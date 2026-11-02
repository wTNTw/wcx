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
fn should_inject(module_dir_fd: i32, process_name: &str) -> bool {
    let package = target_package(process_name);

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
        if line == package {
            return true;
        }
    }
    // 有配置文件但没有命中：不注入
    false
}

fn send_check_request(api: *mut ApiTable, uid: i32, process_name: &str) -> u8 {
    let fd = unsafe { (*api).connect_companion() };
    if fd < 0 {
        return 2; // COMPANION_ERROR
    }
    let _ = write_u8_to_fd(fd, COMPANION_REQUEST_ENABLED);
    // Write uid as i32 little-endian
    let uid_bytes = uid.to_ne_bytes();
    unsafe {
        libc::write(fd, uid_bytes.as_ptr().cast(), 4);
    }
    let _ = write_string_to_fd(fd, process_name);
    let status = read_u8_from_fd(fd).unwrap_or(2);
    unsafe { libc::close(fd) };
    status
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

// ── Lifecycle callbacks ───────────────────────────────────────────────────────

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

    let mod_fd = (*module.api).get_module_dir();
    if mod_fd < 0 {
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }

    // 总开关：模块目录里的 disable（WebUI / 管理器都会写这个文件），存在则不注入
    if unsafe { libc::faccessat(mod_fd, c"disable".as_ptr(), libc::F_OK, 0) } == 0 {
        logi!("Zygisk: 模块已停用（disable），跳过 {nice_name}");
        unsafe { libc::close(mod_fd) };
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }

    // 目标判定：本地 target.list（默认 com.tencent.mm），不再依赖 root companion
    if !should_inject(mod_fd, &nice_name) {
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
    let t_loader = std::time::Instant::now();
    let cl = crate::payload::build_dex_classloader(module.env, &apk_dst, parent);
    if cl.is_null() {
        loge!("Zygisk: failed to build PathClassLoader");
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
        "Zygisk: 冷启动耗时 publish={publish_ms}ms loader={loader_ms}ms art={}ms total={}ms ({})",
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
