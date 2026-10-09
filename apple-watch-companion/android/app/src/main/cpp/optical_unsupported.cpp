#include <jni.h>

namespace {
jboolean initialize(JNIEnv*, jobject, jbyteArray, jint, jint) { return JNI_FALSE; }
jbyteArray process(JNIEnv* env, jobject, jbyteArray) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Optical reader requires arm64 Android");
    return nullptr;
}
void destroy(JNIEnv*, jobject) {}
}
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass type = env->FindClass("dev/applewatchandroid/companion/apple_watch_companion/OpticalNativeReader");
    if (!type) return JNI_ERR;
    JNINativeMethod methods[] = {
        {const_cast<char*>("initialize"), const_cast<char*>("([BII)Z"), reinterpret_cast<void*>(initialize)},
        {const_cast<char*>("process"), const_cast<char*>("([B)[B"), reinterpret_cast<void*>(process)},
        {const_cast<char*>("destroy"), const_cast<char*>("()V"), reinterpret_cast<void*>(destroy)}
    };
    return env->RegisterNatives(type, methods, 3) == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
