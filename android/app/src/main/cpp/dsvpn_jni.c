/* JNI glue between DsvpnVpnService (Kotlin) and the dsvpn C core.
 *
 * Ownership contract (same as the brook client): Kotlin detaches the tun
 * descriptor from the ParcelFileDescriptor and hands the raw int to
 * nativeStart(); the native side owns it from there and closes it when the
 * session ends. */

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>

#include "os.h"
#include "vpn.h"

#define LOG_TAG "dsvpn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM    *jvm;
static jobject    service;         /* global ref to the bound DsvpnVpnService  */
static jmethodID  protect_mid;     /* VpnService.protect(int)                  */
static jmethodID  on_exit_mid;     /* DsvpnVpnService.onNativeExit()           */
static pthread_t  vpn_thread;
static volatile int vpn_running;

/* Pump whatever the C core prints (printf/fprintf stderr) into logcat. */
static void *log_pump(void *arg)
{
    char   line[512];
    size_t len = 0; /* total valid bytes, always starting at line[0] */
    int    fd = (int) (intptr_t) arg;
    ssize_t n;

    while ((n = read(fd, line + len, sizeof line - len)) > 0) {
        char *p = line, *nl;

        len += (size_t) n;
        while ((nl = memchr(p, '\n', len - (size_t) (p - line))) != NULL) {
            *nl = 0;
            LOGI("%s", p);
            p = nl + 1;
        }
        if (p != line) {
            len -= (size_t) (p - line);
            memmove(line, p, len);
        }
        if (len == sizeof line) { /* overlong line without '\n': flush it */
            line[sizeof line - 1] = 0;
            LOGI("%s", line);
            len = 0;
        }
    }
    if (len > 0) {
        line[len] = 0;
        LOGI("%s", line);
    }
    close(fd);

    return NULL;
}

static void redirect_stdio_to_logcat(void)
{
    static int done;
    int        fds[2];
    pthread_t  t;

    if (done) {
        return;
    }
    done = 1;
    if (pipe(fds) != 0) {
        return;
    }
    (void) dup2(fds[1], STDOUT_FILENO);
    (void) dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    /* stdout is fully buffered once it is a pipe; keep log lines timely. */
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (pthread_create(&t, NULL, log_pump, (void *) (intptr_t) fds[0]) == 0) {
        (void) pthread_detach(t);
    }
}

/* Called by the C core for every outbound TCP socket (see vpn.c tcp_client):
 * marks the socket so its traffic bypasses the VPN interface, otherwise the
 * tunnel's own packets would be routed back into the tunnel. */
int android_protect_fd(const int fd)
{
    JNIEnv *env;
    jboolean ok;

    /* No bound service means no VPN interface exists yet: the socket cannot
     * be routed into the tunnel, so there is nothing to protect. This is the
     * normal case for the handshake-only "Test" button. */
    if (jvm == NULL || service == NULL) {
        return 0;
    }
    if ((*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }
    ok = (*env)->CallBooleanMethod(env, service, protect_mid, fd);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return -1;
    }

    return ok ? 0 : -1;
}

static void notify_exit(JNIEnv *env)
{
    vpn_running = 0;
    if (service != NULL) {
        (*env)->CallVoidMethod(env, service, on_exit_mid);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }
}

typedef struct StartArgs {
    int           tun_fd;
    unsigned char key[32];
    char          server[256];
    char          port[8];
} StartArgs;

static void *vpn_thread_main(void *arg)
{
    StartArgs *args = (StartArgs *) arg;
    JNIEnv    *env  = NULL;
    jint       attached = (*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_6) == JNI_OK;

    if (!attached && (*jvm)->AttachCurrentThread(jvm, &env, NULL) != JNI_OK) {
        env = NULL;
    }
    LOGI("VPN session starting (tun fd %d)", args->tun_fd);
    if (dsvpn_client_run(args->tun_fd, args->key, args->server, args->port) != 0) {
        LOGE("VPN session ended with an error");
    }
    if (env != NULL) {
        notify_exit(env);
        if (!attached) {
            (*jvm)->DetachCurrentThread(jvm);
        }
    }
    free(args);

    return NULL;
}

