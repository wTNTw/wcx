/*
 * WCX Zygisk 注入器 —— 日志宏
 *
 * 统一 tag 便于真机抓日志：
 *   adb logcat -s WCXZygisk
 */

#pragma once

#include <android/log.h>

#define WCX_LOG_TAG "WCXZygisk"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, WCX_LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, WCX_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, WCX_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, WCX_LOG_TAG, __VA_ARGS__)
