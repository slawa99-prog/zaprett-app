#include <jni.h>
#include "link_probe.h"
JNIEXPORT jstring JNICALL Java_com_slawa_ethernetlink_NativeLink_getLinkInfo(JNIEnv *env, jclass cls, jstring name) {
    (void)cls;
    if (!name) return NULL;
    const char *iface = (*env)->GetStringUTFChars(env, name, NULL);
    if (!iface) return NULL;
    char report[4096];
    probe_interface(iface, report, sizeof(report));
    (*env)->ReleaseStringUTFChars(env, name, iface);
    return (*env)->NewStringUTF(env, report);
}