JNIEXPORT jint JNICALL
Java_io_github_dongyuwei_dsvpn_DsvpnNative_start(JNIEnv *env, jclass clazz, jint tun_fd,
                                                 jbyteArray key, jstring server, jstring port)
{
    StartArgs     *args;
    jsize          key_len;
    const char    *server_utf, *port_utf;

    (void) clazz;
    if (vpn_running) {
        LOGW("nativeStart: a session is already active");
        return -1;
    }
    if ((args = malloc(sizeof *args)) == NULL) {
        return -1;
    }
    memset(args, 0, sizeof *args);
    key_len = (*env)->GetArrayLength(env, key);
    if (key_len != (jsize) sizeof args->key) {
        LOGE("nativeStart: the key must be exactly 32 bytes (got %d)", key_len);
        free(args);
        return -1;
    }
    (*env)->GetByteArrayRegion(env, key, 0, key_len, (jbyte *) args->key);
    server_utf = (*env)->GetStringUTFChars(env, server, NULL);
    port_utf   = (*env)->GetStringUTFChars(env, port, NULL);
    if (server_utf == NULL || port_utf == NULL ||
        strlen(server_utf) >= sizeof args->server || strlen(port_utf) >= sizeof args->port) {
        LOGE("nativeStart: invalid server or port");
        if (server_utf != NULL) {
            (*env)->ReleaseStringUTFChars(env, server, server_utf);
        }
        if (port_utf != NULL) {
            (*env)->ReleaseStringUTFChars(env, port, port_utf);
        }
        free(args);
        return -1;
    }
    snprintf(args->server, sizeof args->server, "%s", server_utf);
    snprintf(args->port, sizeof args->port, "%s", port_utf);
    (*env)->ReleaseStringUTFChars(env, server, server_utf);
    (*env)->ReleaseStringUTFChars(env, port, port_utf);
    args->tun_fd = tun_fd;

    /* Set before pthread_create: the thread may fail and notify its exit
     * before the creating thread runs again. */
    vpn_running = 1;
    if (pthread_create(&vpn_thread, NULL, vpn_thread_main, args) != 0) {
        LOGE("nativeStart: unable to start the VPN thread");
        vpn_running = 0;
        free(args);
        return -1;
    }
    redirect_stdio_to_logcat();

    return 0;
}

JNIEXPORT void JNICALL
Java_io_github_dongyuwei_dsvpn_DsvpnNative_stop(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;
    if (!vpn_running) {
        return;
    }
    dsvpn_client_stop();
    /* Wake the session thread if it is blocked in poll()/sleep(). */
    (void) pthread_kill(vpn_thread, SIGTERM);
}

JNIEXPORT jint JNICALL
Java_io_github_dongyuwei_dsvpn_DsvpnNative_test(JNIEnv *env, jclass clazz, jstring server,
                                                jstring port, jbyteArray key)
{
    const char   *server_utf, *port_utf;
    unsigned char key_bytes[32];
    jsize         key_len;
    int           ret;

    (void) clazz;
    key_len = (*env)->GetArrayLength(env, key);
    if (key_len != (jsize) sizeof key_bytes) {
        return -1;
    }
    (*env)->GetByteArrayRegion(env, key, 0, key_len, (jbyte *) key_bytes);
    server_utf = (*env)->GetStringUTFChars(env, server, NULL);
    port_utf   = (*env)->GetStringUTFChars(env, port, NULL);
    if (server_utf == NULL || port_utf == NULL) {
        if (server_utf != NULL) {
            (*env)->ReleaseStringUTFChars(env, server, server_utf);
        }
        if (port_utf != NULL) {
            (*env)->ReleaseStringUTFChars(env, port, port_utf);
        }
        return -1;
    }
    ret = dsvpn_client_test(server_utf, port_utf, key_bytes);
    (*env)->ReleaseStringUTFChars(env, server, server_utf);
    (*env)->ReleaseStringUTFChars(env, port, port_utf);

    return ret;
}

JNIEXPORT void JNICALL
Java_io_github_dongyuwei_dsvpn_DsvpnNative_bindService(JNIEnv *env, jclass clazz, jobject svc)
{
    JavaVM *vm;
    jclass  cls;

    (void) clazz;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK) {
        return;
    }
    if (service != NULL) {
        (*env)->DeleteGlobalRef(env, service);
        service = NULL;
    }
    jvm = vm;
    if (svc == NULL) {
        return;
    }
    service = (*env)->NewGlobalRef(env, svc);
    cls          = (*env)->GetObjectClass(env, svc);
    protect_mid  = (*env)->GetMethodID(env, cls, "protect", "(I)Z");
    on_exit_mid  = (*env)->GetMethodID(env, cls, "onNativeExit", "()V");
    if (protect_mid == NULL || on_exit_mid == NULL) {
        LOGE("bindService: missing callback methods");
    }
}

JNIEXPORT jboolean JNICALL
Java_io_github_dongyuwei_dsvpn_DsvpnNative_isRunning(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;

    return vpn_running ? JNI_TRUE : JNI_FALSE;
}
