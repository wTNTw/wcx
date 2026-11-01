/*
 * WCX Zygisk 注入器 —— ART hook 桥实现
 *
 * 为什么用 dlsym 而不是直接链接 LSPlant：
 *   Zygisk 通过 android_dlopen_ext(fd) 加载模块 .so，运行时的库搜索路径里没有模块目录。
 *   一旦模块 .so 带 DT_NEEDED=liblsplant.so，加载就会直接失败；即使把 soname 挪到
 *   运行时查找，也会因为 BIND_NOW/惰性绑定策略差异在部分 ROM 上炸。
 *   所以这里让模块 .so 对 LSPlant **零链接期依赖**：
 *   注入器在 zygote 阶段 dlopen("<模块目录>/zygisk/liblsplant.so", RTLD_GLOBAL)，
 *   随后用 dlsym 拿 5 个入口函数指针调用。
 *
 * LSPlant 的 Itanium ABI 符号名可以用下面这条命令核对（版本升级时务必复查）：
 *   nm -D --defined-only liblsplant.so | grep _ZN7lsplant2v2
 */

#include "hook_bridge.hpp"

#include <android/dlext.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "dobby.h"
#include "elf_util.hpp"
#include "logging.hpp"
#include "lsplant.hpp"

namespace wcx {
namespace {

using LsplantInit = bool (*)(JNIEnv *, const lsplant::InitInfo &);
using LsplantHook = jobject (*)(JNIEnv *, jobject, jobject, jobject);
using LsplantUnhook = bool (*)(JNIEnv *, jobject);
using LsplantIsHooked = bool (*)(JNIEnv *, jobject);
using LsplantDeoptimize = bool (*)(JNIEnv *, jobject);

// LSPlant 6.4（org.lsposed.lsplant:lsplant-standalone:6.4）导出的符号
constexpr const char *kSymInit = "_ZN7lsplant2v24InitEP7_JNIEnvRKNS0_8InitInfoE";
constexpr const char *kSymHook = "_ZN7lsplant2v24HookEP7_JNIEnvP8_jobjectS4_S4_";
constexpr const char *kSymUnhook = "_ZN7lsplant2v26UnHookEP7_JNIEnvP8_jobject";
constexpr const char *kSymIsHooked = "_ZN7lsplant2v28IsHookedEP7_JNIEnvP8_jobject";
constexpr const char *kSymDeoptimize = "_ZN7lsplant2v210DeoptimizeEP7_JNIEnvP8_jobject";

struct LsplantApi {
    LsplantInit init = nullptr;
    LsplantHook hook = nullptr;
    LsplantUnhook unhook = nullptr;
    LsplantIsHooked isHooked = nullptr;
    LsplantDeoptimize deoptimize = nullptr;

    bool complete() const {
        return init != nullptr && hook != nullptr && unhook != nullptr && isHooked != nullptr &&
               deoptimize != nullptr;
    }
};

std::atomic<bool> g_inited{false};
std::mutex g_mutex;
LsplantApi g_api;
std::vector<void *> g_libs;
SandHook::ElfImg *g_art = nullptr;

// ── Dobby inline hook（LSPlant 的 hooker 注入点）──────────────────────────────
void *InlineHooker(void *target, void *hooker) {
    void *backup = nullptr;
    if (DobbyHook(target, hooker, &backup) == RS_SUCCESS) {
        return backup;
    }
    LOGE("DobbyHook 失败: target=%p", target);
    return nullptr;
}

bool InlineUnhooker(void *func) { return DobbyDestroy(func) == RT_SUCCESS; }

// ── libart.so 符号解析 ───────────────────────────────────────────────────────
void *ArtSymbol(std::string_view name) {
    if (g_art == nullptr) return nullptr;
    return g_art->getSymbAddress(name);
}

void *ArtSymbolPrefix(std::string_view prefix) {
    if (g_art == nullptr) return nullptr;
    return g_art->getSymbPrefixFirstAddress(prefix);
}

bool LoadLsplantApi() {
    g_api.init = reinterpret_cast<LsplantInit>(dlsym(RTLD_DEFAULT, kSymInit));
    g_api.hook = reinterpret_cast<LsplantHook>(dlsym(RTLD_DEFAULT, kSymHook));
    g_api.unhook = reinterpret_cast<LsplantUnhook>(dlsym(RTLD_DEFAULT, kSymUnhook));
    g_api.isHooked = reinterpret_cast<LsplantIsHooked>(dlsym(RTLD_DEFAULT, kSymIsHooked));
    g_api.deoptimize = reinterpret_cast<LsplantDeoptimize>(dlsym(RTLD_DEFAULT, kSymDeoptimize));
    if (g_api.complete()) return true;

    LOGE("LSPlant 符号缺失 init=%p hook=%p unhook=%p isHooked=%p deopt=%p",
         reinterpret_cast<void *>(g_api.init), reinterpret_cast<void *>(g_api.hook),
         reinterpret_cast<void *>(g_api.unhook), reinterpret_cast<void *>(g_api.isHooked),
         reinterpret_cast<void *>(g_api.deoptimize));
    return false;
}

/// JNI 符号名转义：'/'->'_'，'_'->'_1'，';'->'_2'，'['->'_3'
void MangleTo(std::string &out, std::string_view in) {
    for (char c : in) {
        switch (c) {
            case '/':
                out.push_back('_');
                break;
            case '_':
                out += "_1";
                break;
            case ';':
                out += "_2";
                break;
            case '[':
                out += "_3";
                break;
            default:
                out.push_back(c);
                break;
        }
    }
}

void *FindJniSymbol(const std::string &symbol) {
    for (void *lib : g_libs) {
        if (void *p = dlsym(lib, symbol.c_str())) return p;
    }
    return nullptr;
}

}  // namespace

bool HookInit(JNIEnv *env) {
    if (g_inited.load(std::memory_order_acquire)) return true;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_inited.load(std::memory_order_relaxed)) return true;

