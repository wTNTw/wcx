//! Test-only APK entry points. Never registered by a baseline native build.
use jni::sys::{JNI_FALSE, JNI_TRUE, JNIEnv as RawJNIEnv, jboolean, jclass, jint, jlong, jobject};

fn ready(env: *mut RawJNIEnv) -> bool {
    !env.is_null() && wekite_art::is_initialized()
        && unsafe { ((*(*env)).v1_6.ExceptionCheck)(env) == JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_loader_entry_wekite_WeKiteHookBridge_nativeInitialize(
    env: *mut RawJNIEnv, _class: jclass,
) -> jboolean {
    if !env.is_null() && unsafe { ((*(*env)).v1_6.ExceptionCheck)(env) == JNI_FALSE }
        && wekite_art::init(env) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_loader_entry_wekite_WeKiteHookBridge_nativeGetArtMethod(
    env: *mut RawJNIEnv, _class: jclass, executable: jobject,
) -> jlong {
    if ready(env) && !executable.is_null() { wekite_art::get_art_method(env, executable) as jlong } else { 0 }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_loader_entry_wekite_WeKiteHookBridge_nativeHookMethod(
    env: *mut RawJNIEnv, _class: jclass, target: jlong, backup: jlong, bridge: jlong, _hook_id: jlong,
) -> jint {
    if !ready(env) { return -1; }
    wekite_art::hook_method(env, target as usize, backup as usize, bridge as usize)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_loader_entry_wekite_WeKiteHookBridge_nativeUnhookMethod(
    env: *mut RawJNIEnv, _class: jclass, target: jlong, backup: jlong,
) -> jint {
    if !ready(env) { return -1; }
    wekite_art::unhook_method(env, target as usize, backup as usize)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_loader_entry_wekite_WeKiteHookBridge_nativeTrustDexFile(
    env: *mut RawJNIEnv, _class: jclass, dex_file: jobject,
) -> jboolean {
    if ready(env) && wekite_art::trust_dex_file(env, dex_file) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_loader_entry_wekite_WeKiteHookBridge_nativeAllocateInstance(
    env: *mut RawJNIEnv, _class: jclass, target_class: jclass,
) -> jobject {
    if ready(env) && !target_class.is_null() { wekite_art::allocate_instance(env, target_class) } else { std::ptr::null_mut() }
}
