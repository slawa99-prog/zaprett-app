#include "echo_probe.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <errno.h>
#include <poll.h>
#include <string.h>
#include <time.h>

static int64_t echo_time_us(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (int64_t)t.tv_sec*1000000+t.tv_nsec/1000;}
uint16_t echo_checksum(const unsigned char *bytes,size_t length){
    uint32_t sum=0;size_t i=0;
    for(;i+1<length;i+=2)sum+=((uint32_t)bytes[i]<<8)|bytes[i+1];
    if(i<length)sum+=(uint32_t)bytes[i]<<8;
    while(sum>>16)sum=(sum&0xffff)+(sum>>16);
    return (uint16_t)~sum;
}
int echo_reply_matches(const unsigned char *reply,size_t length,const unsigned char *request,size_t request_length,int ipv6){
    // Linux ping datagram sockets return an ICMP header without an IP header.
    // The kernel owns the identifier; sequence and per-request nonce must match.
    return request_length>=16&&length==request_length&&reply[0]==(ipv6?129:0)&&reply[1]==0
        &&reply[6]==request[6]&&reply[7]==request[7]&&!memcmp(reply+8,request+8,request_length-8);
}
EchoResult echo_probe(uint64_t network,const unsigned char *address,size_t length,int scope,int timeout_ms,EchoBind bind_network){
    EchoResult out={2,-1,EINVAL};
    if(!network||!address||(length!=4&&length!=16)||timeout_ms<100||timeout_ms>2000||!bind_network)return out;
    int ipv6=length==16;
    int fd=socket(ipv6?AF_INET6:AF_INET,SOCK_DGRAM|SOCK_CLOEXEC,ipv6?IPPROTO_ICMPV6:IPPROTO_ICMP);
    if(fd<0){out.error=errno;return out;}
    // Bind BEFORE any connect/send. Never retry on the process default network.
    if(bind_network(network,fd)<0){out.error=errno;close(fd);return out;}
    struct sockaddr_storage destination;memset(&destination,0,sizeof(destination));socklen_t size;
    if(ipv6){struct sockaddr_in6 *a=(void*)&destination;a->sin6_family=AF_INET6;a->sin6_scope_id=(uint32_t)scope;memcpy(&a->sin6_addr,address,16);size=sizeof(*a);}
    else{struct sockaddr_in *a=(void*)&destination;a->sin_family=AF_INET;memcpy(&a->sin_addr,address,4);size=sizeof(*a);}
    if(connect(fd,(void*)&destination,size)<0){out.error=errno;close(fd);return out;}
    unsigned char request[32]={0};request[0]=ipv6?128:8;request[7]=1;
    memcpy(request+8,"EthernetLink",12);int64_t nonce=echo_time_us();memcpy(request+20,&nonce,sizeof(nonce));
    if(!ipv6){uint16_t checksum=echo_checksum(request,sizeof(request));request[2]=(unsigned char)(checksum>>8);request[3]=(unsigned char)checksum;}
    int64_t started=echo_time_us(),deadline=started+(int64_t)timeout_ms*1000;
    if(send(fd,request,sizeof(request),0)!=(ssize_t)sizeof(request)){out.error=errno?errno:EIO;close(fd);return out;}
    out.status=1;out.error=0;
    while(echo_time_us()<deadline){
        struct pollfd p={fd,POLLIN,0};int remaining=(int)((deadline-echo_time_us()+999)/1000);if(remaining<1)break;
        int ready=poll(&p,1,remaining);
        if(ready<0){if(errno==EINTR)continue;out.status=2;out.error=errno;break;}
        if(!ready)break;
        unsigned char reply[512];ssize_t received=recv(fd,reply,sizeof(reply),MSG_DONTWAIT);
        if(received<0){if(errno==EAGAIN||errno==EINTR)continue;out.status=2;out.error=errno;break;}
        if(echo_reply_matches(reply,(size_t)received,request,sizeof(request),ipv6)){
            out.status=0;out.rtt_us=(int)(echo_time_us()-started);break;
        }
    }
    close(fd);return out;
}
