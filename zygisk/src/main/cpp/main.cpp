// WCX Zygisk 注入器
//
// 目标：不安装 WCX 的 APK，也能把模块代码注入微信进程并跑起来。
//   * 只在 com.tencent.mm 主进程生效，其它进程 / system_server 立刻 DLCLOSE
//   * payload（classes*.dex + lib/*.so）随模块 zip 下发到 /data/adb/modules/wcx-zygisk
//   * pre-specialize 阶段仍是 zygote 特权：可以直读模块目录，因此 dex 与 .so 都在这里取好
//   * post-specialize 阶段已在 sandbox 内：用 InMemoryDexClassLoader 从内存建 ClassLoader，
//     再把控制权交给 payload 里的 ZygiskInjectEntry

#include <dirent.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <string>
#include <vector>

#include "hook_bridge.hpp"
#include "logging.hpp"
#include "zygisk.hpp"

namespace {

constexpr const char *kTargetProcess = "com.tencent.mm";
constexpr size_t kMaxDexSize = 64u * 1024u * 1024u;

struct DexBlob {
    uint8_t *data = nullptr;
    size_t len = 0;
    std::string name;
};

zygisk::Api *g_api = nullptr;
JNIEnv *g_env = nullptr;
bool g_is_target = false;

std::string g_module_dir;
std::string g_app_data_dir;
std::string g_process_name;
std::string g_abi_dir;

std::vector<DexBlob> g_dex_blobs;

// ── 基础 IO ─────────────────────────────────────────────────────────────────

bool ReadAll(int fd, void *buf, size_t len) {
    size_t done = 0;
    auto *p = static_cast<uint8_t *>(buf);
    while (done < len) {
        ssize_t n = read(fd, p + done, len - done);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (n == 0) break;
        done += static_cast<size_t>(n);
    }
    return done == len;
}

DexBlob ReadDexAt(int dirfd, const char *name) {
    DexBlob blob;
    blob.name = name;
    int fd = openat(dirfd, name, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return blob;

    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0 ||
        static_cast<size_t>(st.st_size) > kMaxDexSize) {
        LOGE("dex 尺寸非法: %s size=%lld", name, static_cast<long long>(st.st_size));
        close(fd);
        return blob;
    }

    auto *buf = static_cast<uint8_t *>(malloc(static_cast<size_t>(st.st_size)));
    if (buf == nullptr) {
        close(fd);
        return blob;
    }
    if (!ReadAll(fd, buf, static_cast<size_t>(st.st_size))) {
        LOGE("dex 读取失败: %s", name);
        free(buf);
        close(fd);
        return blob;
    }
    close(fd);

    blob.data = buf;
    blob.len = static_cast<size_t>(st.st_size);
    return blob;
}

// classes.dex / classes2.dex / ... 按序号排序
int DexIndex(const std::string &name) {
    constexpr const char *kPrefix = "classes";
    constexpr const char *kSuffix = ".dex";
    if (name.rfind(kPrefix, 0) != 0) return -1;
    if (name.size() <= strlen(kPrefix) + strlen(kSuffix)) return -1;
    if (name.compare(name.size() - strlen(kSuffix), strlen(kSuffix), kSuffix) != 0) return -1;
    std::string digits =
            name.substr(strlen(kPrefix), name.size() - strlen(kPrefix) - strlen(kSuffix));
    if (digits.empty()) return 1;
    for (char c : digits) {
        if (c < '0' || c > '9') return -1;
    }
    return atoi(digits.c_str());
}

void ResolveModuleDir(int dir_fd) {
    char link[64];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", dir_fd);
    char buf[512];
    ssize_t n = readlink(link, buf, sizeof(buf) - 1);
    if (n <= 0) return;
    buf[n] = '\0';
    g_module_dir = buf;
}

/// 从 dir_fd 里收集 classes*.dex 并按序号读取到内存
void LoadDexFromDir(int dir_fd) {
    std::vector<std::pair<int, std::string>> entries;
    int scan_fd = dup(dir_fd);
    if (scan_fd >= 0) {
        DIR *dir = fdopendir(scan_fd);
        if (dir != nullptr) {
            struct dirent *entry;
            while ((entry = readdir(dir)) != nullptr) {
                struct stat st {};
                if (fstatat(dir_fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) != 0) continue;
                if (!S_ISREG(st.st_mode)) continue;
                int idx = DexIndex(entry->d_name);
                if (idx < 0) continue;
                entries.emplace_back(idx, entry->d_name);
            }
            closedir(dir);
        } else {
            close(scan_fd);
        }
    }

    std::sort(entries.begin(), entries.end(),
              [](const auto &a, const auto &b) { return a.first < b.first; });

    for (const auto &[idx, name] : entries) {
        DexBlob blob = ReadDexAt(dir_fd, name.c_str());
        if (blob.data == nullptr) continue;
        LOGI("payload dex 就绪: %s (%zu bytes)", name.c_str(), blob.len);
        g_dex_blobs.push_back(blob);
    }
}

/// payload 的两种布局都支持：
///   1) 独立模块 zip：<模块目录>/payload/classes*.dex
///   2) APK 即模块包：<模块目录>/classes*.dex（APK 自身的 dex 直接落到模块根目录）
void LoadPayloadDex(int module_fd) {
    int payload_fd = openat(module_fd, "payload", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (payload_fd >= 0) {
        LoadDexFromDir(payload_fd);
        close(payload_fd);
    }
    if (!g_dex_blobs.empty()) return;

    LOGI("模块目录内没有 payload/，改从模块根目录读取 classes*.dex（APK 一体包布局）");
    LoadDexFromDir(module_fd);
}

void PreloadPayloadLibs(int module_fd) {
    int lib_fd = openat(module_fd, "lib", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (lib_fd < 0) {
        LOGI("模块目录内没有 lib/，跳过原生库预加载");
        return;
    }

    std::vector<std::string> abi_dirs;
    int scan_fd = dup(lib_fd);
    if (scan_fd >= 0) {
        DIR *dir = fdopendir(scan_fd);
        if (dir != nullptr) {
            struct dirent *entry;
            while ((entry = readdir(dir)) != nullptr) {
                if (entry->d_name[0] == '.') continue;
                struct stat st {};
                if (fstatat(lib_fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) != 0) continue;
                if (S_ISDIR(st.st_mode)) abi_dirs.emplace_back(entry->d_name);
            }
            closedir(dir);
        } else {
            close(scan_fd);
        }
    }
    std::sort(abi_dirs.begin(), abi_dirs.end());
    if (abi_dirs.empty()) {
        close(lib_fd);
        return;
    }
    g_abi_dir = abi_dirs.front();
    LOGI("payload 原生库目录: lib/%s", g_abi_dir.c_str());

    std::vector<std::string> libs;
    int abi_fd = openat(lib_fd, g_abi_dir.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (abi_fd >= 0) {
        int list_fd = dup(abi_fd);
        if (list_fd >= 0) {
            DIR *dir = fdopendir(list_fd);
            if (dir != nullptr) {
                struct dirent *entry;
                while ((entry = readdir(dir)) != nullptr) {
                    std::string name = entry->d_name;
                    if (name.size() < 4 || name.compare(name.size() - 3, 3, ".so") != 0) {
                        continue;
                    }
                    struct stat st {};
                    if (fstatat(abi_fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) != 0) continue;
                    if (!S_ISREG(st.st_mode)) continue;
                    libs.push_back(name);
                }
                closedir(dir);
            } else {
                close(list_fd);
            }
        }
        close(abi_fd);
    }
    close(lib_fd);

    // libc++_shared 之类的基础库要先加载
    std::sort(libs.begin(), libs.end(), [](const std::string &a, const std::string &b) {
        bool a_cxx = a.find("c++") != std::string::npos;
        bool b_cxx = b.find("c++") != std::string::npos;
        if (a_cxx != b_cxx) return a_cxx;
        return a < b;
    });

    if (libs.empty()) return;

    // 用 openat 拿 fd 再加载：不依赖 /data/adb 的路径搜索权限
    int abi_dir_fd = openat(module_fd, ("lib/" + g_abi_dir).c_str(),
                            O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (abi_dir_fd < 0) {
        LOGE("打开 lib/%s 失败: %s", g_abi_dir.c_str(), strerror(errno));
        return;
    }
    for (const auto &name : libs) {
        wcx::PreloadLibraryFromDir(abi_dir_fd, name.c_str());
    }
    close(abi_dir_fd);
}

// ── post-specialize：建 ClassLoader 并交给 payload ───────────────────────────

jobjectArray BuildDexBuffers(JNIEnv *env) {
    jclass byte_buffer_cls = env->FindClass("java/nio/ByteBuffer");
    if (byte_buffer_cls == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }

    auto array = env->NewObjectArray(static_cast<jsize>(g_dex_blobs.size()), byte_buffer_cls,
                                     nullptr);
    for (size_t i = 0; i < g_dex_blobs.size(); ++i) {
        jobject buffer = env->NewDirectByteBuffer(g_dex_blobs[i].data,
                                                  static_cast<jlong>(g_dex_blobs[i].len));
        if (buffer == nullptr) {
            env->ExceptionClear();
            return nullptr;
        }
        env->SetObjectArrayElement(array, static_cast<jsize>(i), buffer);
        env->DeleteLocalRef(buffer);
    }
    env->DeleteLocalRef(byte_buffer_cls);
    return array;
}

jobject CreateModuleClassLoader(JNIEnv *env) {
    jobjectArray buffers = BuildDexBuffers(env);
    if (buffers == nullptr) return nullptr;

    jclass loader_cls = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (loader_cls == nullptr) {
        env->ExceptionClear();
        LOGE("找不到 InMemoryDexClassLoader");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(loader_cls, "<init>",
                                     "([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (ctor == nullptr) {
        env->ExceptionClear();
        LOGE("InMemoryDexClassLoader 构造器缺失");
        return nullptr;
    }

    jobject loader = env->NewObject(loader_cls, ctor, buffers, nullptr);
    if (loader == nullptr || env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return nullptr;
    }
    env->DeleteLocalRef(buffers);
    env->DeleteLocalRef(loader_cls);
    return loader;
}

// 本模块自己的 JNI 方法表：payload 的 .so 是手工 dlopen 的，
// ART 不会替我们做符号解析，必须显式 RegisterNatives。
static const JNINativeMethod kHookBridgeMethods[] = {
        {const_cast<char *>("nativeInit"), const_cast<char *>("()Z"),
         reinterpret_cast<void *>(+[](JNIEnv *env, jclass) -> jboolean {
             return wcx::HookInit(env) ? JNI_TRUE : JNI_FALSE;
         })},
        {const_cast<char *>("nativeHook"),
         const_cast<char *>("(Ljava/lang/reflect/Executable;Ljava/lang/Class;)"
                            "Ljava/lang/reflect/Method;"),
         reinterpret_cast<void *>(+[](JNIEnv *env, jclass, jobject target,
                                      jclass hooker) -> jobject {
             return wcx::HookMethod(env, target, hooker);
         })},
        {const_cast<char *>("nativeUnhook"),
         const_cast<char *>("(Ljava/lang/reflect/Executable;)Z"),
         reinterpret_cast<void *>(+[](JNIEnv *env, jclass, jobject target) -> jboolean {
             return wcx::UnhookMethod(env, target) ? JNI_TRUE : JNI_FALSE;
         })},
        {const_cast<char *>("nativeIsHooked"),
         const_cast<char *>("(Ljava/lang/reflect/Executable;)Z"),
         reinterpret_cast<void *>(+[](JNIEnv *env, jclass, jobject target) -> jboolean {
             return wcx::IsMethodHooked(env, target) ? JNI_TRUE : JNI_FALSE;
         })},
        {const_cast<char *>("nativeDeoptimize"),
         const_cast<char *>("(Ljava/lang/reflect/Executable;)Z"),
         reinterpret_cast<void *>(+[](JNIEnv *env, jclass, jobject target) -> jboolean {
             return wcx::DeoptimizeMethod(env, target) ? JNI_TRUE : JNI_FALSE;
         })},
        {const_cast<char *>("nativeBindNatives"),
         const_cast<char *>("(Ljava/lang/Class;[Ljava/lang/String;)Z"),
         reinterpret_cast<void *>(+[](JNIEnv *env, jclass, jclass clazz,
                                      jobjectArray symbols) -> jboolean {
             return wcx::BindNatives(env, clazz, symbols) ? JNI_TRUE : JNI_FALSE;
         })},
};

bool RegisterHookBridge(JNIEnv *env, jobject module_loader) {
    jclass loader_cls = env->FindClass("java/lang/ClassLoader");
    jmethodID load_class = env->GetMethodID(loader_cls, "loadClass",
                                           "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring name = env->NewStringUTF("com.Johnny.wcx.loader.entry.zygisk.NativeHookBridge");

    auto cls = reinterpret_cast<jclass>(env->CallObjectMethod(module_loader, load_class, name));
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(loader_cls);
    if (env->ExceptionCheck() || cls == nullptr) {
        env->ExceptionClear();
        LOGE("payload 内缺少 NativeHookBridge");
        return false;
    }

    jint rc = env->RegisterNatives(cls, kHookBridgeMethods,
                                   sizeof(kHookBridgeMethods) / sizeof(kHookBridgeMethods[0]));
    env->DeleteLocalRef(cls);
    if (rc != JNI_OK || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("RegisterNatives(NativeHookBridge) 失败 rc=%d", rc);
        return false;
    }
    return true;
}

void CallPayloadEntry(JNIEnv *env, jobject module_loader) {
    jclass loader_cls = env->FindClass("java/lang/ClassLoader");
    jmethodID load_class = env->GetMethodID(loader_cls, "loadClass",
                                           "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring name = env->NewStringUTF("com.Johnny.wcx.loader.entry.zygisk.ZygiskInjectEntry");

    auto entry_cls =
            reinterpret_cast<jclass>(env->CallObjectMethod(module_loader, load_class, name));
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(loader_cls);
    if (entry_cls == nullptr || env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("payload 内缺少 ZygiskInjectEntry");
        return;
    }

    jmethodID entry = env->GetStaticMethodID(
            entry_cls, "entry",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (entry == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("ZygiskInjectEntry.entry 签名不匹配");
        env->DeleteLocalRef(entry_cls);
        return;
    }

    jstring app_data = env->NewStringUTF(g_app_data_dir.c_str());
    jstring module_dir = env->NewStringUTF(g_module_dir.c_str());
    jstring process = env->NewStringUTF(g_process_name.c_str());

    env->CallStaticVoidMethod(entry_cls, entry, app_data, module_dir, process, module_loader);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("ZygiskInjectEntry.entry 抛出异常");
    }

    env->DeleteLocalRef(app_data);
    env->DeleteLocalRef(module_dir);
    env->DeleteLocalRef(process);
    env->DeleteLocalRef(entry_cls);
}

// ── Zygisk 模块本体 ─────────────────────────────────────────────────────────

class WcxZygiskModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        g_api = api;
        g_env = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        const char *process =
                args->nice_name ? g_env->GetStringUTFChars(args->nice_name, nullptr) : nullptr;
        g_process_name = process != nullptr ? process : "";
        if (process != nullptr) g_env->ReleaseStringUTFChars(args->nice_name, process);

        g_is_target = g_process_name == kTargetProcess;

        if (!g_is_target) {
            g_api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        if (args->app_data_dir != nullptr) {
            const char *data_dir = g_env->GetStringUTFChars(args->app_data_dir, nullptr);
            if (data_dir != nullptr) {
                g_app_data_dir = data_dir;
                g_env->ReleaseStringUTFChars(args->app_data_dir, data_dir);
            }
        }
        if (args->is_child_zygote != nullptr && *args->is_child_zygote) {
            LOGI("child zygote，跳过注入");
            g_is_target = false;
            g_api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (g_app_data_dir.empty()) {
            LOGW("拿不到 app_data_dir，放弃注入（多半是 child zygote）");
            g_is_target = false;
            g_api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        int module_fd = g_api->getModuleDir();
        if (module_fd < 0) {
            LOGE("getModuleDir() 失败");
            g_is_target = false;
            g_api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        ResolveModuleDir(module_fd);
        LOGI("注入准备 pid=%d uid=%d process=%s module=%s data=%s", getpid(), getuid(),
             g_process_name.c_str(), g_module_dir.c_str(), g_app_data_dir.c_str());

        // zygote 特权限下把 payload 全部取好，sandbox 内不再碰 /data/adb
        LoadPayloadDex(module_fd);

        // 先加载 LSPlant（hook 桥的运行时依赖），再加载 payload 自己的库
        if (wcx::PreloadLibraryFromDir(module_fd, "zygisk/liblsplant.so") == 0) {
            LOGE("liblsplant.so 加载失败，无法 hook");
        }
        PreloadPayloadLibs(module_fd);

        close(module_fd);

        if (g_dex_blobs.empty()) {
            LOGE("payload 为空，放弃注入");
            g_is_target = false;
            g_api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!g_is_target) return;

        LOGI("post-specialize pid=%d uid=%d dex=%zu libs=%d", getpid(), getuid(),
             g_dex_blobs.size(), wcx::PreloadedLibraryCount());

        jobject module_loader = CreateModuleClassLoader(g_env);
        if (module_loader == nullptr) {
            LOGE("模块 ClassLoader 创建失败");
            return;
        }

        if (!RegisterHookBridge(g_env, module_loader)) {
            LOGE("hook 桥注册失败，放弃注入");
            return;
        }

        CallPayloadEntry(g_env, module_loader);
        LOGI("注入流程结束");
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override {
        g_api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }
};

}  // namespace

// Zygisk 用 dlsym() 在 .dynsym 里找这个符号，所以必须显式 default 可见性
// （本模块编译时全局 -fvisibility=hidden，直接写 REGISTER_ZYGISK_MODULE 会把入口藏起来）。
extern "C" __attribute__((visibility("default"))) void zygisk_module_entry(
        zygisk::internal::api_table *table, JNIEnv *env) {
    zygisk::internal::entry_impl<WcxZygiskModule>(table, env);
}
