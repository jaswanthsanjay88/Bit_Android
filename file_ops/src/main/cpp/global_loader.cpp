#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#define LOG_TAG "TTSManager"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_bit_tts_TTSManager_loadLibraryGlobal(JNIEnv* env, jobject /* this */, jstring libPath) {
    const char* path = env->GetStringUTFChars(libPath, nullptr);
    if (!path) return JNI_FALSE;

    dlerror();
    void* handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        const char* err = dlerror();
        LOGE("dlopen(RTLD_GLOBAL) failed for %s: %s", path, err ? err : "unknown error");
    } else {
        LOGI("dlopen(RTLD_GLOBAL) succeeded for %s", path);
    }

    env->ReleaseStringUTFChars(libPath, path);
    return handle != nullptr ? JNI_TRUE : JNI_FALSE;
}

}
