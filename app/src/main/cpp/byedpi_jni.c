/*
 * JNI bridge between ZapretAndroid and byedpi (https://github.com/hufrea/byedpi, MIT).
 *
 * byedpi is a normal CLI program: main() parses argv into the global `params`
 * structure and then blocks inside the event loop until the listening socket is
 * shut down. We keep a pristine copy of `params` taken before the very first
 * run and restore it on every start, so repeated start/stop cycles inside a
 * long living Android process behave like fresh process launches.
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <getopt.h>
#include <sys/socket.h>
#include <android/log.h>

#include "params.h"

#define TAG "zapret-byedpi"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* byedpi internals we rely on */
extern int server_fd;
extern int main(int argc, char **argv);
extern void clear_params(char *line, char **argv);

static struct params pristine_params;
static int pristine_saved = 0;
static volatile int engine_running = 0;

static void restore_params(void)
{
    if (!pristine_saved) {
        pristine_params = params;
        pristine_saved = 1;
        return;
    }
    clear_params(NULL, NULL);
    params = pristine_params;
}

JNIEXPORT jint JNICALL
Java_ru_dragonfly_zapret_core_ByeDpiNative_nativeStart(JNIEnv *env, jobject thiz, jobjectArray jargs)
{
    (void) thiz;

    if (engine_running) {
        LOGI("engine is already running");
        return -2;
    }

    jsize argc = (*env)->GetArrayLength(env, jargs);
    if (argc <= 0) {
        return -3;
    }

    char **argv = calloc((size_t) argc + 1, sizeof(char *));
    if (!argv) {
        return -4;
    }

    for (jsize i = 0; i < argc; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, jargs, i);
        if (!item) {
            argv[i] = strdup("");
            continue;
        }
        const char *chars = (*env)->GetStringUTFChars(env, item, NULL);
        argv[i] = chars ? strdup(chars) : strdup("");
        if (chars) {
            (*env)->ReleaseStringUTFChars(env, item, chars);
        }
        (*env)->DeleteLocalRef(env, item);
    }

    LOGI("starting byedpi with %d arguments", (int) argc);

    restore_params();
    optind = 1;
    opterr = 0;
    server_fd = -1;
    engine_running = 1;

    int rc = main((int) argc, argv);

    engine_running = 0;
    LOGI("byedpi finished with code %d", rc);

    for (jsize i = 0; i < argc; i++) {
        free(argv[i]);
    }
    free(argv);

    return rc;
}

JNIEXPORT jint JNICALL
Java_ru_dragonfly_zapret_core_ByeDpiNative_nativeStop(JNIEnv *env, jobject thiz)
{
    (void) env;
    (void) thiz;

    if (!engine_running) {
        return -1;
    }
    LOGI("shutting down byedpi listening socket %d", server_fd);
    if (server_fd >= 0) {
        shutdown(server_fd, SHUT_RDWR);
    }
    engine_running = 0;
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_ru_dragonfly_zapret_core_ByeDpiNative_nativeIsRunning(JNIEnv *env, jobject thiz)
{
    (void) env;
    (void) thiz;
    return engine_running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ru_dragonfly_zapret_core_ByeDpiNative_nativeIsAvailable(JNIEnv *env, jobject thiz)
{
    (void) env;
    (void) thiz;
    return JNI_TRUE;
}
