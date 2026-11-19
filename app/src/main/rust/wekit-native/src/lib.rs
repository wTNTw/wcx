//! Core JNI entry points used during process startup.

#![allow(clippy::not_unsafe_ptr_arg_deref, clippy::missing_safety_doc)]

mod crash_handler;
mod crash_triggerer;
mod logging;
mod native_hook;
mod signature_verifier;
mod utils;
#[cfg(feature = "wekite_hook")]
mod wekite_hook;

use std::process::abort;

use crash_handler::{install_crash_handler, uninstall_crash_handler};
use crash_triggerer::trigger_test_crash;
use jni::{
    objects::JObject,
    sys::{
        JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6, JNIEnv as RawJNIEnv, JavaVM, jboolean, jint, jobject,
        jstring,
    },
};
use libc::c_void;

use crate::utils::with_jstring;

/// Install the native crash handler.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_crash_NativeCrashHandler_installNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    crash_log_dir: jstring,
) -> jboolean {
    with_jstring(env, crash_log_dir, |dir| {
        if install_crash_handler(dir) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

/// Uninstall the native crash handler.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_crash_NativeCrashHandler_uninstallNative(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
) {
    uninstall_crash_handler();
}

/// Trigger a deliberate test crash.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_crash_NativeCrashHandler_triggerTestCrashNative(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
    crash_type: jint,
) {
    trigger_test_crash(crash_type);
}

/// Verify the module's signing certificate natively.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_SignatureVerifier_nativeVerify(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    context: jobject,
    package_name: jstring,
) -> jboolean {
    let ok = with_jstring(env, package_name, |pkg| {
        let mut unowned = unsafe { jni::EnvUnowned::from_raw(env) };
        let mut result = false;
        let _ = unowned.with_env(|jni_env| {
            let ctx = unsafe { JObject::from_raw(jni_env, context) };
            result = signature_verifier::verify(jni_env, &ctx, pkg);
            Ok::<(), jni::errors::Error>(())
        });
        result
    });

    if ok {
        JNI_TRUE
    } else {
        abort();
    }
}

/// Required JNI library entry point — returns the JNI version we target.
#[unsafe(no_mangle)]
pub extern "C" fn JNI_OnLoad(_vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    JNI_VERSION_1_6
}
