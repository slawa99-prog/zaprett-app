#include <assert.h>
#include "../app/src/main/cpp/link_probe.c"
static int calls, mode;
static int fake_query(int fd, unsigned long request, void *arg) {
    (void)fd; assert(request == SIOCETHTOOL);
    struct ifreq *ifr = arg; assert(!strcmp(ifr->ifr_name,"eth0"));
    struct ethtool_link_settings *s = (void *)ifr->ifr_data;
    assert(s->cmd == ETHTOOL_GLINKSETTINGS);
    calls++;
    if(mode==1){errno=EACCES;return -1;}
    if(calls==1){assert(s->link_mode_masks_nwords==0);s->link_mode_masks_nwords= mode==2 ? -128 : -4;return 0;}
    assert(s->link_mode_masks_nwords==4);
    // Exercise the entire trailing three-bitmap allocation under ASan.
    uint32_t *m=(void *)((unsigned char *)s+sizeof(*s));for(int i=0;i<12;i++)m[i]=0;
    s->speed=mode==3 ? UINT32_MAX : 1000;s->duplex=DUPLEX_FULL;
    return 0;
}
int main(void){
    char output[2048];
    for(mode=0;mode<4;mode++){
        memset(output,0,sizeof(output));Report r={output,sizeof(output),0};calls=0;
        int status=query_settings(0,"eth0",fake_query,&r);
        if(mode==0){assert(status==0);assert(calls==2);assert(strstr(output,"glinksettings.speed=1000"));}
        if(mode==1){assert(status<0);assert(calls==1);assert(strstr(output,"errno=13"));}
        if(mode==2){assert(status<0);assert(calls==1);}
        if(mode==3){assert(status==0);assert(strstr(output,"glinksettings.speed=-1"));}
    }
    assert(!valid_speed(0));assert(!valid_speed(65535));assert(!valid_speed(UINT32_MAX));assert(valid_speed(100));
    assert(!valid_name("../../bad"));assert(!valid_name("abcdefghijklmnop"));assert(valid_name("eth0"));
    char tiny[2]={0};probe_interface("invalid/name",tiny,sizeof(tiny));assert(tiny[1]==0);
    puts("Native handshake and bounds checks passed");
    return 0;
}
