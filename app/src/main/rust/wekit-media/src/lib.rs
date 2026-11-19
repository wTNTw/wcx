//! Media JNI entry points loaded on first media use.

#![allow(clippy::not_unsafe_ptr_arg_deref, clippy::missing_safety_doc)]

mod audio_utils;
mod logging;
mod telegram_sticker;
mod utils;

use std::ffi::CString;

use jni::sys::{
    JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6, JNIEnv as RawJNIEnv, JavaVM, jboolean, jint, jlong,
    jobject, jstring,
};
use libc::c_void;

use crate::utils::with_jstring;

fn native_error_string(env: *mut RawJNIEnv, result: Result<(), String>) -> jstring {
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(message) => unsafe {
            let fns = *env;
            let c_str = CString::new(message)
                .unwrap_or_else(|_| CString::new("native conversion failed").unwrap());
            ((*fns).v1_6.NewStringUTF)(env, c_str.as_ptr())
        },
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_com_Johnny_wcx_features_items_chat_MarkdownRendering_convertMarkdownToHtmlNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    markdown_string: jstring,
) -> jstring {
    let result = with_jstring(env, markdown_string, |md_text| {
        markdown::to_html_with_options(md_text, &markdown::Options::gfm())
    });

    match result {
        Ok(html) => unsafe {
            let fns = *env;
            let c_str = CString::new(html).unwrap_or_default();
            ((*fns).v1_6.NewStringUTF)(env, c_str.as_ptr())
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_AudioUtils_anyToSilkNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    input_path: jstring,
    silk_path: jstring,
    sample_rate: jint,
) -> jboolean {
    with_jstring(env, input_path, |input| {
        with_jstring(
            env,
            silk_path,
            |silk| match audio_utils::any_to_silk_at_rate(input, silk, sample_rate.max(0) as u32) {
                Ok(()) => JNI_TRUE,
                Err(err) => {
                    loge!("any_to_silk failed: {:?}", err);
                    JNI_FALSE
                }
            },
        )
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_AudioUtils_pcmToSilkNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    pcm_path: jstring,
    silk_path: jstring,
    silk_sample_rate: jint,
    pcm_sample_rate: jint,
    channels: jint,
) -> jboolean {
    with_jstring(env, pcm_path, |pcm| {
        with_jstring(env, silk_path, |silk| {
            match audio_utils::pcm_file_to_silk(
                pcm,
                silk,
                silk_sample_rate.max(0) as u32,
                pcm_sample_rate.max(0) as u32,
                channels.max(0) as usize,
            ) {
                Ok(()) => JNI_TRUE,
                Err(err) => {
                    loge!("pcm_to_silk failed: {:?}", err);
                    JNI_FALSE
                }
            }
        })
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_AudioUtils_silkToPcmNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    silk_path: jstring,
    pcm_path: jstring,
    sample_rate: jint,
) -> jboolean {
    with_jstring(env, silk_path, |silk| {
        with_jstring(env, pcm_path, |pcm| {
            match audio_utils::silk_to_pcm(silk, pcm, sample_rate) {
                Ok(()) => JNI_TRUE,
                Err(err) => {
                    loge!("silk_to_pcm failed: {:?}", err);
                    JNI_FALSE
                }
            }
        })
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_AudioUtils_pcmToMp3Native(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    pcm_path: jstring,
    mp3_path: jstring,
    sample_rate: jint,
) -> jboolean {
    with_jstring(env, pcm_path, |pcm| {
        with_jstring(env, mp3_path, |mp3| {
            if audio_utils::pcm_to_mp3(pcm, mp3, sample_rate.max(0) as u32, 128) {
                JNI_TRUE
            } else {
                loge!("pcm_to_mp3 failed");
                JNI_FALSE
            }
        })
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_AudioUtils_anyToPcmNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    input_path: jstring,
    pcm_path: jstring,
) -> jint {
    with_jstring(env, input_path, |input| {
        with_jstring(env, pcm_path, |pcm| {
            match audio_utils::any_to_pcm_file(input, pcm) {
                Ok(sample_rate) => sample_rate.min(jint::MAX as u32) as jint,
                Err(err) => {
                    loge!("any_to_pcm failed: {:?}", err);
                    -1
                }
            }
        })
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_AudioUtils_getFileTypeNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    path: jstring,
) -> jint {
    with_jstring(env, path, |path| {
        audio_utils::detect_file_type(path).unwrap_or(0)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_AudioUtils_getDurationMsNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    path: jstring,
) -> jlong {
    with_jstring(env, path, |path| {
        audio_utils::get_audio_duration_ms(path).unwrap_or_else(|err| {
            loge!("get_audio_duration_ms failed: {:?}", err);
            0
        })
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_TelegramStickerConverter_tgsToGifNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    input_path: jstring,
    output_path: jstring,
) -> jstring {
    let result = with_jstring(env, input_path, |input| {
        with_jstring(env, output_path, |output| {
            telegram_sticker::tgs_to_gif(input, output)
        })
    });
    native_error_string(env, result)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_utils_TelegramStickerConverter_webmToGifNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    input_path: jstring,
    output_path: jstring,
    remove_rounded_canvas_mask: jboolean,
) -> jstring {
    let result = with_jstring(env, input_path, |input| {
        with_jstring(env, output_path, |output| {
            telegram_sticker::webm_to_gif(input, output, remove_rounded_canvas_mask != JNI_FALSE)
        })
    });
    native_error_string(env, result)
}

#[unsafe(no_mangle)]
pub extern "C" fn JNI_OnLoad(_vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    JNI_VERSION_1_6
}
