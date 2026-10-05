#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <net/if.h>
#include <linux/ethtool.h>
#include <linux/sockios.h>

static int glink(int fd,const char* n){
 struct ifreq i; struct ethtool_value e; memset(&i,0,sizeof(i)); memset(&e,0,sizeof(e));
 strncpy(i.ifr_name,n,IFNAMSIZ-1); e.cmd=ETHTOOL_GLINK; i.ifr_data=(char*)&e;
 return ioctl(fd,SIOCETHTOOL,&i)==0?(e.data?1:0):-1;
}
static void gset(int fd,const char* n,int* sp,int* dup){
 struct ifreq i; struct ethtool_cmd e; memset(&i,0,sizeof(i)); memset(&e,0,sizeof(e));
 strncpy(i.ifr_name,n,IFNAMSIZ-1); e.cmd=ETHTOOL_GSET; i.ifr_data=(char*)&e;
 if(ioctl(fd,SIOCETHTOOL,&i)==0){
  unsigned int s=((unsigned int)e.speed)|(((unsigned int)e.speed_hi)<<16);
  if(s&&s!=0xffffffffu)*sp=(int)s;
  if(e.duplex==DUPLEX_FULL)*dup=1; else if(e.duplex==DUPLEX_HALF)*dup=0;
 }
}
JNIEXPORT jstring JNICALL Java_com_slawa_ethernetlink_NativeLink_getLinkInfo(JNIEnv* env,jclass c,jstring jn){
 (void)c; if(!jn)return NULL; const char* n=(*env)->GetStringUTFChars(env,jn,NULL);
 int sp=-1,dup=-1,link=-1,fd=socket(AF_INET,SOCK_DGRAM,0);
 if(fd>=0){link=glink(fd,n);gset(fd,n,&sp,&dup);close(fd);}
 (*env)->ReleaseStringUTFChars(env,jn,n); char out[64]; snprintf(out,sizeof(out),"%d|%d|%d",sp,dup,link);
 return (*env)->NewStringUTF(env,out);
}
