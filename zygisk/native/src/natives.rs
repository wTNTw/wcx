// natives.rs — JNI native method registration
//
// Registers the two sets of native methods (`ArtHookBridge` and `ZygiskEntry`)
// via `RegisterNatives`.  Both classes are loaded through the
// `InMemoryDexClassLoader` built in `postAppSpecialize`, so standard
// `FindClass` is not used here.
//
// Note: this module is named `natives` rather than `jni` to avoid shadowing
// the external `jni` crate in the module namespace.

use crate::{loge, logi};
use jni::sys::{
    JNI_FALSE, JNI_TRUE, JNIEnv as RawJNIEnv, JNINativeMethod, jboolean, jclass, jint, jobject,
    jstring,
};
use std::ffi::{CStr, CString, c_char, c_void};

// ── JNI helper: load class via ClassLoader.loadClass ─────────────────────────

pub(crate) unsafe fn load_class_from_loader(
    env: *mut RawJNIEnv,
    loader: jobject,
    dot_name: &str,
) -> jclass {
    let fns = *env;
    let jname = CString::new(dot_name).unwrap_or_default();
    let jname_obj = ((*fns).v1_6.NewStringUTF)(env, jname.as_ptr());
    if jname_obj.is_null() {
        return std::ptr::null_mut();
    }
    let loader_cls = ((*fns).v1_6.GetObjectClass)(env, loader);
    let mid = ((*fns).v1_6.GetMethodID)(
        env,
        loader_cls,
        c"loadClass".as_ptr(),
        c"(Ljava/lang/String;)Ljava/lang/Class;".as_ptr(),
    );
    if mid.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    ((*fns).v1_6.CallObjectMethod)(env, loader, mid, jname_obj) as jclass
}

// ── ArtHookBridge JNI implementations ────────────────────────────────────────

extern "C" fn jni_hook_method(
    env: *mut RawJNIEnv,
    _class: jclass,
    target: jobject,
    hooker: jobject,
    callback: jobject,
) -> jobject {
    crate::art::hook_method(env, target, hooker, callback)
}

