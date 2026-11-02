// APK 发布（跨 app specialization 复用）+ PathClassLoader 构造。

use crate::loge;
use anyhow::{Context, Result, ensure};
use jni::sys::{JNIEnv as RawJNIEnv, jobject};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
};

/// Copy the already-open module APK after specialization.
///
/// 冷启动优化：目标文件名由源文件的 `(dev, inode, size, mtime)` 推导，
/// 已存在且大小一致时**直接复用、不再复制**（升级模块会换 inode/mtime，自然换新文件）。
/// 顺带清理其它版本的旧副本，避免 /data 目录无限增长。
pub fn publish_apk(mut source: File, data_dir: &str) -> Result<PathBuf> {
    use std::os::unix::fs::MetadataExt;

    let directory = Path::new(data_dir).join("files/mmkv");
    fs::create_dir_all(&directory)?;

    let meta = source.metadata()?;
    ensure!(meta.len() > 0, "empty module APK");
    ensure!(
        meta.len() <= crate::apk::MAX_APK_BYTES,
        "module APK exceeds size limit"
    );

    let marker = format!(
        "{:x}-{:x}-{:x}-{:x}",
        meta.ino(),
        meta.len(),
        meta.mtime(),
        meta.mtime_nsec()
    );
    let destination = directory.join(format!(".wcx-bootstrap-{marker}.apk"));

    // 命中缓存：只做零拷贝校验，不再读/写 35MB
    if let Ok(existing) = fs::metadata(&destination) {
        if existing.is_file() && existing.len() == meta.len() {
            cleanup_stale_copies(&directory, &destination);
            return Ok(destination);
        }
    }

    let temporary = directory.join(format!(".wcx-bootstrap-{}.tmp", std::process::id()));
    // A prior crash may have left this PID's temporary file. Never follow it.
    match fs::remove_file(&temporary) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }

    let result = (|| {
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(&temporary)?;
        // Dynamic code is read-only before its content is written (the open fd
        // still allows writes), and remains read-only when published.
        output.set_permissions(fs::Permissions::from_mode(0o400))?;
        let mut bytes = [0u8; 65536];
        let mut total = 0u64;
        loop {
            let count = source.read(&mut bytes)?;
            if count == 0 {
                break;
            }
            total += count as u64;
            ensure!(
                total <= crate::apk::MAX_APK_BYTES,
                "module APK exceeds size limit"
            );
            output.write_all(&bytes[..count])?;
        }
        ensure!(total == meta.len(), "module APK shrank while copying");
        output.sync_all()?;
        drop(output);
        // 廉价校验：只读 ZIP 目录，确认 classes*.dex 存在（不解压）
        crate::apk::validate_apk(File::open(&temporary)?).context("invalid module APK")?;
        // rename is atomic, including concurrent copies of the same content.
        fs::rename(&temporary, &destination)?;
        Ok(destination)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    let destination = result?;
    cleanup_stale_copies(&directory, &destination);
    Ok(destination)
}

/// 删除其它版本的 `.wcx-bootstrap-*.apk` / 残留 `.tmp`（尽力而为，失败忽略）。
fn cleanup_stale_copies(directory: &Path, keep: &Path) {
    let Ok(entries) = fs::read_dir(directory) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
            continue;
        };
        if !name.starts_with(".wcx-bootstrap-") {
            continue;
        }
        if path == keep {
            continue;
        }
        let is_tmp = name.ends_with(".tmp");
        if is_tmp || name.ends_with(".apk") {
            let _ = fs::remove_file(&path);
        }
    }
}

