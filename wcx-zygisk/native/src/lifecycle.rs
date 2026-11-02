// lifecycle — Zygisk module lifecycle callbacks
//
// Implements the three specialization hooks called by the Zygisk framework:
// `preAppSpecialize` (allow-list + companion IPC + resource acquisition),
// `postAppSpecialize` (APK publication, in-memory DEX bootstrap), and
// `preServerSpecialize` (dlclose — module does not inject into system_server).

use crate::protocol::{
    COMPANION_ENABLED, COMPANION_REQUEST_ENABLED, COMPANION_REQUEST_TELEGRAM_SESSION,
    read_u8_from_fd, write_string_to_fd, write_u8_to_fd,
};
use crate::zygisk::{ApiTable, AppSpecializeArgs, DLCLOSE_MODULE_LIBRARY, ServerSpecializeArgs};
use crate::{loge, logi};
use jni::sys::{JNIEnv as RawJNIEnv, jobject, jstring};
use std::{
    ffi::{CStr, c_char},
    fs::File,
    os::{
        fd::{FromRawFd, OwnedFd},
        unix::io::AsRawFd,
    },
};

const WCX_RUN_JAVA_ENTRY: bool = true;

pub struct WcxZygiskModule {
    pub api: *mut ApiTable,
    pub env: *mut RawJNIEnv,
    // filled in preAppSpecialize
    pub module_dir_fd: Option<OwnedFd>,
    pub data_dir: String,
    pub process_name: String,
    pub telegram_socket_name: Option<String>,
    pub enabled: bool,
    // filled in postAppSpecialize
    pub module_classloader: Option<jobject>,
}

impl WcxZygiskModule {
    pub fn new(api: *mut ApiTable, env: *mut RawJNIEnv) -> Self {
        Self {
            api,
            env,
            module_dir_fd: None,
            data_dir: String::new(),
            process_name: String::new(),
            telegram_socket_name: None,
            enabled: false,
            module_classloader: None,
        }
    }
}

// Helper: dereference a C++ reference-field (stored as *mut T) and read the jstring.
unsafe fn read_jstring(env: *mut RawJNIEnv, field_ptr: *mut jstring) -> Option<String> {
    if field_ptr.is_null() {
        return None;
    }
    let jstr = *field_ptr;
    if jstr.is_null() {
        return None;
    }
    let fns = *env;
    let chars = ((*fns).v1_6.GetStringUTFChars)(env, jstr, std::ptr::null_mut());
    if chars.is_null() {
        return None;
    }
    let s = CStr::from_ptr(chars as *const c_char)
        .to_string_lossy()
        .into_owned();
    ((*fns).v1_6.ReleaseStringUTFChars)(env, jstr, chars);
    Some(s)
}


/// 目标包名（进程名去掉 `:xxx` 后缀）
fn target_package(process_name: &str) -> &str {
    process_name.split(':').next().unwrap_or(process_name)
}

/// 是否应当注入：读 `<模块目录>/target.list`（一行一个包名，# 开头为注释）。
/// 文件不存在时默认只注入 com.tencent.mm。命中包名的所有进程（含子进程）都会注入。
/// 是否应当注入。
///
/// `target.list` 支持两种行格式：
///   * `<userId>\t<package>`  —— 只注入该 Android 用户下的该包（双开/分身靠它区分）
///   * `<package>`            —— 兼容旧格式，等同于该包在所有用户下都注入
///
/// 文件不存在 = 默认只注入 `com.tencent.mm`（所有用户）；文件存在但为空 = 一个都不注入。
fn should_inject(module_dir_fd: i32, process_name: &str, uid: i32) -> bool {
    let package = target_package(process_name);
    let user_id = uid / 100_000; // Android userId = uid / PER_USER_RANGE

    let fd = unsafe {
        libc::openat(
            module_dir_fd,
            c"target.list".as_ptr(),
            libc::O_RDONLY | libc::O_CLOEXEC,
        )
    };
    if fd < 0 {
        return package == "com.tencent.mm";
    }

    let mut buf = [0u8; 4096];
    let read = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
    unsafe { libc::close(fd) };
    if read <= 0 {
        // 文件存在但为空 = 用户显式清空了作用域（WebUI 全关），此时不注入任何进程。
        // 想要“恢复默认”用 config.sh reset（删除文件）。
        return false;
    }

    let text = String::from_utf8_lossy(&buf[..read as usize]);
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        // 支持 tab / 空格分隔：<userId> <package>
        let mut parts = line.split(|c: char| c == '\t' || c == ' ');
        let first = parts.next().unwrap_or("");
        let second = parts.next().unwrap_or("").trim();

        if second.is_empty() {
            // 旧格式：只有包名 → 所有用户
            if first == package {
                return true;
            }
            continue;
        }

        if second != package {
            continue;
        }
        if first == "*" || first.parse::<i32>().map(|id| id == user_id).unwrap_or(false) {
            return true;
        }
    }
    false
}