extern "C" fn jni_deoptimize(env: *mut RawJNIEnv, _class: jclass, target: jobject) -> jboolean {
    if crate::art::deoptimize(env, target) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

extern "C" fn jni_allocate_instance(
    env: *mut RawJNIEnv,
    _class: jclass,
    target_class: jclass,
) -> jobject {
    crate::art::allocate_instance(env, target_class)
}

extern "C" fn jni_hide_loaded_module_libraries(
    env: *mut RawJNIEnv,
    _class: jclass,
    directory: jstring,
) -> jboolean {
    if directory.is_null() {
        return JNI_FALSE;
    }
    let fns = *env;
    let chars = ((*fns).v1_6.GetStringUTFChars)(env, directory, std::ptr::null_mut());
    if chars.is_null() {
        return JNI_FALSE;
    }
    let dir = match CStr::from_ptr(chars as *const c_char).to_str() {
        Ok(s) => s.to_owned(),
        Err(_) => String::new(),
    };
    ((*fns).v1_6.ReleaseStringUTFChars)(env, directory, chars);
    if dir.is_empty() {
        return JNI_FALSE;
    }
    let ok = crate::so_hider::hide_libraries_in_dir(&dir) >= 0;
    if ok { JNI_TRUE } else { JNI_FALSE }
}

// ── ZygiskEntry JNI implementations ──────────────────────────────────────────

unsafe fn get_class_loader(env: *mut RawJNIEnv, entry_class: jclass) -> jobject {
    let fns = *env;
    let class_cls = ((*fns).v1_6.FindClass)(env, c"java/lang/Class".as_ptr());
    if class_cls.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    let mid = ((*fns).v1_6.GetMethodID)(
        env,
        class_cls,
        c"getClassLoader".as_ptr(),
        c"()Ljava/lang/ClassLoader;".as_ptr(),
    );
    ((*fns).v1_6.DeleteLocalRef)(env, class_cls);
    if mid.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    let loader = ((*fns).v1_6.CallObjectMethod)(env, entry_class as jobject, mid);
    if ((*fns).v1_6.ExceptionCheck)(env) != JNI_FALSE {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    loader
}

extern "C" fn jni_native_initialize(env: *mut RawJNIEnv, entry: jclass) -> jboolean {
    unsafe {
        if !crate::art::is_initialized() {
            loge!("WcxZygisk: ZygiskEntry.nativeInitialize: LSPlant bootstrap did not complete");
            return JNI_FALSE;
        }
        let loader = get_class_loader(env, entry);
        if loader.is_null() {
            return JNI_FALSE;
        }
        let ok = register_hook_bridge_natives(env, loader);
        if !ok {
            loge!("WcxZygisk: ZygiskEntry.nativeInitialize: failed to register ArtHookBridge");
        }
        ((*(*env)).v1_6.DeleteLocalRef)(env, loader);
        if ok { JNI_TRUE } else { JNI_FALSE }
    }
}

pub unsafe fn register_hook_bridge_natives(env: *mut RawJNIEnv, class_loader: jobject) -> bool {
    let class = load_class_from_loader(
        env,
        class_loader,
        "dev.ujhhgtg.wekit.loader.entry.zygisk.ArtHookBridge",
    );
    if class.is_null() {
        loge!("WcxZygisk: failed to load ArtHookBridge class");
        return false;
    }

    let methods = [
        JNINativeMethod {
            name: c"nativeHookMethod".as_ptr() as *mut c_char,
            signature: c"(Ljava/lang/reflect/Executable;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;".as_ptr() as *mut c_char,
            fnPtr: jni_hook_method as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeDeoptimize".as_ptr() as *mut c_char,
            signature: c"(Ljava/lang/reflect/Executable;)Z".as_ptr() as *mut c_char,
            fnPtr: jni_deoptimize as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeAllocateInstance".as_ptr() as *mut c_char,
            signature: c"(Ljava/lang/Class;)Ljava/lang/Object;".as_ptr() as *mut c_char,
            fnPtr: jni_allocate_instance as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeHideLoadedModuleLibraries".as_ptr() as *mut c_char,
            signature: c"(Ljava/lang/String;)Z".as_ptr() as *mut c_char,
            fnPtr: jni_hide_loaded_module_libraries as *mut c_void,
        },
    ];

    let fns = *env;
    let ret = ((*fns).v1_6.RegisterNatives)(env, class, methods.as_ptr(), methods.len() as jint);
    ((*fns).v1_6.DeleteLocalRef)(env, class);
    if ret == 0 {
        logi!("WcxZygisk: ArtHookBridge natives registered");
        true
    } else {
        loge!("WcxZygisk: RegisterNatives(ArtHookBridge) failed: {ret}");
        false
    }
}

/// Register ZygiskEntry native methods.
///
/// # Safety
///
/// `env` must be a valid JNIEnv pointer. `class_loader` must be a valid
/// reference to the InMemoryDexClassLoader holding ZygiskEntry.
pub unsafe fn register_entry_natives(env: *mut RawJNIEnv, class_loader: jobject) -> bool {
    let class = load_class_from_loader(
        env,
        class_loader,
        "com.Johnny.wcx.loader.entry.zygisk.ZygiskEntry",
    );
    if class.is_null() {
        loge!("WcxZygisk: failed to load ZygiskEntry class");
        return false;
    }

    let mut methods: [JNINativeMethod; 1] = [JNINativeMethod {
        name: c"nativeInitialize".as_ptr() as *mut c_char,
        signature: c"()Z".as_ptr() as *mut c_char,
        fnPtr: jni_native_initialize as *mut c_void,
    }];

    let fns = *env;
    let ret = ((*fns).v1_6.RegisterNatives)(env, class, methods.as_mut_ptr(), 1);
    if ret == 0 {
        logi!("WcxZygisk: ZygiskEntry natives registered");
        true
    } else {
        loge!("WcxZygisk: RegisterNatives(ZygiskEntry) failed: {ret}");
        false
    }
}
