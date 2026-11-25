#ifdef __ANDROID__
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "bridge.h"
#include "_cgo_export.h"

#define LOG_TAG "vpn233-jni"

static JavaVM *g_jvm = NULL;
static jclass g_bridge_cls = NULL;
static jmethodID g_mid_protect = NULL;
static jmethodID g_mid_query_pkg = NULL;
static jmethodID g_mid_on_log = NULL;
static pthread_key_t g_detach_key;
static pthread_once_t g_key_once = PTHREAD_ONCE_INIT;

static void detach_thread_cb(void *arg) {
    (void)arg;
    if (g_jvm != NULL) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

static void make_detach_key(void) {
    pthread_key_create(&g_detach_key, detach_thread_cb);
}

static JNIEnv *attach_jni_env(void) {
    if (g_jvm == NULL) return NULL;
    JNIEnv *env = NULL;
    jint status = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        pthread_once(&g_key_once, make_detach_key);
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) == JNI_OK) {
            pthread_setspecific(g_detach_key, (void *)1);
            return env;
        }
        return NULL;
    }
    return (status == JNI_OK) ? env : NULL;
}

static char *jbytes_to_cstr(JNIEnv *env, jbyteArray arr) {
    if (arr == NULL) {
        char *empty = (char *)malloc(1);
        if (empty) empty[0] = '\0';
        return empty;
    }
    jsize len = (*env)->GetArrayLength(env, arr);
    char *buf = (char *)malloc((size_t)len + 1);
    if (!buf) return NULL;
    if (len > 0) {
        (*env)->GetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    }
    buf[len] = '\0';
    return buf;
}

static jbyteArray cstr_to_jbytes_and_free(JNIEnv *env, char *cstr) {
    if (cstr == NULL) {
        return (*env)->NewByteArray(env, 0);
    }
    jsize len = (jsize)strlen(cstr);
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr != NULL && len > 0) {
        (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)cstr);
    }
    free(cstr);
    return arr;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = (*env)->FindClass(env, "com/neko233/vpn233/core/MihomoBridge");
    if (cls == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to find MihomoBridge class");
        return JNI_ERR;
    }
    g_bridge_cls = (jclass)(*env)->NewGlobalRef(env, cls);
    (*env)->DeleteLocalRef(env, cls);

    g_mid_protect = (*env)->GetStaticMethodID(env, g_bridge_cls, "onProtectSocket", "(I)Z");
    g_mid_query_pkg = (*env)->GetStaticMethodID(env, g_bridge_cls, "onQuerySocketPackage", "(ILjava/lang/String;Ljava/lang/String;)[B");
    g_mid_on_log = (*env)->GetStaticMethodID(env, g_bridge_cls, "onCoreLog", "(Ljava/lang/String;[B)V");
    if (!g_mid_protect || !g_mid_query_pkg || !g_mid_on_log) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to bind MihomoBridge callback methods");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

int c_protect_socket(int fd) {
    JNIEnv *env = attach_jni_env();
    if (!env || !g_bridge_cls || !g_mid_protect) return 0;
    jboolean ok = (*env)->CallStaticBooleanMethod(env, g_bridge_cls, g_mid_protect, (jint)fd);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }
    return ok ? 1 : 0;
}

char *c_query_socket_pkg(int protocol, const char *src, const char *dst) {
    JNIEnv *env = attach_jni_env();
    if (!env || !g_bridge_cls || !g_mid_query_pkg) return NULL;
    if ((*env)->PushLocalFrame(env, 8) != JNI_OK) return NULL;
    jstring jsrc = (*env)->NewStringUTF(env, src ? src : "");
    jstring jdst = (*env)->NewStringUTF(env, dst ? dst : "");
    jbyteArray jpkg = (jbyteArray)(*env)->CallStaticObjectMethod(env, g_bridge_cls, g_mid_query_pkg, (jint)protocol, jsrc, jdst);
    char *res = NULL;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    } else if (jpkg != NULL) {
        res = jbytes_to_cstr(env, jpkg);
    }
    (*env)->PopLocalFrame(env, NULL);
    return res;
}

