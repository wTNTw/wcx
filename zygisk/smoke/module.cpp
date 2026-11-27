// Public Zygisk API probe for a WCX-owned test APK. No hooks or payload loading.
#include <android/log.h>
#include "zygisk.hpp"

namespace {
constexpr char kTestProcess[] = "dev.wcx.zygisk.probe";
constexpr char kTag[] = "WcxZygiskNativeProbe";

class SmokeProbe final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        jstring name = args->nice_name;
        constexpr jsize length = sizeof(kTestProcess) - 1;
        if (name != nullptr && env_->GetStringLength(name) == length) {
            jchar chars[length];
            env_->GetStringRegion(name, 0, length, chars);
            target_ = true;
            for (jsize i = 0; i < length; ++i) {
                if (chars[i] != kTestProcess[i]) {
                    target_ = false;
                    break;
                }
            }
        }
        api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs*) override {
        if (target_) {
            __android_log_write(ANDROID_LOG_INFO, kTag, "probe_post_app_specialize");
        }
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs*) override {
        api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    bool target_ = false;
};
} // namespace

REGISTER_ZYGISK_MODULE(SmokeProbe)
