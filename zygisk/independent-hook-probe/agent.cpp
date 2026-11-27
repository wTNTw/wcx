// ART TI prototype for the debuggable fixture APK only. No Zygisk or private ART APIs.
#include <jni.h>
#include <jvmti.h>
#include <string.h>

namespace {
jvmtiEnv* env = nullptr;
jmethodID constructor = nullptr;
jmethodID evaluate = nullptr;
jint inputSlot = -1;

enum EventField {
    ConstructorEntries, ConstructorExits, MethodEntries, NormalExits, ExceptionalExits,
    LastReturn, ReadArgumentStatus, WriteArgumentStatus, ForceReturnCapability, InputSlot, FieldCount
};
jint results[FieldCount]{};
constexpr jint kNotAttempted = -1;

void JNICALL before(jvmtiEnv* ti, JNIEnv*, jthread thread, jmethodID method) {
    if (method == constructor) {
        ++results[ConstructorEntries];
    } else if (method == evaluate) {
        ++results[MethodEntries];
        if (inputSlot < 0 || results[MethodEntries] != 1) return;
        jint argument = 0;
        const auto read = ti->GetLocalInt(thread, 0, inputSlot, &argument);
        results[ReadArgumentStatus] = read;
        if (read == JVMTI_ERROR_NONE && argument == 3) {
            results[WriteArgumentStatus] = ti->SetLocalInt(thread, 0, inputSlot, 8);
        }
    }
}

void JNICALL after(jvmtiEnv*, JNIEnv*, jthread, jmethodID method, jboolean thrown, jvalue returned) {
    if (method == constructor) {
        ++results[ConstructorExits];
    } else if (method == evaluate) {
        if (thrown) {
            ++results[ExceptionalExits];
        } else {
            ++results[NormalExits];
            results[LastReturn] = returned.i;
        }
    }
}
} // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char*, void*) {
    return vm->GetEnv(reinterpret_cast<void**>(&env), JVMTI_VERSION_1_2);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_wcx_independenthook_probe_MainActivity_start(JNIEnv* jni, jclass, jobject ctor, jobject method) {
    if (!env) return JNI_ERR;
    constructor = jni->FromReflectedMethod(ctor);
    evaluate = jni->FromReflectedMethod(method);
    if (!constructor || !evaluate) return JNI_ERR;
    for (auto& field : results) field = 0;
    results[ReadArgumentStatus] = kNotAttempted;
    results[WriteArgumentStatus] = kNotAttempted;

    jvmtiCapabilities available{};
    auto error = env->GetPotentialCapabilities(&available);
    if (error != JVMTI_ERROR_NONE) return error;
    jvmtiCapabilities requested{};
    requested.can_generate_method_entry_events = 1;
    requested.can_generate_method_exit_events = 1;
    requested.can_access_local_variables = available.can_access_local_variables;
    error = env->AddCapabilities(&requested);
    if (error != JVMTI_ERROR_NONE) return error;
    // On ART, read the actual slot from the fixture's debug metadata; Java parameter order is not a slot number.
    inputSlot = kNotAttempted;
    if (requested.can_access_local_variables) {
        jint count = 0;
        jvmtiLocalVariableEntry* variables = nullptr;
        if (env->GetLocalVariableTable(evaluate, &count, &variables) == JVMTI_ERROR_NONE) {
            for (jint index = 0; index < count; ++index) {
                const auto& variable = variables[index];
                if (strcmp(variable.name, "input") == 0 && strcmp(variable.signature, "I") == 0) {
                    inputSlot = variable.slot;
                }
                env->Deallocate(reinterpret_cast<unsigned char*>(variable.name));
                env->Deallocate(reinterpret_cast<unsigned char*>(variable.signature));
                if (variable.generic_signature) {
                    env->Deallocate(reinterpret_cast<unsigned char*>(variable.generic_signature));
                }
            }
            env->Deallocate(reinterpret_cast<unsigned char*>(variables));
        }
    }
    results[InputSlot] = inputSlot;
    results[ForceReturnCapability] = available.can_force_early_return;

    jvmtiEventCallbacks callbacks{};
    callbacks.MethodEntry = before;
    callbacks.MethodExit = after;
    error = env->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (error != JVMTI_ERROR_NONE) return error;

    jthread current = nullptr;
    error = env->GetCurrentThread(&current);
    if (error != JVMTI_ERROR_NONE) return error;
    if (!current) return JNI_ERR;
    error = env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_ENTRY, current);
    if (error != JVMTI_ERROR_NONE) return error;
    error = env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_EXIT, current);
    if (error != JVMTI_ERROR_NONE) {
        env->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_ENTRY, current);
    }
    return error;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_wcx_independenthook_probe_MainActivity_snapshot(JNIEnv* jni, jclass) {
    auto result = jni->NewIntArray(FieldCount);
    if (result) jni->SetIntArrayRegion(result, 0, FieldCount, results);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_wcx_independenthook_probe_MainActivity_stop(JNIEnv*, jclass) {
    if (!env) return JNI_ERR;
    jthread current = nullptr;
    auto error = env->GetCurrentThread(&current);
    if (error != JVMTI_ERROR_NONE) return error;
    if (!current) return JNI_ERR;
    const auto entry = env->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_ENTRY, current);
    const auto exit = env->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, current);
    return entry != JVMTI_ERROR_NONE ? entry : exit;
}