fn negotiate_telegram_socket(api: *mut ApiTable, uid: i32, process_name: &str) -> Option<String> {
    let fd = unsafe { (*api).connect_companion() };
    if fd < 0 {
        return None;
    }
    let _ = write_u8_to_fd(fd, COMPANION_REQUEST_TELEGRAM_SESSION);
    let uid_bytes = uid.to_ne_bytes();
    unsafe {
        libc::write(fd, uid_bytes.as_ptr().cast(), 4);
    }
    let _ = write_string_to_fd(fd, process_name);
    let status = read_u8_from_fd(fd).unwrap_or(2);
    if status != COMPANION_ENABLED {
        unsafe { libc::close(fd) };
        return None;
    }
    let name = crate::protocol::read_string_from_fd(fd).ok();
    unsafe { libc::close(fd) };
    name
}


/// 宿主 ClassLoader 里是否存在某个类（用于检测是否已有 Xposed/LSPosed 注入）。
unsafe fn loader_has_class(env: *mut RawJNIEnv, loader: jobject, name: &str) -> bool {
    if loader.is_null() {
        return false;
    }
    let fns = *env;
    let cl_cls = ((*fns).v1_6.FindClass)(env, c"java/lang/ClassLoader".as_ptr());
    if cl_cls.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        return false;
    }
    let load = ((*fns).v1_6.GetMethodID)(
        env,
        cl_cls,
        c"loadClass".as_ptr(),
        c"(Ljava/lang/String;)Ljava/lang/Class;".as_ptr(),
    );
    ((*fns).v1_6.DeleteLocalRef)(env, cl_cls);
    if load.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        return false;
    }
    let jname = ((*fns).v1_6.NewStringUTF)(env, name.as_ptr().cast());
    if jname.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        return false;
    }
    let found = ((*fns).v1_6.CallObjectMethod)(env, loader, load, jname);
    ((*fns).v1_6.DeleteLocalRef)(env, jname);
    let ok = ((*fns).v1_6.ExceptionCheck)(env) == jni::sys::JNI_FALSE && !found.is_null();
    if !found.is_null() {
        ((*fns).v1_6.DeleteLocalRef)(env, found);
    }
    // 无论成功失败都要清掉 pending exception，否则 ART 会 abort
    if ((*fns).v1_6.ExceptionCheck)(env) != jni::sys::JNI_FALSE {
        ((*fns).v1_6.ExceptionClear)(env);
    }
    ok
}

/// 这个进程里是否已经有 Xposed/LSPosed 在注入。
/// 两者同时 hook ART 内部函数会互相踩坏 trampoline（表现为微信启动即 SIGILL），
/// 所以检测到就跳过本模块，让用户二选一。
unsafe fn has_external_xposed(env: *mut RawJNIEnv, loader: jobject) -> bool {
    loader_has_class(env, loader, "de.robv.android.xposed.XposedBridge")
        || loader_has_class(env, loader, "io.github.libxposed.api.XposedModule")
        || loader_has_class(env, loader, "com.Johnny.wcx.loader.entry.lsp10x.Lsp10xHookImpl")
}


