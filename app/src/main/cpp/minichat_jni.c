/*
 * MiniChat JNI 包装：在后台 pthread 里跑原版 minichat 的 POSIX main。
 *
 * 不改原服务逻辑，只做四件事：把 Android 可写目录通过 --dir 传进去、
 * 把宿主生成的内部口令 --token 与 6 位配对码 --pin 传进去、等 listen 完成再返回端口、
 * 把运行态暴露给 Java。有 token 时 C 侧绑 0.0.0.0 并对局域网来源校验口令；
 * 没 token 时自动退回只绑 127.0.0.1。
 */
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

extern int minichat_main(int argc, char **argv);
extern int minichat_port(void);
extern int minichat_ready(void);

typedef struct {
    char *dir;
    char *token;
    char *pin;
} start_args;

static pthread_mutex_t s_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int s_started = 0;

static char *dup_utf(JNIEnv *env, jstring s) {
    const char *p;
    char *r;
    if (!s) return strdup("");
    p = (*env)->GetStringUTFChars(env, s, NULL);
    if (!p) return strdup("");
    r = strdup(p);
    (*env)->ReleaseStringUTFChars(env, s, p);
    return r ? r : strdup("");
}

static void *run_server(void *arg) {
    start_args *sa = (start_args *)arg;
    char *argv[11];
    argv[0] = "minichat";
    argv[1] = "--dir";
    argv[2] = sa->dir;
    argv[3] = "--no-browser";
    argv[4] = "--port";
    argv[5] = "53317";
    argv[6] = "--token";
    argv[7] = sa->token;
    argv[8] = "--pin";
    argv[9] = sa->pin;
    argv[10] = NULL;
    minichat_main(10, argv);
    __android_log_print(ANDROID_LOG_WARN, "MiniChat", "service thread exited");
    free(sa->dir);
    free(sa->token);
    free(sa->pin);
    free(sa);
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_aosen_shizukudex_MiniChatBridge_nativeStart(
        JNIEnv *env, jclass cls, jstring jdir, jstring jtoken, jstring jpin) {
    (void)cls;
    pthread_mutex_lock(&s_lock);
    if (s_started) {
        int p = minichat_ready() ? minichat_port() : 0;
        pthread_mutex_unlock(&s_lock);
        return p;
    }
    start_args *sa = (start_args *)calloc(1, sizeof(start_args));
    if (!sa) {
        pthread_mutex_unlock(&s_lock);
        return -1;
    }
    sa->dir = dup_utf(env, jdir);
    sa->token = dup_utf(env, jtoken);
    sa->pin = dup_utf(env, jpin);
    pthread_t t;
    if (pthread_create(&t, NULL, run_server, sa) != 0) {
        free(sa->dir);
        free(sa->token);
        free(sa->pin);
        free(sa);
        pthread_mutex_unlock(&s_lock);
        return -1;
    }
    pthread_detach(t);
    s_started = 1;
    pthread_mutex_unlock(&s_lock);

    /* 等 bind+listen 完成，最多 2 秒；端口被占用时会向后试到 53337。 */
    int i;
    for (i = 0; i < 100 && !minichat_ready(); i++) usleep(20000);
    return minichat_ready() ? minichat_port() : -1;
}

JNIEXPORT jint JNICALL
Java_com_aosen_shizukudex_MiniChatBridge_nativePort(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return minichat_port();
}

JNIEXPORT jint JNICALL
Java_com_aosen_shizukudex_MiniChatBridge_nativeReady(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return minichat_ready();
}
