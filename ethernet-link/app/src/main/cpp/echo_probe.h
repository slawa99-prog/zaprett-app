#ifndef ETHERNET_ECHO_PROBE_H
#define ETHERNET_ECHO_PROBE_H
#include <stdint.h>
#include <stddef.h>
typedef struct { int status; int rtt_us; int error; } EchoResult;
typedef int (*EchoBind)(uint64_t,int);
uint16_t echo_checksum(const unsigned char *bytes,size_t length);
int echo_reply_matches(const unsigned char *reply,size_t length,const unsigned char *request,size_t request_length,int ipv6);
EchoResult echo_probe(uint64_t network,const unsigned char *address,size_t length,int scope,int timeout_ms,EchoBind bind_network);
#endif
