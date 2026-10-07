#include <jni.h>
#include <android/multinetwork.h>
#include "echo_probe.h"
JNIEXPORT jintArray JNICALL Java_com_slawa_ethernetlink_NativeLink_echoProbe(JNIEnv *env,jclass clazz,jlong network,jbyteArray address,jint scope,jint timeout){
    (void)clazz;EchoResult result={2,-1,22};
    if(address){jsize n=(*env)->GetArrayLength(env,address);if(n==4||n==16){
        unsigned char bytes[16];(*env)->GetByteArrayRegion(env,address,0,n,(jbyte*)bytes);
        if((*env)->ExceptionCheck(env))return NULL;
        result=echo_probe((uint64_t)network,bytes,(size_t)n,scope,timeout,android_setsocknetwork);
    }}
    jint values[3]={result.status,result.rtt_us,result.error};jintArray array=(*env)->NewIntArray(env,3);
    if(array)(*env)->SetIntArrayRegion(env,array,0,3,values);return array;
}
