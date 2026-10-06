/* Read-only driver queries. No shell commands, USB claims or link changes. */
#include "link_probe.h"
#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <net/if.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <linux/ethtool.h>
#include <linux/sockios.h>

typedef struct { char *data; size_t size, used; } Report;
typedef int (*Query)(int, unsigned long, void *);
static void log_line(Report *r, const char *format, ...) {
    if (r->used >= r->size) return;
    va_list ap;
    va_start(ap, format);
    int n = vsnprintf(r->data + r->used, r->size - r->used, format, ap);
    va_end(ap);
    if (n > 0) r->used += (size_t)n < r->size - r->used ? (size_t)n : r->size - r->used;
}
static int valid_speed(uint32_t s) { return s > 0 && s != 65535 && s <= INT_MAX; }
static int valid_name(const char *name) {
    size_t n = strlen(name);
    if (!n || n >= IFNAMSIZ) return 0;
    for (size_t i = 0; i < n; ++i) {
        char c = name[i];
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.')) return 0;
    }
    return 1;
}
static void init_ifreq(struct ifreq *ifr, const char *name, void *data) {
    memset(ifr, 0, sizeof(*ifr));
    memcpy(ifr->ifr_name, name, strlen(name) + 1);
    ifr->ifr_data = data;
}
static int system_query(int fd, unsigned long request, void *arg) { return ioctl(fd, request, arg); }
static void log_error(Report *r, const char *method, int error) {
    log_line(r, "%s.errno=%d\n%s.error=%s\n", method, error, method, strerror(error));
}
static void query_link(int fd, const char *name, Report *r, const char *key) {
    struct ifreq ifr;
    struct ethtool_value value = { .cmd = ETHTOOL_GLINK };
    init_ifreq(&ifr, name, &value);
    if (ioctl(fd, SIOCETHTOOL, &ifr) < 0) log_error(r, key, errno);
    else log_line(r, "%s.link=%d\n", key, value.data ? 1 : 0);
}
/* First GLINKSETTINGS response negotiates bitmap size; it is not a speed. */
static int query_settings(int fd, const char *name, Query query, Report *r) {
    struct ifreq ifr;
    struct ethtool_link_settings first;
    memset(&first, 0, sizeof(first));
    first.cmd = ETHTOOL_GLINKSETTINGS;
    init_ifreq(&ifr, name, &first);
    if (query(fd, SIOCETHTOOL, &ifr) < 0) {
        log_error(r, "glinksettings.handshake", errno); return -1;
    }
    int words = -(int)first.link_mode_masks_nwords;
    if (words <= 0 || words > 127) { log_error(r, "glinksettings.handshake", EPROTO); return -1; }
    size_t size = sizeof(first) + 3u * (size_t)words * sizeof(uint32_t);
    struct ethtool_link_settings *s = calloc(1, size);
    if (!s) { log_error(r, "glinksettings", ENOMEM); return -1; }
    s->cmd = ETHTOOL_GLINKSETTINGS;
    s->link_mode_masks_nwords = (int8_t)words;
    ifr.ifr_data = (char *)s;
    int status = query(fd, SIOCETHTOOL, &ifr);
    int error = status < 0 ? errno : EPROTO;
    if (status == 0 && s->cmd == ETHTOOL_GLINKSETTINGS && s->link_mode_masks_nwords == words) {
        log_line(r, "glinksettings.speed=%d\nglinksettings.duplex=%d\n",
                 valid_speed(s->speed) ? (int)s->speed : -1, s->duplex <= DUPLEX_FULL ? s->duplex : -1);
    } else { status = -1; log_error(r, "glinksettings", error); }
    free(s);
    return status;
}
static void query_legacy(int fd, const char *name, Report *r) {
    struct ifreq ifr;
    struct ethtool_cmd settings;
    memset(&settings, 0, sizeof(settings));
    settings.cmd = ETHTOOL_GSET;
    init_ifreq(&ifr, name, &settings);
    if (ioctl(fd, SIOCETHTOOL, &ifr) < 0) { log_error(r, "gset", errno); return; }
    uint32_t speed = settings.speed | ((uint32_t)settings.speed_hi << 16);
    log_line(r, "gset.speed=%d\ngset.duplex=%d\n", valid_speed(speed) ? (int)speed : -1,
             settings.duplex <= DUPLEX_FULL ? settings.duplex : -1);
}
void probe_interface(const char *name, char *output, size_t size) {
    Report r = {output, size, 0};
    if (!size) return;
    output[0] = 0;
    if (!valid_name(name)) { log_error(&r, "interface", EINVAL); return; }
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        log_error(&r, "socket.ipv4", errno);
        fd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    }
    if (fd < 0) { log_error(&r, "socket.ipv6", errno); return; }
    log_line(&r, "socket.ok=1\n");
    struct ifreq ifr;
    init_ifreq(&ifr, name, NULL);
    if (ioctl(fd, SIOCGIFINDEX, &ifr) == 0) log_line(&r, "interface.exists=1\ninterface.index=%d\n", ifr.ifr_ifindex);
    else log_error(&r, "interface.index", errno);
    query_link(fd, name, &r, "glink.before");
    query_settings(fd, name, system_query, &r);
    query_legacy(fd, name, &r);
    query_link(fd, name, &r, "glink.after");
    close(fd);
}