/// 检测是否有其它 Xposed 框架处于启用状态（目前认 LSPosed 系）。
///
/// 两套 ART hook 同时存在会互相踩坏 trampoline，表现为微信启动即 SIGILL
/// （实测 `Class::SetStatus+20` / `MarkVisiblyInitialized+236`），所以默认让路。
/// 想强制用本模块：在模块目录放 `force` 文件。
fn external_xposed_module() -> Option<&'static str> {
    const CANDIDATES: &[(&str, &str)] = &[
        ("/data/adb/modules/zygisk_lsposed", "LSPosed (Zygisk)"),
        ("/data/adb/modules/riru_lsposed", "LSPosed (Riru)"),
    ];
    for (dir, name) in CANDIDATES {
        let path = std::path::Path::new(dir);
        if !path.exists() {
            continue;
        }
        // 模块被停用时（Root 管理器会写 disable / remove）不算
        if path.join("disable").exists() || path.join("remove").exists() {
            continue;
        }
        return Some(name);
    }
    None
}

pub unsafe fn do_pre_app_specialize(module: &mut WcxZygiskModule, args: *mut AppSpecializeArgs) {
    let nice_name = match read_jstring(module.env, (*args).nice_name) {
        Some(s) if !s.is_empty() && s.len() <= 255 => s,
        _ => {
            (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
            return;
        }
    };
    let app_data_dir = match read_jstring(module.env, (*args).app_data_dir) {
        Some(s) if !s.is_empty() => s,
        _ => {
            (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
            return;
        }
    };
    let uid = *(*args).uid;
    let wechat_family = nice_name.starts_with("com.tencent.mm");
    if wechat_family {
        logi!(
            "Zygisk: preAppSpecialize {} uid={} user={}",
            nice_name,
            uid,
            uid / 100_000
        );
    }

    let mod_fd = (*module.api).get_module_dir();
    if mod_fd < 0 {
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }

    // 模块停用：Root 管理器停用模块时会写 disable，存在则不注入
    if unsafe { libc::faccessat(mod_fd, c"disable".as_ptr(), libc::F_OK, 0) } == 0 {
        logi!("Zygisk: 模块已停用（disable），跳过 {nice_name}");
        unsafe { libc::close(mod_fd) };
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }

    // 其它 Xposed 框架（LSPosed 等）启用时让路：双 hook 会直接崩微信
    if let Some(framework) = external_xposed_module() {
        let forced = unsafe { libc::faccessat(mod_fd, c"force".as_ptr(), libc::F_OK, 0) } == 0;
        if !forced {
            logi!(
                "Zygisk: 检测到 {framework} 已启用，跳过本模块以免双 hook 冲突（想强制启用：在模块目录放 force 文件）"
            );
            unsafe { libc::close(mod_fd) };
            (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
            return;
        }
        logi!("Zygisk: 检测到 {framework}，但存在 force 文件，继续注入");
    }

    // 目标判定：本地 target.list（默认 com.tencent.mm），不再依赖 root companion
    if !should_inject(mod_fd, &nice_name, uid) {
        if wechat_family {
            logi!("Zygisk: {} 不在注入作用域内（uid={}）", nice_name, uid);
        }
        unsafe { libc::close(mod_fd) };
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }
    logi!("Zygisk: {nice_name} 在注入名单内");
    // Preserve the pre-dual-format lifecycle: keep the module directory, then
    // open and copy the payload in postAppSpecialize. No exemptFd dependency.
    module.module_dir_fd = Some(OwnedFd::from_raw_fd(mod_fd));
    module.data_dir = app_data_dir;
    module.process_name = nice_name.clone();

    // Non-isolated processes: negotiate Telegram socket, write to global
    if !nice_name.contains(':')
        && let Some(name) = negotiate_telegram_socket(module.api, uid, &nice_name)
    {
        *crate::TELEGRAM_SOCKET_NAME.lock().unwrap() = name.clone();
        module.telegram_socket_name = Some(name);
        logi!("Zygisk: retained Telegram root companion socket for {nice_name}");
    }

    module.enabled = true;
    logi!("Zygisk: preAppSpecialize OK for {nice_name}");
}

pub unsafe fn do_post_app_specialize(module: &mut WcxZygiskModule, _args: *const AppSpecializeArgs) {
    if !module.enabled {
        return;
    }
    module.enabled = false;
    let Some(module_dir) = module.module_dir_fd.take() else {
        return;
    };
    let fd = libc::openat(
        module_dir.as_raw_fd(),
        c"module.apk".as_ptr(),
        libc::O_RDONLY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
    );
    if fd < 0 {
        loge!(
            "Zygisk: cannot open module.apk: {}",
            std::io::Error::last_os_error()
        );
        return;
    }
    let apk = File::from_raw_fd(fd);
    if !apk.metadata().is_ok_and(|metadata| {
        metadata.is_file() && metadata.len() > 0 && metadata.len() <= crate::apk::MAX_APK_BYTES
    }) {
        loge!("Zygisk: invalid module.apk");
        return;
    }
    let data_dir = module.data_dir.clone();
    let started = std::time::Instant::now();
    let apk_dst = match crate::payload::publish_apk(apk, &data_dir) {
        Ok(path) => path,
        Err(error) => {
            loge!("Zygisk: cannot prepare module APK: {error:#}");
            return;
        }
    };
    let publish_ms = started.elapsed().as_millis();

    // Build InMemoryDexClassLoader
    let fns = *module.env;
    let sys_cl_class = ((*fns).v1_6.FindClass)(module.env, c"java/lang/ClassLoader".as_ptr());
    let get_sys_id = ((*fns).v1_6.GetStaticMethodID)(
        module.env,
        sys_cl_class,
        c"getSystemClassLoader".as_ptr(),
        c"()Ljava/lang/ClassLoader;".as_ptr(),
    );
    let parent = ((*fns).v1_6.CallStaticObjectMethod)(module.env, sys_cl_class, get_sys_id);
    // 已经有 Xposed/LSPosed 在注入这个进程时直接退出：两套 ART hook 同时存在必崩
    if has_external_xposed(module.env, parent) {
        logi!(
            "Zygisk: 检测到 Xposed/LSPosed 已注入 {}，跳过本模块（两者不能同时启用）",
            module.process_name
        );
        return;
    }

    let t_loader = std::time::Instant::now();
    let odex_dir = std::path::Path::new(&data_dir).join("files/mmkv/odex");
    let _ = std::fs::create_dir_all(&odex_dir);
    let mut cl = crate::payload::build_dex_classloader(module.env, &apk_dst, &odex_dir, parent);
    let mut loader_kind = "file";
    // 文件式加载偶尔会得到空的 dexElements：**首次**打开刚发布的 APK（通常是主进程）时
    // ART 的 odex 初始化会失败一次，随后的进程就正常。这里先重试几次，
    // 仍失败再退回内存式加载（慢一点，但一定能用）。
    let mut attempt = 0;
    while !cl.is_null() && crate::art::class_loader_dex_count(module.env, cl) == 0 {
        attempt += 1;
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        cl = std::ptr::null_mut();
        if attempt >= 3 {
            loge!("Zygisk: 文件式 dex 加载仍为空（试了 {attempt} 次），回退 InMemoryDexClassLoader");
            break;
        }
        loge!("Zygisk: 文件式 dex 加载为空，第 {attempt} 次重试");
        std::thread::sleep(std::time::Duration::from_millis(40));
        let parent_retry = ((*fns).v1_6.CallStaticObjectMethod)(
            module.env,
            sys_cl_class,
            get_sys_id,
        );
        cl = crate::payload::build_dex_classloader(module.env, &apk_dst, &odex_dir, parent_retry);
    }
    if cl.is_null() {
        match std::fs::File::open(&apk_dst)
            .map_err(anyhow::Error::from)
            .and_then(crate::apk::read_dex)
        {
            Ok(dex) => {
                let parent2 = ((*fns).v1_6.CallStaticObjectMethod)(
                    module.env,
                    sys_cl_class,
                    get_sys_id,
                );
                cl = crate::payload::build_in_memory_classloader(module.env, &dex, parent2);
                loader_kind = "memory";
                if dex.is_empty() {
                    loge!("Zygisk: module APK 内没有 dex");
                }
            }
            Err(error) => {
                loge!("Zygisk: 读取 payload dex 失败: {error:#}");
            }
        }
    }
    if cl.is_null() {
        loge!("Zygisk: failed to build module ClassLoader");
        return;
    }
    let loader_ms = t_loader.elapsed().as_millis();

    // LSPlant needs unrestricted hidden-API JNI access during initialization.
    // Do this while still in the native Zygote callback, before a module Java
    // frame becomes the caller. Keep the loader resident after any partial ART
    // initialization: LSPlant's runtime inline hooks live until process exit.
    let t_art = std::time::Instant::now();
    if !crate::art::init(module.env) || !crate::art::trust_class_loader(module.env, cl) {
        if ((*fns).v1_6.ExceptionCheck)(module.env) != jni::sys::JNI_FALSE {
            ((*fns).v1_6.ExceptionDescribe)(module.env);
            ((*fns).v1_6.ExceptionClear)(module.env);
        }
        loge!("Zygisk: failed to initialize LSPlant or trust module DEX");
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }

    // Load ZygiskEntry class
    let entry_name = "com.Johnny.wcx.loader.entry.zygisk.ZygiskEntry";
    let entry_cls = crate::natives::load_class_from_loader(module.env, cl, entry_name);
    if entry_cls.is_null() {
        loge!("Zygisk: ZygiskEntry class not found");
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }

    // Register ZygiskEntry native methods
    if !crate::natives::register_entry_natives(module.env, cl) {
        loge!("Zygisk: failed to register ZygiskEntry bootstrap JNI");
        ((*fns).v1_6.DeleteLocalRef)(module.env, entry_cls);
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }

    logi!(
        "Zygisk: 冷启动耗时 publish={publish_ms}ms loader={loader_ms}ms({loader_kind}) art={}ms total={}ms ({})",
        t_art.elapsed().as_millis(),
        started.elapsed().as_millis(),
        module.process_name
    );

    // Call ZygiskEntry.init(processName, dataDir, apkPath)
    let init_mid = ((*fns).v1_6.GetStaticMethodID)(
        module.env,
        entry_cls,
        c"init".as_ptr(),
        c"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V".as_ptr(),
    );
    if init_mid.is_null() {
        loge!("Zygisk: ZygiskEntry.init method not found");
        ((*fns).v1_6.ExceptionClear)(module.env);
        ((*fns).v1_6.DeleteLocalRef)(module.env, entry_cls);
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }
    let process_name_c = std::ffi::CString::new(module.process_name.as_str()).unwrap_or_default();
    let data_dir_c = std::ffi::CString::new(data_dir.as_str()).unwrap_or_default();
    let apk_path_c = std::ffi::CString::new(apk_dst.to_str().unwrap()).unwrap_or_default();
    let j_process = ((*fns).v1_6.NewStringUTF)(module.env, process_name_c.as_ptr());
    let j_data = ((*fns).v1_6.NewStringUTF)(module.env, data_dir_c.as_ptr());
    let j_apk = ((*fns).v1_6.NewStringUTF)(module.env, apk_path_c.as_ptr());
    if j_process.is_null()
        || j_data.is_null()
        || j_apk.is_null()
        || ((*fns).v1_6.ExceptionCheck)(module.env) != jni::sys::JNI_FALSE
    {
        ((*fns).v1_6.ExceptionClear)(module.env);
        loge!("Zygisk: ZygiskEntry.init argument allocation failed");
        for j in [j_process, j_data, j_apk].iter().filter(|&&p| !p.is_null()) {
            ((*fns).v1_6.DeleteLocalRef)(module.env, *j);
        }
        ((*fns).v1_6.DeleteLocalRef)(module.env, entry_cls);
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }
    // 临时诊断开关：只做原生 LSPlant 初始化，不进入 Kotlin 入口
    // 诊断模式：跳过 Kotlin 入口（验证仅原生 LSPlant 是否会崩）
    if WCX_RUN_JAVA_ENTRY {
        ((*fns).v1_6.CallStaticVoidMethod)(module.env, entry_cls, init_mid, j_process, j_data, j_apk);
    }
    let failed = ((*fns).v1_6.ExceptionCheck)(module.env) != jni::sys::JNI_FALSE;
    if failed {
        ((*fns).v1_6.ExceptionDescribe)(module.env);
        ((*fns).v1_6.ExceptionClear)(module.env);
        loge!("Zygisk: ZygiskEntry.init failed");
    } else {
        logi!(
            "Zygisk: ZygiskEntry.init completed for {}",
            module.process_name
        );
    }
    ((*fns).v1_6.DeleteLocalRef)(module.env, j_process);
    ((*fns).v1_6.DeleteLocalRef)(module.env, j_data);
    ((*fns).v1_6.DeleteLocalRef)(module.env, j_apk);
    ((*fns).v1_6.DeleteLocalRef)(module.env, entry_cls);
    if failed {
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }
    // Keep classloader alive for hook bridge class resolution
    module.module_classloader = Some(cl);
    logi!("Zygisk: postAppSpecialize complete");
}

pub unsafe fn do_pre_server_specialize(module: &mut WcxZygiskModule, _args: *mut ServerSpecializeArgs) {
    (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
}

#[cfg(test)]
mod scope_tests {
    use super::should_inject;
    use std::ffi::CString;
    use std::fs;
    use std::os::unix::io::RawFd;

    struct TempDir(std::path::PathBuf);

    impl TempDir {
        fn new() -> Self {
            let dir = std::env::temp_dir().join(format!(
                "wcx-scope-{}-{:?}",
                std::process::id(),
                std::thread::current().id()
            ));
            let _ = fs::remove_dir_all(&dir);
            fs::create_dir_all(&dir).unwrap();
            TempDir(dir)
        }

        fn fd(&self) -> RawFd {
            let c = CString::new(self.0.to_str().unwrap()).unwrap();
            unsafe { libc::open(c.as_ptr(), libc::O_RDONLY | libc::O_DIRECTORY) }
        }

        fn write(&self, content: &str) {
            fs::write(self.0.join("target.list"), content).unwrap();
        }

        fn remove(&self) {
            let _ = fs::remove_file(self.0.join("target.list"));
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    const APP_ID: i32 = 10_000;
    const UID_USER0: i32 = APP_ID;
    const UID_USER999: i32 = 999 * 100_000 + APP_ID;

    #[test]
    fn scope_matches_per_user_and_legacy() {
        let dir = TempDir::new();
        let fd = dir.fd();
        assert!(fd >= 0);

        // 1) 没有 target.list：默认注入 com.tencent.mm（任意用户）
        assert!(should_inject(fd, "com.tencent.mm", UID_USER0));
        assert!(should_inject(fd, "com.tencent.mm", UID_USER999));
        assert!(!should_inject(fd, "com.example.other", UID_USER0));

        // 2) 显式 (userId, package)：只命中该用户（这就是双开能单独开关的关键）
        dir.write("0\tcom.tencent.mm\n");
        assert!(should_inject(fd, "com.tencent.mm", UID_USER0));
        assert!(!should_inject(fd, "com.tencent.mm", UID_USER999));

        // 3) 子进程按包名匹配，仍受用户限制
        assert!(should_inject(fd, "com.tencent.mm:push", UID_USER0));
        assert!(!should_inject(fd, "com.tencent.mm:push", UID_USER999));

        // 4) 只开分身
        dir.write("999\tcom.tencent.mm\n");
        assert!(!should_inject(fd, "com.tencent.mm", UID_USER0));
        assert!(should_inject(fd, "com.tencent.mm", UID_USER999));

        // 5) 旧格式（只有包名）= 所有用户
        dir.write("com.tencent.mm\n");
        assert!(should_inject(fd, "com.tencent.mm", UID_USER0));
        assert!(should_inject(fd, "com.tencent.mm", UID_USER999));

        // 6) 空文件 = 一个都不注入
        dir.write("");
        assert!(!should_inject(fd, "com.tencent.mm", UID_USER0));

        // 7) 删除文件 = 恢复默认
        dir.remove();
        assert!(should_inject(fd, "com.tencent.mm", UID_USER0));

        unsafe { libc::close(fd) };
    }
}
