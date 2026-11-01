/*
 * WCX Zygisk 注入器 —— ART hook 桥
 *
 * 职责（全部在微信进程内执行）：
 *   * 用 LSPlant（动态 dlopen，避免 DT_NEEDED 依赖）把 Java 方法替换成通用分派方法
 *   * 用 Dobby 提供 inline hook（LSPlant 要求宿主注入）
 *   * 用 ElfImg 解析 libart.so 内存符号（LSPlant 要求宿主注入符号解析器）
 *   * 以「通用 hooker 类 + Object[] 回调」的方式对外暴露，Kotlin 侧只需一个
 *     NativeHooker 类即可承载任意签名的方法，不必按签名生成 N 个 hooker。
 *   * 负责 payload 原生库（libdexkit.so / libwekit_native.so）在 zygote 阶段的预加载
 *     与后续 RegisterNatives 绑定（不装 APK 时 System.loadLibrary 找不到它们）。
 */

#pragma once

#include <jni.h>
#include <stdint.h>

namespace wcx {

/// 初始化 LSPlant（可重复调用，只有第一次生效）。调用前必须有可用 JNIEnv。
bool HookInit(JNIEnv *env);

/// 把 target 替换为 hookerClass 实例：要求 hookerClass 有
///   ctor(java.lang.reflect.Executable)
///   public Object callback(Object[])
/// 返回 backup 方法（反射 Method 对象，可直接 invoke 调用原方法），失败返回 nullptr。
jobject HookMethod(JNIEnv *env, jobject target, jclass hookerClass);

/// 取消 hook，成功后备份方法不可再使用。
bool UnhookMethod(JNIEnv *env, jobject target);

/// 判断是否已被本模块 hook。
bool IsMethodHooked(JNIEnv *env, jobject target);

/// 反优化方法所在的调用者（避免内联导致 hook 不生效）。
bool DeoptimizeMethod(JNIEnv *env, jobject target);

/// 以 RTLD_GLOBAL 预加载 payload 原生库（只应在 pre-specialize / zygote 阶段调用）。
/// 返回 dlopen handle（失败为 0）。
uintptr_t PreloadLibrary(const char *path);

/// 同 [PreloadLibrary]，但优先用 fd 加载（`android_dlopen_ext(ANDROID_DLEXT_USE_LIBRARY_FD)`）。
/// sandbox 里读不到 /data/adb 的路径，但 zygote 阶段拿到的 fd 仍然有效，
/// 且 fd 路径不依赖 SELinux 的目录搜索权限，所以这是更稳的那条路。
uintptr_t PreloadLibraryFromDir(int dir_fd, const char *relative_path);

/// 预加载的原生库里按 JNI 命名规则查找符号并 RegisterNatives。
/// symbols 形如 "nativeMethod|(ILjava/lang/String;)V"。
bool BindNatives(JNIEnv *env, jclass clazz, jobjectArray symbols);

/// 当前已预加载的库数量（用于日志/诊断）。
int PreloadedLibraryCount();

}  // namespace wcx