/// 用已发布到应用私有目录的 APK 建 `PathClassLoader`。
///
/// 相比 `InMemoryDexClassLoader`（要把 30MB+ dex 全读进内存并建 DirectByteBuffer），
/// 文件方式让 ART 自己 mmap、按需解析，冷启动少一次全量读 + 一份等量内存。
pub unsafe fn build_dex_classloader(
    env: *mut RawJNIEnv,
    apk_path: &Path,
    parent_loader: jobject,
) -> jobject {
    let fns = *env;
    if ((*fns).v1_6.PushLocalFrame)(env, 8) < 0 {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    let loader = (|| {
        let Some(path_str) = apk_path.to_str() else {
            return std::ptr::null_mut();
        };
        let jpath = ((*fns).v1_6.NewStringUTF)(env, path_str.as_ptr().cast());
        if jpath.is_null() {
            return std::ptr::null_mut();
        }
        let cl_class = ((*fns).v1_6.FindClass)(env, c"dalvik/system/PathClassLoader".as_ptr());
        if cl_class.is_null() {
            return std::ptr::null_mut();
        }
        let ctor = ((*fns).v1_6.GetMethodID)(
            env,
            cl_class,
            c"<init>".as_ptr(),
            c"(Ljava/lang/String;Ljava/lang/ClassLoader;)V".as_ptr(),
        );
        if ctor.is_null() {
            return std::ptr::null_mut();
        }
        let loader = ((*fns).v1_6.NewObject)(env, cl_class, ctor, jpath, parent_loader);
        if loader.is_null() {
            return std::ptr::null_mut();
        }
        ((*fns).v1_6.NewGlobalRef)(env, loader)
    })();
    if ((*fns).v1_6.ExceptionCheck)(env) != jni::sys::JNI_FALSE {
        ((*fns).v1_6.ExceptionDescribe)(env);
        ((*fns).v1_6.ExceptionClear)(env);
    }
    ((*fns).v1_6.PopLocalFrame)(env, std::ptr::null_mut());
    if loader.is_null() {
        loge!("Zygisk: PathClassLoader 创建失败");
    }
    loader
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    use zip::{ZipWriter, write::SimpleFileOptions};

    #[test]
    fn publication_reuses_identical_source_and_replaces_changed_versions() {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        struct Directory(PathBuf);
        impl Drop for Directory {
            fn drop(&mut self) {
                let _ = fs::remove_dir_all(&self.0);
            }
        }
        let directory = Directory(std::env::temp_dir().join(format!(
            "wcx-apk-publication-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        )));
        fs::create_dir(&directory.0).unwrap();
        let source = directory.0.join("input.apk");
        let host = directory.0.join("host");

        let write_apk = |dex: &[u8]| {
            let mut zip = ZipWriter::new(File::create(&source).unwrap());
            zip.start_file("classes.dex", SimpleFileOptions::default())
                .unwrap();
            zip.write_all(dex).unwrap();
            zip.finish().unwrap();
        };

        // 第一次发布：真正复制
        write_apk(b"first");
        let original = fs::read(&source).unwrap();
        let path = publish_apk(File::open(&source).unwrap(), host.to_str().unwrap()).unwrap();
        assert_eq!(fs::read(&path).unwrap(), original);
        assert_eq!(fs::metadata(&path).unwrap().permissions().mode() & 0o222, 0);

        // 同一份源文件再发布一次：命中缓存，返回同一路径，不产生新副本
        let again = publish_apk(File::open(&source).unwrap(), host.to_str().unwrap()).unwrap();
        assert_eq!(again, path);
        assert_eq!(fs::read_dir(host.join("files/mmkv")).unwrap().count(), 1);

        // 源文件变化（mtime/size 变）：新路径，旧副本被清理
        std::thread::sleep(std::time::Duration::from_millis(20));
        write_apk(b"second-and-longer");
        let newer = publish_apk(File::open(&source).unwrap(), host.to_str().unwrap()).unwrap();
        assert_ne!(newer, path);
        assert_eq!(fs::read_dir(host.join("files/mmkv")).unwrap().count(), 1);

        // 非法 APK（不是 zip）：发布失败，不影响已有副本
        fs::write(&source, b"corrupted APK").unwrap();
        assert!(publish_apk(File::open(&source).unwrap(), host.to_str().unwrap()).is_err());
        assert_eq!(fs::read(newer).unwrap(), {
            let mut zip = ZipWriter::new(std::io::Cursor::new(Vec::new()));
            zip.start_file("classes.dex", SimpleFileOptions::default())
                .unwrap();
            zip.write_all(b"second-and-longer").unwrap();
            zip.finish().unwrap().into_inner()
        });
    }
}
