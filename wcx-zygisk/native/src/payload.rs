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

    // 注意：不要用隐藏文件（前缀 ）。ART 对这类路径会退化成
    // "ClassLoader referenced unknown path" 并返回空的 DexPathList。
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
    let destination = directory.join(format!("wcx-payload-{marker}.apk"));

    // 命中缓存：只做零拷贝校验，不再读/写 35MB
    if let Ok(existing) = fs::metadata(&destination) {
        if existing.is_file() && existing.len() == meta.len() {
            cleanup_stale_copies(&directory, &destination);
            return Ok(destination);
        }
    }

    let temporary = directory.join(format!("wcx-payload-{}.tmp", std::process::id()));
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

/// 旧 payload 的保留时长：别的进程可能还在用它（主进程/子进程启动时间不同步，
/// 模块升级瞬间更是如此）。实测删掉正在使用的文件会让那个进程
/// `StartupAgent failed: NoSuchFileException: wcx-payload-*.apk` → 整个模块不生效。
const STALE_GRACE: std::time::Duration = std::time::Duration::from_secs(600);

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
        if !name.starts_with("wcx-payload-") && !name.starts_with(".wcx-bootstrap-") {
            continue;
        }
        if path == keep {
            continue;
        }
        // 刚生成的文件可能是别的进程正在用的 payload / 正在写的临时文件，先放过。
        let fresh = fs::metadata(&path)
            .and_then(|meta| meta.modified())
            .ok()
            .and_then(|modified| modified.elapsed().ok())
            .map(|age| age < STALE_GRACE)
            .unwrap_or(false);
        if fresh {
            continue;
        }
        let is_tmp = name.ends_with(".tmp");
        if is_tmp || name.ends_with(".apk") {
            let _ = fs::remove_file(&path);
        }
    }
}

/// 内存式加载（回退路径）：dex 全量读进内存，用 InMemoryDexClassLoader。
pub unsafe fn build_in_memory_classloader(
    env: *mut RawJNIEnv,
    dex_buffers: &[Vec<u8>],
    parent_loader: jobject,
) -> jobject {
    if dex_buffers.is_empty() {
        return std::ptr::null_mut();
    }
    let fns = *env;
    if ((*fns).v1_6.PushLocalFrame)(env, 16) < 0 {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    let loader = (|| {
        let bb_class = ((*fns).v1_6.FindClass)(env, c"java/nio/ByteBuffer".as_ptr());
        if bb_class.is_null() {
            return std::ptr::null_mut();
        }
        let arr = ((*fns).v1_6.NewObjectArray)(
            env,
            dex_buffers.len() as i32,
            bb_class,
            std::ptr::null_mut(),
        );
        if arr.is_null() {
            return std::ptr::null_mut();
        }
        for (i, buf) in dex_buffers.iter().enumerate() {
            let bb =
                ((*fns).v1_6.NewDirectByteBuffer)(env, buf.as_ptr() as *mut _, buf.len() as i64);
            if bb.is_null() {
                return std::ptr::null_mut();
            }
            ((*fns).v1_6.SetObjectArrayElement)(env, arr, i as i32, bb);
            ((*fns).v1_6.DeleteLocalRef)(env, bb);
        }
        let cl_class =
            ((*fns).v1_6.FindClass)(env, c"dalvik/system/InMemoryDexClassLoader".as_ptr());
        if cl_class.is_null() {
            return std::ptr::null_mut();
        }
        let ctor = ((*fns).v1_6.GetMethodID)(
            env,
            cl_class,
            c"<init>".as_ptr(),
            c"([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V".as_ptr(),
        );
        if ctor.is_null() {
            return std::ptr::null_mut();
        }
        let loader = ((*fns).v1_6.NewObject)(env, cl_class, ctor, arr, parent_loader);
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
    loader
}

/// 用已发布到应用私有目录的 APK 建 `PathClassLoader`。
///
/// 相比 `InMemoryDexClassLoader`（要把 30MB+ dex 全读进内存并建 DirectByteBuffer），
/// 文件方式让 ART 自己 mmap、按需解析，冷启动少一次全量读 + 一份等量内存。
pub unsafe fn build_dex_classloader(
    env: *mut RawJNIEnv,
    apk_path: &Path,
    optimized_dir: &Path,
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
        let opt_str = optimized_dir.to_string_lossy();
        let jopt = ((*fns).v1_6.NewStringUTF)(env, opt_str.as_ptr().cast());
        if jopt.is_null() {
            return std::ptr::null_mut();
        }
        // DexClassLoader(path, optimizedDirectory, librarySearchPath, parent)
        // 显式给一个可写 optimizedDirectory：ART 打开应用私有 APK 时要用它生成
        // odex/vdex，否则会退化成 "ClassLoader referenced unknown path" 并得到空 dexElements。
        let cl_class = ((*fns).v1_6.FindClass)(env, c"dalvik/system/DexClassLoader".as_ptr());
        if cl_class.is_null() {
            return std::ptr::null_mut();
        }
        let ctor = ((*fns).v1_6.GetMethodID)(
            env,
            cl_class,
            c"<init>".as_ptr(),
            c"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V".as_ptr(),
        );
        if ctor.is_null() {
            return std::ptr::null_mut();
        }
        let loader = ((*fns).v1_6.NewObject)(
            env,
            cl_class,
            ctor,
            jpath,
            jopt,
            std::ptr::null_mut::<std::ffi::c_void>(),
            parent_loader,
        );
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
        loge!("Zygisk: DexClassLoader 创建失败");
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