void c_on_log(const char *level, const char *msg) {
    JNIEnv *env = attach_jni_env();
    if (!env || !g_bridge_cls || !g_mid_on_log) return;
    if ((*env)->PushLocalFrame(env, 8) != JNI_OK) return;
    jstring jlvl = (*env)->NewStringUTF(env, level ? level : "info");
    size_t len = msg ? strlen(msg) : 0;
    jbyteArray jmsg = (*env)->NewByteArray(env, (jsize)len);
    if (jmsg && len > 0) {
        (*env)->SetByteArrayRegion(env, jmsg, 0, (jsize)len, (const jbyte *)msg);
    }
    if (jlvl && jmsg) {
        (*env)->CallStaticVoidMethod(env, g_bridge_cls, g_mid_on_log, jlvl, jmsg);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    (*env)->PopLocalFrame(env, NULL);
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeInit(JNIEnv *env, jclass clazz, jbyteArray homeDir, jbyteArray version, jint sdkInt) {
    (void)clazz;
    char *h = jbytes_to_cstr(env, homeDir);
    char *v = jbytes_to_cstr(env, version);
    char *res = goInit(h, v, (int)sdkInt);
    free(h);
    free(v);
    return cstr_to_jbytes_and_free(env, res);
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeValidateConfig(JNIEnv *env, jclass clazz, jbyteArray profileDir) {
    (void)clazz;
    char *p = jbytes_to_cstr(env, profileDir);
    char *res = goValidateConfig(p);
    free(p);
    return cstr_to_jbytes_and_free(env, res);
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeLoadConfig(JNIEnv *env, jclass clazz, jbyteArray profileDir, jbyteArray overrideJson) {
    (void)clazz;
    char *p = jbytes_to_cstr(env, profileDir);
    char *o = jbytes_to_cstr(env, overrideJson);
    char *res = goLoadConfig(p, o);
    free(p);
    free(o);
    return cstr_to_jbytes_and_free(env, res);
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeStartTun(JNIEnv *env, jclass clazz, jint fd, jbyteArray stack, jbyteArray gateway, jbyteArray dns, jint mtu) {
    (void)clazz;
    char *s = jbytes_to_cstr(env, stack);
    char *g = jbytes_to_cstr(env, gateway);
    char *d = jbytes_to_cstr(env, dns);
    char *res = goStartTun((int)fd, s, g, d, (int)mtu);
    free(s);
    free(g);
    free(d);
    return cstr_to_jbytes_and_free(env, res);
}

JNIEXPORT void JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeStopTun(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    goStopTun();
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeStartMixed(JNIEnv *env, jclass clazz, jbyteArray listenAddr) {
    (void)clazz;
    char *a = jbytes_to_cstr(env, listenAddr);
    char *res = goStartMixed(a);
    free(a);
    return cstr_to_jbytes_and_free(env, res);
}

JNIEXPORT void JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeStopMixed(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    goStopMixed();
}

JNIEXPORT void JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeReset(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    goReset();
}

JNIEXPORT void JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeSetMode(JNIEnv *env, jclass clazz, jbyteArray mode) {
    (void)clazz;
    char *m = jbytes_to_cstr(env, mode);
    goSetMode(m);
    free(m);
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeGetMode(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return cstr_to_jbytes_and_free(env, goGetMode());
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeQueryGroupsJson(JNIEnv *env, jclass clazz, jbyteArray testUrl) {
    (void)clazz;
    char *u = jbytes_to_cstr(env, testUrl);
    char *res = goQueryGroupsJson(u);
    free(u);
    return cstr_to_jbytes_and_free(env, res);
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeQueryRulesJson(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return cstr_to_jbytes_and_free(env, goQueryRulesJson());
}

JNIEXPORT jboolean JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeSelectProxy(JNIEnv *env, jclass clazz, jbyteArray group, jbyteArray proxy) {
    (void)clazz;
    char *g = jbytes_to_cstr(env, group);
    char *p = jbytes_to_cstr(env, proxy);
    int ok = goSelectProxy(g, p);
    free(g);
    free(p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeTestGroupDelay(JNIEnv *env, jclass clazz, jbyteArray group, jbyteArray testUrl, jint timeoutMs) {
    (void)clazz;
    char *g = jbytes_to_cstr(env, group);
    char *u = jbytes_to_cstr(env, testUrl);
    char *res = goTestGroupDelay(g, u, (int)timeoutMs);
    free(g);
    free(u);
    return cstr_to_jbytes_and_free(env, res);
}

JNIEXPORT jint JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeTestProxyDelay(JNIEnv *env, jclass clazz, jbyteArray proxy, jbyteArray testUrl, jint timeoutMs) {
    (void)clazz;
    char *p = jbytes_to_cstr(env, proxy);
    char *u = jbytes_to_cstr(env, testUrl);
    int delay = goTestProxyDelay(p, u, (int)timeoutMs);
    free(p);
    free(u);
    return (jint)delay;
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeQueryTrafficJson(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return cstr_to_jbytes_and_free(env, goQueryTrafficJson());
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeQueryConnectionsJson(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return cstr_to_jbytes_and_free(env, goQueryConnectionsJson());
}

JNIEXPORT jboolean JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeCloseConnection(JNIEnv *env, jclass clazz, jbyteArray uuid) {
    (void)clazz;
    char *id = jbytes_to_cstr(env, uuid);
    int ok = goCloseConnection(id);
    free(id);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeCloseAllConnections(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    goCloseAllConnections();
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeQueryProvidersJson(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return cstr_to_jbytes_and_free(env, goQueryProvidersJson());
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeUpdateProvider(JNIEnv *env, jclass clazz, jbyteArray kind, jbyteArray name) {
    (void)clazz;
    char *k = jbytes_to_cstr(env, kind);
    char *n = jbytes_to_cstr(env, name);
    char *res = goUpdateProvider(k, n);
    free(k);
    free(n);
    return cstr_to_jbytes_and_free(env, res);
}

JNIEXPORT void JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeNotifyDnsChanged(JNIEnv *env, jclass clazz, jbyteArray dnsCsv) {
    (void)clazz;
    char *d = jbytes_to_cstr(env, dnsCsv);
    goNotifyDnsChanged(d);
    free(d);
}

JNIEXPORT void JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeNotifyTimeZoneChanged(JNIEnv *env, jclass clazz, jbyteArray tzName, jint offsetSec) {
    (void)clazz;
    char *t = jbytes_to_cstr(env, tzName);
    goNotifyTimeZoneChanged(t, (int)offsetSec);
    free(t);
}

JNIEXPORT void JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeSetLogLevel(JNIEnv *env, jclass clazz, jbyteArray level) {
    (void)clazz;
    char *l = jbytes_to_cstr(env, level);
    goSetLogLevel(l);
    free(l);
}

JNIEXPORT void JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeSetLogStreaming(JNIEnv *env, jclass clazz, jboolean enabled) {
    (void)env;
    (void)clazz;
    goSetLogStreaming(enabled ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeForceGc(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    goForceGc();
}

JNIEXPORT void JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeSetIdleMode(JNIEnv *env, jclass clazz, jboolean idle) {
    (void)env;
    (void)clazz;
    goSetIdleMode(idle ? 1 : 0);
}

JNIEXPORT jbyteArray JNICALL
Java_com_neko233_vpn233_core_MihomoBridge_nativeCoreVersion(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return cstr_to_jbytes_and_free(env, goCoreVersion());
}
#endif
