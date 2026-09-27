/*
 * Fallback implementation used when byedpi sources have not been fetched.
 * It keeps the app linkable so that the root (nfqws) engine still works.
 */

#include <jni.h>

JNIEXPORT jint JNICALL
Java_ru_dragonfly_zapret_core_ByeDpiNative_nativeStart(JNIEnv *env, jobject thiz, jobjectArray jargs)
{
    (void) env; (void) thiz; (void) jargs;
    return -100;
}

JNIEXPORT jint JNICALL
Java_ru_dragonfly_zapret_core_ByeDpiNative_nativeStop(JNIEnv *env, jobject thiz)
{
    (void) env; (void) thiz;
    return -100;
}

JNIEXPORT jboolean JNICALL
Java_ru_dragonfly_zapret_core_ByeDpiNative_nativeIsRunning(JNIEnv *env, jobject thiz)
{
    (void) env; (void) thiz;
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ru_dragonfly_zapret_core_ByeDpiNative_nativeIsAvailable(JNIEnv *env, jobject thiz)
{
    (void) env; (void) thiz;
    return JNI_FALSE;
}
