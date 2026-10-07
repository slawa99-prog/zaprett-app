#include <assert.h>
#include <stdio.h>
#include "../app/src/main/cpp/echo_probe.c"
static int bindings;
static int denied(uint64_t n,int fd){assert(n==123&&fd>=0);bindings++;errno=EACCES;return -1;}
static int loopback_bind(uint64_t n,int fd){assert(n==123&&fd>=0);return 0;}
int main(void){
    unsigned char request[32]={8,0,0,0,0,0,0,1};memcpy(request+8,"EthernetLink unique nonce",24);
    uint16_t checksum=echo_checksum(request,32);request[2]=checksum>>8;request[3]=checksum;
    assert(echo_checksum(request,32)==0);
    unsigned char reply[32];memcpy(reply,request,32);reply[0]=0;reply[4]=47;
    assert(echo_reply_matches(reply,32,request,32,0));
    assert(!echo_reply_matches(reply,7,request,32,0));
    assert(!echo_reply_matches(reply,32,request,32,1));
    reply[0]=129;assert(echo_reply_matches(reply,32,request,32,1));
    reply[20]^=1;assert(!echo_reply_matches(reply,32,request,32,1));reply[20]^=1;
    reply[7]=2;assert(!echo_reply_matches(reply,32,request,32,1));
    unsigned char address[4]={127,0,0,1};
    EchoResult bad=echo_probe(0,address,4,0,500,denied);assert(bad.status==2&&bindings==0);
    bad=echo_probe(123,address,3,0,500,denied);assert(bad.status==2&&bindings==0);
    // A platform denial, including opening the ICMP datagram socket itself,
    // must be unavailable, never a successful probe or a lost-packet sample.
    bad=echo_probe(123,address,4,0,500,denied);assert(bad.status==2&&bad.error!=0);
    EchoResult local=echo_probe(123,address,4,0,500,loopback_bind);
    assert(local.status==0||local.status==2);
    if(local.status==0){assert(local.rtt_us>=0);puts("Real IPv4 ICMP echo on loopback passed");}
    else puts("Loopback ICMP unavailable in this environment; protocol and denial checks still ran");
    unsigned char address6[16]={0};address6[15]=1;
    local=echo_probe(123,address6,16,0,500,loopback_bind);assert(local.status==0||local.status==2);
    if(local.status==0)puts("Real IPv6 ICMP echo on loopback passed");
    puts("ICMP checksum, reply identity, bounds and fail-closed network binding passed");return 0;
}