    if (!g_api.complete() && !LoadLsplantApi()) {
        LOGE("liblsplant.so 未预加载，hook 不可用");
        return false;
    }

    if (g_art == nullptr) {
        g_art = new SandHook::ElfImg("libart.so");
        if (!g_art->isValid()) {
            LOGE("libart.so 符号解析失败，LSPlant 无法初始化");
            return false;
        }
    }

    lsplant::InitInfo info{
            .inline_hooker = InlineHooker,
            .inline_unhooker = InlineUnhooker,
            .art_symbol_resolver = ArtSymbol,
            .art_symbol_prefix_resolver = ArtSymbolPrefix,
            .generated_class_name = "WcxHooker_",
            .generated_source_name = "Wcx",
            .generated_field_name = "hooker",
            .generated_method_name = "{target}",
    };

    if (!g_api.init(env, info)) {
        LOGE("lsplant::Init 失败（ART 版本不兼容或符号缺失）");
        return false;
    }

    g_inited.store(true, std::memory_order_release);
    LOGI("LSPlant 初始化完成");
    return true;
}

jobject HookMethod(JNIEnv *env, jobject target, jclass hookerClass) {
    if (target == nullptr || hookerClass == nullptr) return nullptr;
    if (!HookInit(env)) return nullptr;

    jmethodID ctor = env->GetMethodID(hookerClass, "<init>",
                                     "(Ljava/lang/reflect/Executable;)V");
    if (ctor == nullptr) {
        env->ExceptionClear();
        LOGE("hooker 类缺少 <init>(Executable)");
        return nullptr;
    }
    jmethodID dispatch =
            env->GetMethodID(hookerClass, "callback", "([Ljava/lang/Object;)Ljava/lang/Object;");
    if (dispatch == nullptr) {
        env->ExceptionClear();
        LOGE("hooker 类缺少 Object callback(Object[])");
        return nullptr;
    }

    jobject hookerObject = env->NewObject(hookerClass, ctor, target);
    if (hookerObject == nullptr || env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("hooker 实例创建失败");
        return nullptr;
    }

    jobject callbackMethod = env->ToReflectedMethod(hookerClass, dispatch, JNI_FALSE);
    if (callbackMethod == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(hookerObject);
        LOGE("分派方法反射失败");
        return nullptr;
    }

    jobject backup = g_api.hook(env, target, hookerObject, callbackMethod);
    env->DeleteLocalRef(callbackMethod);
    env->DeleteLocalRef(hookerObject);
    if (backup == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("lsplant::Hook 失败");
        return nullptr;
    }
    return backup;
}

bool UnhookMethod(JNIEnv *env, jobject target) {
    if (target == nullptr || g_api.unhook == nullptr) return false;
    return g_api.unhook(env, target);
}

bool IsMethodHooked(JNIEnv *env, jobject target) {
    if (target == nullptr || g_api.isHooked == nullptr) return false;
    return g_api.isHooked(env, target);
}

bool DeoptimizeMethod(JNIEnv *env, jobject target) {
    if (target == nullptr || g_api.deoptimize == nullptr) return false;
    return g_api.deoptimize(env, target);
}

uintptr_t PreloadLibrary(const char *path) {
    if (path == nullptr) return 0;
    // RTLD_GLOBAL：让 LSPlant 的符号进入全局作用域（dlsym(RTLD_DEFAULT) 能取到），
    // 同时让 payload 原生库内部的相互依赖可见
    void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        LOGE("预加载失败 %s: %s", path, dlerror());
        return 0;
    }
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_libs.push_back(handle);
    }
    LOGI("预加载成功 %s handle=%p", path, handle);
    return reinterpret_cast<uintptr_t>(handle);
}

uintptr_t PreloadLibraryFromDir(int dir_fd, const char *relative_path) {
    if (dir_fd < 0 || relative_path == nullptr) return 0;

    int fd = openat(dir_fd, relative_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("openat(%s) 失败: %s", relative_path, strerror(errno));
        return 0;
    }

    // 先试 fd 加载：不依赖目录搜索权限（sandbox / 加固 ROM 更友好）
    char fake_name[64];
    snprintf(fake_name, sizeof(fake_name), "/proc/self/fd/%d", fd);

    android_dlextinfo info{};
    info.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
    info.library_fd = fd;

    void *handle = android_dlopen_ext(fake_name, RTLD_NOW | RTLD_GLOBAL, &info);
    if (handle == nullptr) {
        LOGW("fd 加载失败 %s: %s（回退到路径加载）", relative_path, dlerror());
        handle = dlopen(fake_name, RTLD_NOW | RTLD_GLOBAL);
    }
    if (handle == nullptr) {
        LOGE("预加载失败 %s: %s", relative_path, dlerror());
        close(fd);
        return 0;
    }

    // fd 保持打开：与 Magisk 加载模块库的行为一致，避免后续按 fd 重新解析时失效
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_libs.push_back(handle);
    }
    LOGI("预加载成功 %s handle=%p fd=%d", relative_path, handle, fd);
    return reinterpret_cast<uintptr_t>(handle);
}

int PreloadedLibraryCount() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<int>(g_libs.size());
}

bool BindNatives(JNIEnv *env, jclass clazz, jobjectArray symbols) {
    if (clazz == nullptr || symbols == nullptr) return false;

    const jsize count = env->GetArrayLength(symbols);
    if (count <= 0) return true;

    std::string className;
    {
        jclass clsClazz = env->FindClass("java/lang/Class");
        jmethodID getName = env->GetMethodID(clsClazz, "getName", "()Ljava/lang/String;");
        auto name = static_cast<jstring>(env->CallObjectMethod(clazz, getName));
        const char *utf = env->GetStringUTFChars(name, nullptr);
        className = utf;
        env->ReleaseStringUTFChars(name, utf);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(clsClazz);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
    }

    std::string base = "Java_";
    MangleTo(base, className);
    base.push_back('_');

    struct Pending {
        std::string name;
        std::string sig;
        void *fn;
    };
    std::vector<Pending> pending;
    pending.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; ++i) {
        // 每项形如 "nativeXxx|(ILjava/lang/String;)V"
        auto item = static_cast<jstring>(env->GetObjectArrayElement(symbols, i));
        const char *utf = env->GetStringUTFChars(item, nullptr);
        std::string entry = utf;
        env->ReleaseStringUTFChars(item, utf);
        env->DeleteLocalRef(item);

        auto sep = entry.find('|');
        if (sep == std::string::npos) continue;
        std::string name = entry.substr(0, sep);
        std::string sig = entry.substr(sep + 1);

        std::string symbol = base;
        MangleTo(symbol, name);

        void *fn = FindJniSymbol(symbol);
        if (fn == nullptr) {
            // 有重载时 NDK 会生成带签名后缀的符号（"__" 后接转义签名）
            std::string withSig = symbol + "__";
            MangleTo(withSig, sig);
            fn = FindJniSymbol(withSig);
        }
        if (fn == nullptr) {
            LOGE("找不到 JNI 符号 %s（类 %s）", symbol.c_str(), className.c_str());
            continue;
        }
        pending.push_back(Pending{std::move(name), std::move(sig), fn});
    }

    if (pending.empty()) return false;

    std::vector<JNINativeMethod> methods;
    methods.reserve(pending.size());
    for (auto &p : pending) {
        methods.push_back(JNINativeMethod{p.name.data(), p.sig.data(), p.fn});
    }

    jint rc = env->RegisterNatives(clazz, methods.data(), static_cast<jint>(methods.size()));
    if (rc != JNI_OK || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("RegisterNatives 失败 class=%s rc=%d", className.c_str(), rc);
        return false;
    }
    LOGI("RegisterNatives 成功 class=%s count=%zu", className.c_str(), methods.size());
    return true;
}

}  // namespace wcx
