/*
 * minichat.c -- a tiny, zero-dependency LAN chat + file drop server.
 *
 * One HTML page, a handful of JSON endpoints, an in-memory message log.
 * No third-party library: WinSock2 / POSIX sockets + the C runtime only.
 *
 * Windows:  cl /O1 /MT ... /link /subsystem:windows ws2_32.lib shell32.lib iphlpapi.lib
 * POSIX:    cc -O2 -pthread minichat.c -o minichat
 *
 * The same source builds for Android / HarmonyOS through their NDK toolchains:
 * those platforms use the POSIX branch, and the app embeds this as a native
 * service (or runs it as a plain command-line binary).
 */
#define _CRT_SECURE_NO_WARNINGS
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdarg.h>
#include <time.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <iphlpapi.h>
#include <shellapi.h>
#include <process.h>
#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "iphlpapi.lib")
typedef SOCKET sock_t;
typedef HANDLE thread_handle;
#define BAD_SOCKET INVALID_SOCKET
#define THREAD_RET unsigned __stdcall
#define CLOSESOCK closesocket
#define STRICMP _stricmp
static CRITICAL_SECTION g_lock;
#define LOCK_INIT() InitializeCriticalSection(&g_lock)
#define LOCK() EnterCriticalSection(&g_lock)
#define UNLOCK() LeaveCriticalSection(&g_lock)
#else
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <ifaddrs.h>
#include <net/if.h>   /* IFF_UP / IFF_BROADCAST：Android bionic 不从其它头间接引入 */
#include <unistd.h>
#include <pthread.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>
static int _strnicmp(const char *a, const char *b, size_t n);
typedef int sock_t;
typedef pthread_t thread_handle;
#define BAD_SOCKET (-1)
#define THREAD_RET void *
#define CLOSESOCK close
#define STRICMP strcasecmp
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
#define LOCK_INIT() ((void)0)
#define LOCK() pthread_mutex_lock(&g_lock)
#define UNLOCK() pthread_mutex_unlock(&g_lock)
#endif

#include "chat_page.h"

#define VERSION "lsbridge-chat-1"
#define DEFAULT_PORT 53317
#define MAX_PORT_TRY 21
#define UDP_PORT 53320
#define MAX_MSG 400
#define MAX_POLL_MSG 120
#define MAX_DEV 64
#define MAX_NODE 32
#define CLIENT_TTL 60000
#define NODE_TTL 150000
#define MAX_BODY (2u * 1024u * 1024u)
#define SCAN_THREADS 12

/* ------------------------------------------------------------------ state */
typedef struct {
    uint64_t id, time, fsize;
    char from[64], from_name[64], to[64], kind[16], file_name[256], file_url[512];
    char *text;
} Msg;

typedef struct {
    char id[64], name[64], type[16], ip[48];
    uint64_t seen;
} Dev;

typedef struct {
    char base[128], name[64], fp[64], source[16];
    uint64_t seen;
} Node;

static Msg g_msgs[MAX_MSG];
static int g_nmsg;
static uint64_t g_nextid;
static Dev g_devs[MAX_DEV];
static int g_ndev;
static Node g_nodes[MAX_NODE];
static int g_nnode;

static char g_share[1024];
static char g_alias[128] = "局域网大厅";
static char g_hostname[128] = "minichat";
static char g_fp[64];
static char g_ips[8][64];
static char g_bcast[8][64];
static int g_nips;
static int g_port = DEFAULT_PORT;
/* JNI 起的是后台线程，启动方需要知道 listen 何时真正完成。 */
static volatile int g_server_ready = 0;
static int g_no_browser;
static int g_console;

/* 局域网邀请口令：由宿主 App 生成并持久化，通过 --token 传入。
 * 本机(127.0.0.1)免口令，其它来源必须带 ?k=<token> 或 Cookie: mc=<token>。
 * 没配 token 时 Android 自动退回只绑 loopback，绝不裸奔到局域网。 */
static char g_token[64];
/* 给人看的 6 位配对码；token 只放在 Cookie 里，用户不用记一长串。 */
static char g_pin[16];
static volatile int g_pair_fails;
#ifdef _WIN32
#define MC_TLS __declspec(thread)
#else
#define MC_TLS __thread
#endif
/* 只有当前请求线程鉴权通过才允许下发 Set-Cookie，防止 403 响应泄漏口令。 */
static MC_TLS int t_authorized;
/* Held for the whole process lifetime: released by the OS on exit. */
#ifdef _WIN32
static HANDLE g_single_mutex;
#else
static int g_single_lock_fd = -1;
#endif

/* ------------------------------------------------------------------ utils */
static uint64_t tick_ms(void) {
#ifdef _WIN32
    return (uint64_t)GetTickCount64();
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
#endif
}
static uint64_t epoch_ms(void) { return (uint64_t)time(NULL) * 1000; }

#ifndef _WIN32
static int _strnicmp(const char *a, const char *b, size_t n) { return strncasecmp(a, b, n); }
#endif

static void debugf(const char *fmt, ...) {
    va_list ap;
    char buf[1024];
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
#ifdef _WIN32
    OutputDebugStringA(buf);
    if (g_console) fputs(buf, stderr);
#else
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "MiniChat", "%s", buf);
#else
    fputs(buf, stderr);
#endif
#endif
}

static void *xmalloc(size_t n) {
    void *p = malloc(n ? n : 1);
    if (!p) { debugf("[minichat] out of memory\n"); exit(1); }
    return p;
}

/* Growable output buffer ------------------------------------------------- */
typedef struct { char *p; size_t n, cap; } Buf;
static void bgrow(Buf *b, size_t extra) {
    size_t cap;
    char *q;
    if (b->n + extra + 1 <= b->cap) return;
    cap = b->cap ? b->cap : 1024;
    while (cap < b->n + extra + 1) cap *= 2;
    q = (char *)realloc(b->p, cap);
    if (!q) { debugf("[minichat] out of memory\n"); exit(1); }
    b->p = q; b->cap = cap;
}
static void bput(Buf *b, const char *s, size_t n) {
    if (!n) return;
    bgrow(b, n);
    memcpy(b->p + b->n, s, n);
    b->n += n;
    b->p[b->n] = 0;
}
static void bs(Buf *b, const char *s) { bput(b, s, strlen(s)); }
static void bf(Buf *b, const char *fmt, ...) {
    char t[1024];
    va_list ap;
    int n;
    va_start(ap, fmt);
    n = vsnprintf(t, sizeof(t), fmt, ap);
    va_end(ap);
    if (n > 0) bput(b, t, (size_t)n);
}
/* Append a JSON string literal, escaping as needed (UTF-8 passes through). */
static void bj(Buf *b, const char *s) {
    const unsigned char *p = (const unsigned char *)(s ? s : "");
    bs(b, "\"");
    for (; *p; p++) {
        char c;
        switch (*p) {
        case '"': bs(b, "\\\""); continue;
        case '\\': bs(b, "\\\\"); continue;
        case '\n': bs(b, "\\n"); continue;
        case '\r': bs(b, "\\r"); continue;
        case '\t': bs(b, "\\t"); continue;
        default: break;
        }
        if (*p < 0x20) { bf(b, "\\u%04x", (unsigned)*p); continue; }
        c = (char)*p;
        bput(b, &c, 1);
    }
    bs(b, "\"");
}

/* Minimal JSON field readers -------------------------------------------- */
static const char *find_key(const char *s, const char *key) {
    char pat[80];
    const char *p;
    snprintf(pat, sizeof(pat), "\"%s\"", key);
    p = s;
    while ((p = strstr(p, pat)) != NULL) {
        const char *q = p + strlen(pat);
        while (*q == ' ' || *q == '\t' || *q == '\n' || *q == '\r') q++;
        if (*q == ':') return q + 1;
        p = q;
    }
    return NULL;
}
static void append_utf8(Buf *b, unsigned cp) {
    if (cp < 0x80) { char c = (char)cp; bput(b, &c, 1); }
    else if (cp < 0x800) {
        char t[2] = { (char)(0xc0 | (cp >> 6)), (char)(0x80 | (cp & 63)) };
        bput(b, t, 2);
    } else {
        char t[3] = { (char)(0xe0 | (cp >> 12)),
                      (char)(0x80 | ((cp >> 6) & 63)),
                      (char)(0x80 | (cp & 63)) };
        bput(b, t, 3);
    }
}
static void json_str(const char *s, const char *key, char *out, size_t cap) {
    const char *v = find_key(s, key);
    Buf b;
    b.p = NULL; b.n = 0; b.cap = 0;
    if (!cap) return;
    out[0] = 0;
    if (!v) return;
    while (*v == ' ' || *v == '\t' || *v == '\r' || *v == '\n') v++;
    if (*v != '"') return;
    v++;
    while (*v && *v != '"') {
        if (*v == '\\') {
            v++;
            if (*v == 'u') {
                unsigned cp = 0;
                int i;
                v++;
                for (i = 0; i < 4 && *v; i++, v++) {
                    char c = *v;
                    unsigned d = (c >= '0' && c <= '9') ? (unsigned)(c - '0')
                               : (c >= 'a' && c <= 'f') ? (unsigned)(c - 'a' + 10)
                               : (c >= 'A' && c <= 'F') ? (unsigned)(c - 'A' + 10) : 0;
                    cp = cp * 16 + d;
                }
                append_utf8(&b, cp);
                continue;
            }
            switch (*v) {
            case 'n': bput(&b, "\n", 1); break;
            case 'r': bput(&b, "\r", 1); break;
            case 't': bput(&b, "\t", 1); break;
            case 'b': bput(&b, "\b", 1); break;
            case 'f': bput(&b, "\f", 1); break;
            default: if (*v) bput(&b, v, 1); break;
            }
            if (*v) v++;
        } else {
            bput(&b, v, 1);
            v++;
        }
        if (b.n + 1 >= cap) break;
    }
    memcpy(out, b.p ? b.p : "", b.n < cap - 1 ? b.n : cap - 1);
    out[b.n < cap - 1 ? b.n : cap - 1] = 0;
    free(b.p);
}
static uint64_t json_num(const char *s, const char *key, uint64_t dflt) {
    const char *v = find_key(s, key);
    if (!v) return dflt;
    while (*v == ' ' || *v == '\t') v++;
    if (*v < '0' || *v > '9') return dflt;
    return strtoull(v, NULL, 10);
}

/* URL / filename helpers ------------------------------------------------- */
static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}
static void url_decode(const char *src, char *out, size_t cap) {
    size_t n = 0;
    for (; *src && n + 1 < cap; src++) {
        if (*src == '%' && hexval(src[1]) >= 0 && hexval(src[2]) >= 0) {
            out[n++] = (char)(hexval(src[1]) * 16 + hexval(src[2]));
            src += 2;
        } else if (*src == '+') {
            out[n++] = ' ';
        } else {
            out[n++] = *src;
        }
    }
    out[n] = 0;
}
static void url_encode(const char *s, char *out, size_t cap) {
    static const char *hex = "0123456789ABCDEF";
    size_t n = 0;
    const unsigned char *p = (const unsigned char *)s;
    for (; *p && n + 4 < cap; p++) {
        unsigned char c = *p;
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
            out[n++] = (char)c;
        } else {
            out[n++] = '%'; out[n++] = hex[c >> 4]; out[n++] = hex[c & 15];
        }
    }
    out[n] = 0;
}
static void sanitize(const char *in, char *out, size_t cap) {
    size_t n = 0;
    const unsigned char *p = (const unsigned char *)in;
    for (; *p && n + 1 < cap; p++) {
        unsigned char c = *p;
        if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' ||
            c == '"' || c == '<' || c == '>' || c == '|' || c < 32) continue;
        out[n++] = (char)c;
    }
    out[n] = 0;
    while (n && out[0] == '.') { memmove(out, out + 1, n); n--; out[n] = 0; }
    if (!n) { memcpy(out, "file.bin", 9); }
}
/* query lookup; returns a decoded, NUL-terminated value */
static void query_get(const char *q, const char *key, char *out, size_t cap) {
    char pat[80];
    const char *p;
    size_t n = 0;
    out[0] = 0;
    if (!q) return;
    snprintf(pat, sizeof(pat), "%s=", key);
    p = q;
    while ((p = strstr(p, pat)) != NULL) {
        if (p == q || p[-1] == '&') break;
        p += strlen(pat);
    }
    if (!p) return;
    p += strlen(pat);
    for (; *p && *p != '&' && n + 1 < cap; p++) {
        if (*p == '%' && hexval(p[1]) >= 0 && hexval(p[2]) >= 0) {
            out[n++] = (char)(hexval(p[1]) * 16 + hexval(p[2]));
            p += 2;
        } else if (*p == '+') {
            out[n++] = ' ';
        } else {
            out[n++] = *p;
        }
    }
    out[n] = 0;
}

/* ------------------------------------------------------------- interfaces */
static int ip_preferred(const char *ip) {
    unsigned a, b;
    if (sscanf(ip, "%u.%u", &a, &b) != 2) return 0;
    if (a == 127 || a == 0 || a >= 224) return -1;
    if (a == 169 && b == 254) return -1;
    if (a == 198 && b == 18) return -1;  /* TUN / proxy adapters */
    if (a == 100 && b >= 64 && b <= 127) return -1;
    if (a == 192 && b == 168) return 3;
    if (a == 10) return 2;
    if (a == 172 && b >= 16 && b <= 31) return 2;
    return 1;
}
static void add_iface(const char *ip, const char *bcast) {
    int rank = ip_preferred(ip), i, j;
    if (rank < 0) return;
    for (i = 0; i < g_nips; i++) if (!strcmp(g_ips[i], ip)) return;
    if (g_nips >= 8) return;
    /* private LAN addresses first */
    for (j = 0; j < g_nips; j++) {
        if (ip_preferred(g_ips[j]) < rank) break;
    }
    for (i = g_nips; i > j; i--) {
        memcpy(g_ips[i], g_ips[i - 1], sizeof(g_ips[0]));
        memcpy(g_bcast[i], g_bcast[i - 1], sizeof(g_bcast[0]));
    }
    snprintf(g_ips[j], sizeof(g_ips[0]), "%s", ip);
    snprintf(g_bcast[j], sizeof(g_bcast[0]), "%s", bcast);
    g_nips++;
}
static void collect_interfaces(void) {
#ifdef _WIN32
    ULONG size = 15 * 1024;
    IP_ADAPTER_ADDRESSES *buf = (IP_ADAPTER_ADDRESSES *)xmalloc(size), *a;
    if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST,
                             NULL, buf, &size) == ERROR_BUFFER_OVERFLOW) {
        free(buf); buf = (IP_ADAPTER_ADDRESSES *)xmalloc(size);
    }
    if (GetAdaptersAddresses(AF_INET, 0, NULL, buf, &size) != NO_ERROR) { free(buf); return; }
    for (a = buf; a; a = a->Next) {
        IP_ADAPTER_UNICAST_ADDRESS *u;
        if (a->OperStatus != IfOperStatusUp) continue;
        for (u = a->FirstUnicastAddress; u; u = u->Next) {
            struct sockaddr_in *sin = (struct sockaddr_in *)u->Address.lpSockaddr;
            char ip[64], bcast[64];
            unsigned long mask;
            if (!sin || sin->sin_family != AF_INET) continue;
            snprintf(ip, sizeof(ip), "%u.%u.%u.%u",
                     sin->sin_addr.S_un.S_un_b.s_b1, sin->sin_addr.S_un.S_un_b.s_b2,
                     sin->sin_addr.S_un.S_un_b.s_b3, sin->sin_addr.S_un.S_un_b.s_b4);
            mask = (u->OnLinkPrefixLength == 0) ? 0 : (0xffffffffUL << (32 - u->OnLinkPrefixLength));
            {
                unsigned long addr = (unsigned long)ntohl(sin->sin_addr.s_addr);
                unsigned long b = (addr & mask) | (~mask);
                struct in_addr in; in.s_addr = (u_long)htonl(b);
                snprintf(bcast, sizeof(bcast), "%s", inet_ntoa(in));
            }
            add_iface(ip, bcast);
        }
    }
    free(buf);
#else
    struct ifaddrs *list, *it;
    if (getifaddrs(&list) != 0) return;
    for (it = list; it; it = it->ifa_next) {
        struct sockaddr_in *sin, *mask;
        char ip[64], bcast[64];
        uint32_t a, m;
        struct in_addr in;
        if (!it->ifa_addr || it->ifa_addr->sa_family != AF_INET) continue;
        if (!(it->ifa_flags & IFF_UP)) continue;
        sin = (struct sockaddr_in *)it->ifa_addr;
        mask = (struct sockaddr_in *)it->ifa_netmask;
        snprintf(ip, sizeof(ip), "%s", inet_ntoa(sin->sin_addr));
        a = (uint32_t)ntohl(sin->sin_addr.s_addr);
        m = mask ? (uint32_t)ntohl(mask->sin_addr.s_addr) : 0xffffff00u;
        in.s_addr = htonl((a & m) | (~m));
        snprintf(bcast, sizeof(bcast), "%s", inet_ntoa(in));
        add_iface(ip, bcast);
    }
    freeifaddrs(list);
#endif
}

/* --------------------------------------------------------------- registry */
static void register_dev(const char *id, const char *name, const char *type, const char *ip) {
    int i, slot = -1;
    uint64_t t = tick_ms(), oldest = t;
    for (i = 0; i < g_ndev; i++) {
        if (!strcmp(g_devs[i].id, id)) { slot = i; break; }
        if (g_devs[i].seen < oldest) { oldest = g_devs[i].seen; slot = -1; }
    }
    if (slot < 0) {
        if (g_ndev < MAX_DEV) slot = g_ndev++;
        else { /* reuse the stalest */ uint64_t o = (uint64_t)-1; slot = 0;
               for (i = 0; i < g_ndev; i++) if (g_devs[i].seen < o) { o = g_devs[i].seen; slot = i; } }
    }
    snprintf(g_devs[slot].id, sizeof(g_devs[0].id), "%s", id);
    snprintf(g_devs[slot].name, sizeof(g_devs[0].name), "%s", name[0] ? name : "未命名设备");
    snprintf(g_devs[slot].type, sizeof(g_devs[0].type), "%s", type[0] ? type : "desktop");
    snprintf(g_devs[slot].ip, sizeof(g_devs[0].ip), "%s", ip);
    g_devs[slot].seen = t;
}
static void register_node(const char *fp, const char *base, const char *name, const char *src) {
    int i, slot = -1;
    if (!fp || !fp[0] || !strcmp(fp, g_fp)) return;
    for (i = 0; i < g_nnode; i++) if (!strcmp(g_nodes[i].fp, fp)) { slot = i; break; }
    if (slot < 0) {
        if (g_nnode < MAX_NODE) slot = g_nnode++;
        else slot = 0;
    }
    snprintf(g_nodes[slot].fp, sizeof(g_nodes[0].fp), "%s", fp);
    snprintf(g_nodes[slot].base, sizeof(g_nodes[0].base), "%s", base);
    snprintf(g_nodes[slot].name, sizeof(g_nodes[0].name), "%s", name);
    snprintf(g_nodes[slot].source, sizeof(g_nodes[0].source), "%s", src);
    g_nodes[slot].seen = tick_ms();
}
static void add_message(const char *from, const char *name, const char *to,
                        const char *kind, const char *text, const char *fname,
                        const char *furl, uint64_t fsize, uint64_t *out_id) {
    Msg *m;
    if (g_nmsg == MAX_MSG) {
        free(g_msgs[0].text);
        memmove(g_msgs, g_msgs + 1, (MAX_MSG - 1) * sizeof(Msg));
        g_nmsg--;
    }
    m = &g_msgs[g_nmsg++];
    memset(m, 0, sizeof(*m));
    m->id = ++g_nextid;
    m->time = epoch_ms();
    m->fsize = fsize;
    snprintf(m->from, sizeof(m->from), "%s", from);
    snprintf(m->from_name, sizeof(m->from_name), "%s", name);
    snprintf(m->to, sizeof(m->to), "%s", to);
    snprintf(m->kind, sizeof(m->kind), "%s", kind[0] ? kind : "text");
    snprintf(m->file_name, sizeof(m->file_name), "%s", fname);
    snprintf(m->file_url, sizeof(m->file_url), "%s", furl);
    m->text = (char *)xmalloc(strlen(text) + 1);
    strcpy(m->text, text);
    *out_id = m->id;
}

/* Build the same snapshot payload the chat page expects. */
static char *snapshot(const char *me, uint64_t since) {
    Buf b;
    uint64_t now = tick_ms();
    int i, first;
    Msg *vis[MAX_MSG];
    int nv = 0, start;
    b.p = NULL; b.n = 0; b.cap = 0;

    bs(&b, "{\"server\":{\"name\":");
    bj(&b, g_alias);
    bs(&b, ",\"fingerprint\":"); bj(&b, g_fp);
    bs(&b, ",\"hostname\":"); bj(&b, g_hostname);
    bs(&b, ",\"ips\":[");
    for (i = 0; i < g_nips; i++) {
        if (i) bs(&b, ",");
        bj(&b, g_ips[i]);
    }
    bf(&b, "],\"port\":%d,\"version\":\"" VERSION "\"},\"now\":%llu,\"devices\":[",
       g_port, (unsigned long long)epoch_ms());

    first = 1;
    for (i = 0; i < g_ndev; i++) {
        Dev *d = &g_devs[i];
        if (now - d->seen >= CLIENT_TTL) continue;
        if (!first) bs(&b, ",");
        first = 0;
        bs(&b, "{\"id\":"); bj(&b, d->id);
        bs(&b, ",\"name\":"); bj(&b, d->name);
        bs(&b, ",\"deviceType\":"); bj(&b, d->type);
        bs(&b, ",\"ip\":"); bj(&b, d->ip);
        bs(&b, (now - d->seen < 15000) ? ",\"online\":true" : ",\"online\":false");
        bs(&b, (me && !strcmp(d->id, me)) ? ",\"self\":true}" : ",\"self\":false}");
    }
    bs(&b, "],\"nodes\":[");
    first = 1;
    for (i = 0; i < g_nnode; i++) {
        Node *n = &g_nodes[i];
        if (now - n->seen >= NODE_TTL) continue;
        if (!first) bs(&b, ",");
        first = 0;
        bs(&b, "{\"base\":"); bj(&b, n->base);
        bs(&b, ",\"name\":"); bj(&b, n->name);
        bs(&b, ",\"source\":"); bj(&b, n->source);
        bs(&b, "}");
    }
    bs(&b, "],\"messages\":[");
    for (i = 0; i < g_nmsg; i++) {
        Msg *m = &g_msgs[i];
        if (m->id <= since) continue;
        if (m->to[0] && (!me || (strcmp(m->to, me) && strcmp(m->from, me)))) continue;
        vis[nv++] = m;
    }
    start = nv > MAX_POLL_MSG ? nv - MAX_POLL_MSG : 0;
    for (i = start; i < nv; i++) {
        Msg *m = vis[i];
        if (i > start) bs(&b, ",");
        bf(&b, "{\"id\":%llu,\"from\":", (unsigned long long)m->id);
        bj(&b, m->from);
        bs(&b, ",\"fromName\":"); bj(&b, m->from_name);
        bs(&b, ",\"to\":"); bj(&b, m->to);
        bs(&b, ",\"kind\":"); bj(&b, m->kind);
        bs(&b, ",\"text\":"); bj(&b, m->text);
        bs(&b, ",\"fileName\":"); bj(&b, m->file_name);
        bs(&b, ",\"fileUrl\":"); bj(&b, m->file_url);
        bf(&b, ",\"fileSize\":%llu,\"time\":%llu}",
           (unsigned long long)m->fsize, (unsigned long long)m->time);
    }
    bs(&b, "]}");
    return b.p ? b.p : (char *)xmalloc(1);
}

/* ----------------------------------------------------------------- socket */
static int send_all(sock_t s, const char *p, size_t n) {
    while (n) {
        int chunk = n > 16384 ? 16384 : (int)n;
        int r = send(s, p, chunk, 0);
        if (r <= 0) return 0;
        p += r; n -= (size_t)r;
    }
    return 1;
}
static void set_timeout(sock_t s, int ms) {
#ifdef _WIN32
    DWORD t = (DWORD)ms;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char *)&t, sizeof(t));
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, (const char *)&t, sizeof(t));
#else
    struct timeval t;
    t.tv_sec = ms / 1000; t.tv_usec = (ms % 1000) * 1000;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &t, sizeof(t));
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &t, sizeof(t));
#endif
}
static void set_nonblock(sock_t s, int on) {
#ifdef _WIN32
    u_long v = (u_long)on;
    ioctlsocket(s, FIONBIO, &v);
#else
    int f = fcntl(s, F_GETFL, 0);
    if (f >= 0) fcntl(s, F_SETFL, on ? (f | O_NONBLOCK) : (f & ~O_NONBLOCK));
#endif
}
/* Only the machine running the service may shut it down. Peers on other
   devices have a different source IP, so a stray click cannot kill the hall. */
static int is_local_admin(sock_t s) {
    struct sockaddr_in peer;
    char ip[32];
    int i, hit = 0;
    unsigned long a;
#ifdef _WIN32
    int pl = sizeof(peer);
#else
    socklen_t pl = sizeof(peer);
#endif
    memset(&peer, 0, sizeof(peer));
    if (getpeername(s, (struct sockaddr *)&peer, &pl) != 0) return 0;
    a = (unsigned long)ntohl(peer.sin_addr.s_addr);
    if ((a >> 24) == 127) return 1;         /* 127.0.0.0/8 */
    snprintf(ip, sizeof(ip), "%s", inet_ntoa(peer.sin_addr));
    LOCK();
    for (i = 0; i < g_nips; i++) if (!strcmp(ip, g_ips[i])) { hit = 1; break; }
    UNLOCK();
    return hit;
}
static void send_head(sock_t s, int code, const char *type, uint64_t len) {
    Buf b;
    const char *status = code == 200 ? "OK" : code == 204 ? "No Content"
                       : code == 400 ? "Bad Request" : code == 403 ? "Forbidden"
                       : code == 404 ? "Not Found" : "OK";
    b.p = NULL; b.n = 0; b.cap = 0;
    bf(&b, "HTTP/1.1 %d %s\r\n", code, status);
    bs(&b, "Content-Type: "); bs(&b, type); bs(&b, "\r\n");
    bf(&b, "Content-Length: %llu\r\n", (unsigned long long)len);
    bs(&b, "Access-Control-Allow-Origin: *\r\n"
           "Access-Control-Allow-Headers: Content-Type\r\n"
           "Access-Control-Allow-Methods: GET, POST, PUT, OPTIONS\r\n"
           "Cache-Control: no-store\r\n");
    /* 邀请链接首次带 ?k= 进来后种 Cookie，页面里所有相对路径请求自动带上，
     * 不用改 chat_page.h 里任何一处 JS。403 响应不会走到这里。 */
    if (t_authorized && g_token[0])
        bf(&b, "Set-Cookie: mc=%s; Path=/; Max-Age=31536000; SameSite=Lax\r\n", g_token);
    bs(&b, "Connection: close\r\n\r\n");
    send_all(s, b.p, b.n);
    free(b.p);
}
static void respond(sock_t s, int code, const char *type, const char *body, size_t len) {
    send_head(s, code, type, (uint64_t)len);
    if (len) send_all(s, body, len);
}
static void respond_json_owned(sock_t s, int code, char *body) {
    respond(s, code, "application/json; charset=utf-8", body ? body : "", body ? strlen(body) : 0);
    free(body);
}

/* ------------------------------------------------------------- discovery */
/* Caller holds g_lock. Rebuilt periodically: Mesh roaming changes the IP and
   a stale address in the broadcast would keep peers pointed at a dead host. */
static void build_hello(Buf *b) {
    int i;
    b->p = NULL; b->n = 0; b->cap = 0;
    bs(b, "{\"version\":\"" VERSION "\",\"fingerprint\":");
    bj(b, g_fp);
    bs(b, ",\"name\":"); bj(b, g_alias);
    bs(b, ",\"hostname\":"); bj(b, g_hostname);
    bf(b, ",\"port\":%d,\"ips\":[", g_port);
    for (i = 0; i < g_nips; i++) { if (i) bs(b, ","); bj(b, g_ips[i]); }
    bs(b, "]}");
}
static THREAD_RET discovery_thread(void *unused) {
    sock_t s;
    struct sockaddr_in a;
    uint64_t last = 0;
    Buf hello;
    (void)unused;
    hello.p = NULL; hello.n = 0; hello.cap = 0;
    LOCK(); build_hello(&hello); UNLOCK();

    s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s == BAD_SOCKET) return 0;
    {
        int reuse = 1;
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, (const char *)&reuse, sizeof(reuse));
    }
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_port = htons(UDP_PORT);
    a.sin_addr.s_addr = htonl(INADDR_ANY);
#ifndef _WIN32
    if (bind(s, (struct sockaddr *)&a, sizeof(a)) != 0) { /* still broadcast */ }
#else
    bind(s, (struct sockaddr *)&a, sizeof(a));
#endif
#ifndef _WIN32
    set_nonblock(s, 1);
#endif
    for (;;) {
        fd_set r;
        struct timeval tv;
        char buf[4096];
        int i;
        tv.tv_sec = 0; tv.tv_usec = 200000;
        FD_ZERO(&r); FD_SET(s, &r);
        if (select((int)s + 1, &r, NULL, NULL, &tv) > 0 && FD_ISSET(s, &r)) {
            struct sockaddr_in from;
#ifdef _WIN32
            int fl = sizeof(from);
#else
            socklen_t fl = sizeof(from);
#endif
            int n = (int)recvfrom(s, buf, sizeof(buf) - 1, 0, (struct sockaddr *)&from, &fl);
            if (n > 0) {
                char fp[64], name[64], host[64];
                uint64_t port;
                buf[n] = 0;
                if (strstr(buf, "\"" VERSION "\"")) {
                    json_str(buf, "fingerprint", fp, sizeof(fp));
                    json_str(buf, "name", name, sizeof(name));
                    port = json_num(buf, "port", (uint64_t)g_port);
                    host[0] = 0;
                    /* the advertised first LAN ip is more trustworthy than the
                       UDP source address (which may be a VPN/TUN adapter) */
                    {
                        const char *p = strchr(buf, '[');
                        if (p) {
                            char *q = strchr(p + 1, '"');
                            char *e = q ? strchr(q + 1, '"') : NULL;
                            if (q && e && (size_t)(e - q - 1) < sizeof(host)) {
                                memcpy(host, q + 1, (size_t)(e - q - 1));
                                host[e - q - 1] = 0;
                            }
                        }
                    }
                    if (!host[0]) snprintf(host, sizeof(host), "%s", inet_ntoa(from.sin_addr));
                    if (strcmp(fp, g_fp)) {
                        char base[128];
                        snprintf(base, sizeof(base), "http://%s:%d", host, (int)port);
                        LOCK(); register_node(fp, base, name, "udp"); UNLOCK();
                    }
                }
            }
        }
        if (!last || tick_ms() - last >= 4000) {
            sock_t out = socket(AF_INET, SOCK_DGRAM, 0);
            last = tick_ms();
            free(hello.p);
            LOCK();
            g_nips = 0;
            collect_interfaces();
            build_hello(&hello);
            UNLOCK();
            if (out != BAD_SOCKET) {
                int on = 1;
                setsockopt(out, SOL_SOCKET, SO_BROADCAST, (const char *)&on, sizeof(on));
                memset(&a, 0, sizeof(a));
                a.sin_family = AF_INET; a.sin_port = htons(UDP_PORT);
                a.sin_addr.s_addr = htonl(INADDR_BROADCAST);
                sendto(out, hello.p, (int)hello.n, 0, (struct sockaddr *)&a, sizeof(a));
                for (i = 0; i < g_nips; i++) {
                    a.sin_addr.s_addr = inet_addr(g_bcast[i]);
                    if (a.sin_addr.s_addr != INADDR_NONE)
                        sendto(out, hello.p, (int)hello.n, 0, (struct sockaddr *)&a, sizeof(a));
                }
                CLOSESOCK(out);
            }
        }
    }
    return 0;
}

/* ------------------------------------------------------------------- scan */
typedef struct { char ip[16], name[64], fp[64]; int found; } ScanRes;
static ScanRes g_scan[255];
static char g_scan_prefix[16];
static int g_scan_port;

static int probe_one(const char *ip, int port, char *name, size_t ncap, char *fp, size_t fcap) {
    sock_t s;
    struct sockaddr_in a;
    fd_set w, e;
    struct timeval tv;
    int connected = 0, found = 0;
    s = socket(AF_INET, SOCK_STREAM, 0);
    if (s == BAD_SOCKET) return 0;
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_port = htons((unsigned short)port);
    a.sin_addr.s_addr = inet_addr(ip);
    set_nonblock(s, 1);
    connect(s, (struct sockaddr *)&a, sizeof(a));
    FD_ZERO(&w); FD_ZERO(&e);
    FD_SET(s, &w); FD_SET(s, &e);
    tv.tv_sec = 0; tv.tv_usec = 300000;
    if (select((int)s + 1, NULL, &w, &e, &tv) > 0 && FD_ISSET(s, &w) && !FD_ISSET(s, &e)) {
        int err = 0;
#ifdef _WIN32
        int el = sizeof(err);
#else
        socklen_t el = sizeof(err);
#endif
        getsockopt(s, SOL_SOCKET, SO_ERROR, (char *)&err, &el);
        connected = (err == 0);
    }
    if (connected) {
        char req[256], buf[4096];
        int n = 0;
        set_nonblock(s, 0);
        set_timeout(s, 350);
        snprintf(req, sizeof(req),
                 "GET /__chat__/ping HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", ip);
        if (send_all(s, req, strlen(req))) {
            while (n < (int)sizeof(buf) - 1) {
                int r = (int)recv(s, buf + n, (int)sizeof(buf) - 1 - n, 0);
                if (r <= 0) break;
                n += r; buf[n] = 0;
                if (strstr(buf, "\r\n\r\n")) break;
            }
            if (n > 0) {
                char *body = strstr(buf, "\r\n\r\n");
                if (body) {
                    body += 4;
                    if (strstr(body, "\"" VERSION "\"")) {
                        json_str(body, "name", name, ncap);
                        json_str(body, "fingerprint", fp, fcap);
                        found = 1;
                    }
                }
            }
        }
    }
    CLOSESOCK(s);
    return found;
}
static THREAD_RET scan_worker(void *arg) {
    int tid = (int)(intptr_t)arg, i;
    for (i = tid + 1; i <= 254; i += SCAN_THREADS) {
        char ip[16];
        ScanRes *r = &g_scan[i - 1];
        snprintf(ip, sizeof(ip), "%s.%d", g_scan_prefix, i);
        snprintf(r->ip, sizeof(r->ip), "%s", ip);
        r->found = probe_one(ip, g_scan_port, r->name, sizeof(r->name), r->fp, sizeof(r->fp));
    }
    return 0;
}

/* ------------------------------------------------------------ file serving */
static void serve_file(sock_t s, const char *encoded) {
    char raw[512], safe[512], path[1200];
    FILE *f;
    uint64_t size;
    url_decode(encoded, raw, sizeof(raw));
    sanitize(raw, safe, sizeof(safe));
    snprintf(path, sizeof(path), "%s/Chat-Files/%s", g_share, safe);
    f = fopen(path, "rb");
    if (!f) { const char *m = "file not found"; respond(s, 404, "text/plain; charset=utf-8", m, strlen(m)); return; }
#ifdef _WIN32
    _fseeki64(f, 0, SEEK_END); size = (uint64_t)_ftelli64(f); _fseeki64(f, 0, SEEK_SET);
#else
    fseeko(f, 0, SEEK_END); size = (uint64_t)ftello(f); fseeko(f, 0, SEEK_SET);
#endif
    send_head(s, 200, "application/octet-stream", size);
    {
        char chunk[8192];
        size_t n;
        while ((n = fread(chunk, 1, sizeof(chunk), f)) > 0) {
            if (!send_all(s, chunk, n)) break;
        }
    }
    fclose(f);
}
static void serve_file_list(sock_t s) {
    Buf b;
    b.p = NULL; b.n = 0; b.cap = 0;
    bs(&b, "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
           "<title>接收的文件</title><style>body{font:15px system-ui;background:#0e1117;color:#eef2fa;padding:24px}"
           "a{color:#8aa5ff;display:block;padding:9px 0;text-decoration:none;word-break:break-all}"
           "h2{font-weight:600}</style><h2>接收的文件</h2>");
#ifdef _WIN32
    {
        WIN32_FIND_DATAA fd;
        char pattern[1200];
        HANDLE h;
        snprintf(pattern, sizeof(pattern), "%s/Chat-Files/*", g_share);
        h = FindFirstFileA(pattern, &fd);
        if (h != INVALID_HANDLE_VALUE) {
            do {
                char enc[1600], shown[600];
                if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
                url_encode(fd.cFileName, enc, sizeof(enc));
                bs(&b, "<a href=\"/Chat-Files/"); bs(&b, enc); bs(&b, "\">");
                {
                    char *o = shown;
                    const char *p = fd.cFileName;
                    for (; *p && (size_t)(o - shown) < sizeof(shown) - 8; p++) {
                        if (*p == '&') { memcpy(o, "&amp;", 5); o += 5; }
                        else if (*p == '<') { memcpy(o, "&lt;", 4); o += 4; }
                        else *o++ = *p;
                    }
                    *o = 0;
                }
                bs(&b, shown); bs(&b, "</a>");
            } while (FindNextFileA(h, &fd));
            FindClose(h);
        }
    }
#else
    {
        char dir[1200];
        DIR *d;
        struct dirent *de;
        snprintf(dir, sizeof(dir), "%s/Chat-Files", g_share);
        d = opendir(dir);
        if (d) {
            while ((de = readdir(d)) != NULL) {
                char enc[1600];
                if (de->d_name[0] == '.') continue;
                url_encode(de->d_name, enc, sizeof(enc));
                bs(&b, "<a href=\"/Chat-Files/"); bs(&b, enc); bs(&b, "\">"); bs(&b, de->d_name); bs(&b, "</a>");
            }
            closedir(d);
        }
    }
#endif
    bs(&b, "<p style='color:#8e9bb2;font-size:13px'><a href='/'>返回聊天</a></p>");
    respond(s, 200, "text/html; charset=utf-8", b.p, b.n);
    free(b.p);
}

/* --------------------------------------------------------------- requests */
static char *read_body(sock_t s, uint64_t need, const char *initial, size_t have, size_t *outlen) {
    char *buf;
    size_t got = 0;
    if (need > MAX_BODY) need = MAX_BODY;
    buf = (char *)xmalloc((size_t)need + 1);
    if (have && initial) {
        size_t n = have < (size_t)need ? have : (size_t)need;
        memcpy(buf, initial, n);
        got = n;
    }
    while (got < (size_t)need) {
        int r = (int)recv(s, buf + got, (int)((size_t)need - got), 0);
        if (r <= 0) break;
        got += (size_t)r;
    }
    buf[got] = 0;
    if (outlen) *outlen = got;
    return buf;
}
static uint64_t header_num(const char *hdr, const char *key) {
    char pat[64];
    const char *p;
    snprintf(pat, sizeof(pat), "%s:", key);
    p = hdr;
    while (*p) {
        const char *line = p;
        const char *e = strchr(p, '\n');
        size_t len = e ? (size_t)(e - p) : strlen(p);
        if (len >= strlen(pat) && !_strnicmp(line, pat, strlen(pat))) {
            const char *v = line + strlen(pat);
            while (*v == ' ' || *v == '\t') v++;
            return strtoull(v, NULL, 10);
        }
        if (!e) break;
        p = e + 1;
    }
    return 0;
}
static const char *header_value(const char *hdr, const char *key, char *out, size_t cap) {
    char pat[64];
    const char *p;
    size_t n = 0;
    out[0] = 0;
    snprintf(pat, sizeof(pat), "%s:", key);
    p = hdr;
    while (*p) {
        const char *line = p, *e = strchr(p, '\n');
        size_t len = e ? (size_t)(e - p) : strlen(p);
        if (len >= strlen(pat) && !_strnicmp(line, pat, strlen(pat))) {
            const char *v = line + strlen(pat);
            while (*v == ' ' || *v == '\t') v++;
            while (*v && *v != '\r' && *v != '\n' && n + 1 < cap) out[n++] = *v++;
            out[n] = 0;
            return out;
        }
        if (!e) break;
        p = e + 1;
    }
    return NULL;
}

static void handle_upload(sock_t s, const char *q, const char *initial, size_t have, uint64_t need) {
    char name_q[512], safe[512], stored[600], path[1200], enc[1800], url[2000];
    FILE *f;
    uint64_t written = 0;
    Buf b;
    b.p = NULL; b.n = 0; b.cap = 0;
    query_get(q, "name", name_q, sizeof(name_q));
    sanitize(name_q[0] ? name_q : "file.bin", safe, sizeof(safe));
    snprintf(stored, sizeof(stored), "%llu__%s", (unsigned long long)epoch_ms(), safe);
    snprintf(path, sizeof(path), "%s/Chat-Files/%s", g_share, stored);
    f = fopen(path, "wb");
    if (!f) { const char *m = "cannot create file"; respond(s, 400, "text/plain", m, strlen(m)); free(b.p); return; }
    if (have && initial) {
        size_t n = have < (size_t)need ? have : (size_t)need;
        if (n) fwrite(initial, 1, n, f);
        written = n;
    }
    while (written < need) {
        char chunk[8192];
        uint64_t left = need - written;
        int want = left > sizeof(chunk) ? (int)sizeof(chunk) : (int)left;
        int r = (int)recv(s, chunk, want, 0);
        if (r <= 0) break;
        fwrite(chunk, 1, (size_t)r, f);
        written += (uint64_t)r;
    }
    fclose(f);
    url_encode(stored, enc, sizeof(enc));
    snprintf(url, sizeof(url), "/Chat-Files/%s", enc);
    bs(&b, "{\"name\":"); bj(&b, safe);
    bs(&b, ",\"url\":"); bj(&b, url);
    bf(&b, ",\"size\":%llu}", (unsigned long long)written);
    respond_json_owned(s, 200, b.p);
    debugf("[minichat] saved %s (%llu bytes)\n", safe, (unsigned long long)written);
}

static void handle_scan(sock_t s, const char *body) {
    char subnet[64], prefix[16];
    int port, a, b, c, i, first = 1;
    thread_handle th[SCAN_THREADS];
    Buf out;
    out.p = NULL; out.n = 0; out.cap = 0;
    json_str(body, "subnet", subnet, sizeof(subnet));
    port = (int)json_num(body, "port", 0);
    if (!port) port = g_port;
    {
        char *p = subnet;
        while (*p == ' ') p++;
        snprintf(prefix, sizeof(prefix), "%s", p);
        for (i = 0; prefix[i]; i++)
            if (prefix[i] == '/' || prefix[i] == ' ' || prefix[i] == '\r' || prefix[i] == '\n') { prefix[i] = 0; break; }
        /* trim trailing dots */
        for (i = (int)strlen(prefix) - 1; i >= 0 && prefix[i] == '.'; i--) prefix[i] = 0;
    }
    if (sscanf(prefix, "%d.%d.%d", &a, &b, &c) != 3 ||
        a < 1 || a > 254 || b < 0 || b > 255 || c < 0 || c > 255 ||
        a == 127 || (a == 169 && b == 254) || a == 0) {
        bs(&out, "{\"found\":[],\"error\":\"网段格式应为 192.168.1\"}");
        respond_json_owned(s, 400, out.p);
        return;
    }
    snprintf(g_scan_prefix, sizeof(g_scan_prefix), "%d.%d.%d", a, b, c);
    g_scan_port = port;
    memset(g_scan, 0, sizeof(g_scan));
    for (i = 0; i < SCAN_THREADS; i++) {
        th[i] = 0;
        {
#ifdef _WIN32
            th[i] = (thread_handle)_beginthreadex(NULL, 0, scan_worker, (void *)(intptr_t)i, 0, NULL);
#else
            if (pthread_create(&th[i], NULL, scan_worker, (void *)(intptr_t)i) != 0) th[i] = 0;
#endif
        }
    }
#ifdef _WIN32
    {
        int count = 0;
        HANDLE hs[SCAN_THREADS];
        for (i = 0; i < SCAN_THREADS; i++) if (th[i]) hs[count++] = (HANDLE)th[i];
        if (count) WaitForMultipleObjects((DWORD)count, hs, TRUE, INFINITE);
        for (i = 0; i < SCAN_THREADS; i++) if (th[i]) CloseHandle((HANDLE)th[i]);
    }
#else
    for (i = 0; i < SCAN_THREADS; i++) if (th[i]) pthread_join(th[i], NULL);
#endif
    bs(&out, "{\"found\":[");
    for (i = 0; i < 254; i++) {
        char base[128];
        ScanRes *r = &g_scan[i];
        if (!r->found || !strcmp(r->fp, g_fp)) continue; /* never list ourselves */
        snprintf(base, sizeof(base), "http://%s:%d", r->ip, port);
        LOCK(); register_node(r->fp, base, r->name, "scan"); UNLOCK();
        if (!first) bs(&out, ",");
        first = 0;
        bs(&out, "{\"base\":"); bj(&out, base);
        bs(&out, ",\"name\":"); bj(&out, r->name);
        bs(&out, ",\"ip\":"); bj(&out, r->ip);
        bs(&out, "}");
    }
    bs(&out, "]}");
    respond_json_owned(s, 200, out.p);
}

/* --------------------------------------------------- LAN 邀请口令鉴权 */
static int is_loopback_peer(sock_t s) {
    struct sockaddr_in peer;
#ifdef _WIN32
    int pl = sizeof(peer);
#else
    socklen_t pl = sizeof(peer);
#endif
    if (getpeername(s, (struct sockaddr *)&peer, &pl) != 0) return 0;
    return (unsigned long)(ntohl(peer.sin_addr.s_addr) >> 24) == 127UL;
}

/* 本机一律放行；局域网来源必须持有邀请链接里的 token。 */
static int lan_authorized(sock_t s, const char *hdr, const char *q) {
    char v[1024], k[128], *p;
    size_t n;
    if (is_loopback_peer(s)) return 1;
    if (!g_token[0]) return 0;
    query_get(q, "k", k, sizeof(k));
    if (k[0] && !strcmp(k, g_token)) return 1;
    header_value(hdr, "X-MC-Token", v, sizeof(v));
    if (v[0] && !strcmp(v, g_token)) return 1;
    header_value(hdr, "Cookie", v, sizeof(v));
    p = strstr(v, "mc=");
    if (p) {
        n = strlen(g_token);
        if (!strncmp(p + 3, g_token, n) &&
            (p[3 + n] == 0 || p[3 + n] == ';' || p[3 + n] == ' ')) return 1;
    }
    return 0;
}

static int is_root_path(const char *path) {
    return !strcmp(path, "/") || !strcmp(path, "/index.html") ||
           !strcmp(path, "/__chat__") || !strcmp(path, "/__chat__/");
}

/* 局域网设备扫码/输网址进来时看到的是配对页，不是冷冰冰的 403。
 * 地址保持 http://<ip>:<port>/__chat__/ 这么短，人只要输 6 位配对码。 */
static void serve_pair_page(sock_t s, int err) {
    Buf b;
    b.p = NULL; b.n = 0; b.cap = 0;
    bs(&b, "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
           "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
           "<title>沧海局域网大厅 · 配对</title><style>"
           ":root{color-scheme:dark}*{box-sizing:border-box}"
           "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
           "background:#0b1622;color:#e8f1fa;font:15px/1.7 system-ui,-apple-system,sans-serif;padding:20px}"
           ".card{width:100%;max-width:380px;background:#122438;border:1px solid #23415d;"
           "border-radius:16px;padding:28px 24px;box-shadow:0 12px 40px rgba(0,0,0,.35)}"
           "h1{margin:0 0 8px;font-size:19px}p{margin:0 0 18px;color:#9fb6ca;font-size:13px}"
           "input{width:100%;padding:14px;font:700 26px/1 ui-monospace,Consolas,monospace;"
           "letter-spacing:8px;text-align:center;color:#e8f1fa;background:#0b1b2b;"
           "border:1px solid #2f5c82;border-radius:10px;outline:none}"
           "input:focus{border-color:#58a6e0}"
           "button{width:100%;margin-top:14px;padding:13px;border:0;border-radius:10px;"
           "background:#2b7db8;color:#fff;font-size:15px;font-weight:700;cursor:pointer}"
           "button:hover{background:#3590d0}"
           ".err{margin:14px 0 0;color:#fca5a5;text-align:center;font-size:13px}"
           ".foot{margin:20px 0 0;text-align:center;color:#63809a;font-size:11px}"
           "</style></head><body><div class=\"card\">"
           "<h1>连接沧海大厅</h1>"
           "<p>输入手机或大屏「局域网大厅」里显示的 6 位配对码。</p>"
           "<form method=\"post\" action=\"/__chat__/pair\">"
           "<input name=\"pin\" type=\"text\" inputmode=\"numeric\" pattern=\"[0-9]*\" "
           "maxlength=\"6\" autocomplete=\"one-time-code\" placeholder=\"······\" autofocus required>"
           "<button type=\"submit\">进入大厅</button></form>");
    if (err) bs(&b, "<p class=\"err\">配对码不对，再试一次</p>");
    bs(&b, "<p class=\"foot\">配对码只在这台设备上显示，不会写进网址</p>"
           "</div></body></html>");
    respond(s, 200, "text/html; charset=utf-8", b.p, b.n);
    free(b.p);
}

/* 配对成功：种下长随机 token 的 Cookie，页面立刻跳回大厅。
 * 之后 chat_page 里所有相对路径请求自动带 Cookie，不用改一行前端 JS。 */
static void pair_ok(sock_t s) {
    const char *html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                       "<meta http-equiv=\"refresh\" content=\"0;url=/__chat__/\">"
                       "<title>配对成功</title></head><body style=\"background:#0b1622;color:#e8f1fa;"
                       "font:15px system-ui;padding:40px;text-align:center\">"
                       "配对成功，正在进入大厅…</body></html>";
    t_authorized = 1;   /* send_head 据此下发 Set-Cookie */
    respond(s, 200, "text/html; charset=utf-8", html, strlen(html));
}

static void handle_pair_post(sock_t s, uint64_t need, const char *initial, size_t have) {
    char *b = read_body(s, need, initial, have, NULL);
    char pin[32];
    if (!b) { serve_pair_page(s, 1); return; }
    query_get(b, "pin", pin, sizeof(pin));
    free(b);
    if (g_pin[0] && pin[0] && !strcmp(pin, g_pin)) {
        pair_ok(s);
        return;
    }
    g_pair_fails++;
#ifdef _WIN32
    Sleep(400);
#else
    usleep(400000);   /* 6 位码简单限速，防连续猜 */
#endif
    serve_pair_page(s, 1);
}

static THREAD_RET client_thread(void *arg) {
    sock_t s = (sock_t)(intptr_t)arg;
    char hdr[65536];
    size_t hn = 0, hend = 0;
    char method[16], path[2048], query[2048];
    char body[4096];
    const char *q;
    uint64_t need;
    int found_end = 0;
    set_timeout(s, 20000);
    while (hn < sizeof(hdr) - 1) {
        int r = (int)recv(s, hdr + hn, (int)(sizeof(hdr) - 1 - hn), 0);
        char *e;
        if (r <= 0) break;
        hn += (size_t)r; hdr[hn] = 0;
        e = strstr(hdr, "\r\n\r\n");
        if (e) { hend = (size_t)(e + 4 - hdr); found_end = 1; break; }
    }
    if (!found_end) { CLOSESOCK(s); return 0; }
    {
        char line[4096];
        size_t n = strcspn(hdr, "\r\n");
        if (n > sizeof(line) - 1) n = sizeof(line) - 1;
        memcpy(line, hdr, n); line[n] = 0;
        {
            char *p = line, *sp = strchr(line, ' ');
            method[0] = 0; path[0] = 0; query[0] = 0;
            if (sp) {
                char *p2;
                *sp = 0;
                snprintf(method, sizeof(method), "%s", p);
                p = sp + 1;
                p2 = strchr(p, ' ');
                if (p2) *p2 = 0;
                {
                    char *qm = strchr(p, '?');
                    if (qm) { *qm = 0; snprintf(query, sizeof(query), "%s", qm + 1); }
                    snprintf(path, sizeof(path), "%s", p);
                }
            }
        }
    }
    q = query[0] ? query : NULL;
    need = header_num(hdr, "Content-Length");
    (void)body;

#ifdef __ANDROID__
    /* 局域网来源一律先验邀请口令。OPTIONS 预检只回空响应、不种 Cookie，
     * 因此拿不到口令的第三方最多知道端口开着，读不到任何数据。 */
    t_authorized = 0;
    if (STRICMP(method, "OPTIONS")) {
        int auth = lan_authorized(s, hdr, q);
        /* /__chat__/ping 是跨网段扫描的探针，只回版本/名称/IP，不含任何聊天数据。
         * 必须允许未配对设备探测，否则聊天页「扫描对方网段」在两台沧海之间永远扫不到。
         * 这里不置 t_authorized，所以探测响应不会下发 Cookie、也不会拿到 token。 */
        int public_ping = (!STRICMP(method, "GET") && !strcmp(path, "/__chat__/ping"));
        if (!auth && !public_ping) {
            if (!STRICMP(method, "POST") && !strcmp(path, "/__chat__/pair")) {
                handle_pair_post(s, need, hdr + hend, hn - hend);
            } else if (!STRICMP(method, "GET") &&
                       (is_root_path(path) || !strcmp(path, "/__chat__/pair"))) {
                serve_pair_page(s, 0);
            } else {
                const char *msg = "Forbidden: 请先在本机大厅完成配对";
                respond(s, 403, "text/plain; charset=utf-8", msg, strlen(msg));
            }
            CLOSESOCK(s);
            return 0;
        }
        t_authorized = auth ? 1 : 0;
    }
#endif

    if (!STRICMP(method, "OPTIONS")) {
        respond(s, 204, "text/plain", "", 0);
    } else if (!STRICMP(method, "GET") && is_root_path(path)) {
        if (q && strstr(q, "files")) serve_file_list(s);
        else respond(s, 200, "text/html; charset=utf-8", (const char *)CHAT_HTML, CHAT_HTML_LEN);
    } else if (!STRICMP(method, "GET") && !strcmp(path, "/favicon.ico")) {
        respond(s, 204, "image/x-icon", "", 0);
    } else if (!STRICMP(method, "GET") && !strcmp(path, "/__chat__/ping")) {
        Buf b; b.p = NULL; b.n = 0; b.cap = 0;
        int i;
        LOCK();
        bs(&b, "{\"version\":\"" VERSION "\",\"impl\":\"minichat\",\"name\":"); bj(&b, g_alias);
        bs(&b, ",\"fingerprint\":"); bj(&b, g_fp);
        bs(&b, ",\"hostname\":"); bj(&b, g_hostname);
        bs(&b, ",\"ips\":[");
        for (i = 0; i < g_nips; i++) { if (i) bs(&b, ","); bj(&b, g_ips[i]); }
        bf(&b, "],\"port\":%d}", g_port);
        UNLOCK();
        respond_json_owned(s, 200, b.p);
    } else if (!STRICMP(method, "GET") && !strcmp(path, "/__chat__/poll")) {
        char me[128], since[32];
        query_get(q, "me", me, sizeof(me));
        query_get(q, "since", since, sizeof(since));
        LOCK();
        if (me[0]) {
            int i;
            for (i = 0; i < g_ndev; i++) if (!strcmp(g_devs[i].id, me)) g_devs[i].seen = tick_ms();
        }
        respond_json_owned(s, 200, snapshot(me, strtoull(since, NULL, 10)));
        UNLOCK();
    } else if (!STRICMP(method, "POST") && !strcmp(path, "/__chat__/hello")) {
        char *b = read_body(s, need, hdr + hend, hn - hend, NULL);
        char id[64], name[64], type[16], ip[64];
        header_value(hdr, "X-Forwarded-For", ip, sizeof(ip));
        if (!ip[0]) {
            struct sockaddr_in peer;
#ifdef _WIN32
            int pl = sizeof(peer);
#else
            socklen_t pl = sizeof(peer);
#endif
            if (getpeername(s, (struct sockaddr *)&peer, &pl) == 0)
                snprintf(ip, sizeof(ip), "%s", inet_ntoa(peer.sin_addr));
            else snprintf(ip, sizeof(ip), "unknown");
        }
        json_str(b, "id", id, sizeof(id));
        json_str(b, "name", name, sizeof(name));
        json_str(b, "deviceType", type, sizeof(type));
        if (!id[0]) snprintf(id, sizeof(id), "anon-%llu", (unsigned long long)tick_ms());
        LOCK();
        register_dev(id, name, type, ip);
        respond_json_owned(s, 200, snapshot(id, 0));
        UNLOCK();
        free(b);
    } else if (!STRICMP(method, "POST") && !strcmp(path, "/__chat__/send")) {
        char *b = read_body(s, need, hdr + hend, hn - hend, NULL);
        char from[64], name[64], to[64], kind[16], fname[256], furl[512], *text;
        uint64_t fsize, id = 0;
        size_t tlen = strlen(b) + 1;
        json_str(b, "from", from, sizeof(from));
        json_str(b, "name", name, sizeof(name));
        json_str(b, "to", to, sizeof(to));
        json_str(b, "kind", kind, sizeof(kind));
        json_str(b, "fileName", fname, sizeof(fname));
        json_str(b, "fileUrl", furl, sizeof(furl));
        fsize = json_num(b, "fileSize", 0);
        text = (char *)xmalloc(tlen < 16 ? 16 : tlen);
        json_str(b, "text", text, tlen < 16 ? 16 : tlen);
        if (text[0] || furl[0]) {
            Buf out; out.p = NULL; out.n = 0; out.cap = 0;
            LOCK(); add_message(from, name, to, kind, text, fname, furl, fsize, &id); UNLOCK();
            bf(&out, "{\"id\":%llu}", (unsigned long long)id);
            respond_json_owned(s, 200, out.p);
        } else {
            const char *m = "empty message";
            respond(s, 400, "text/plain", m, strlen(m));
        }
        free(text); free(b);
    } else if (!STRICMP(method, "PUT") && !strcmp(path, "/__chat__/upload")) {
        handle_upload(s, q, hdr + hend, hn - hend, need);
    } else if (!STRICMP(method, "GET") && !strncmp(path, "/Chat-Files/", 12)) {
        serve_file(s, path + 12);
    } else if (!STRICMP(method, "POST") && !strcmp(path, "/__chat__/scan")) {
        char *b = read_body(s, need, hdr + hend, hn - hend, NULL);
        handle_scan(s, b);
        free(b);
    } else if (!STRICMP(method, "POST") && !strcmp(path, "/__chat__/quit")) {
        if (!is_local_admin(s)) {
            const char *m = "forbidden";
            respond(s, 403, "text/plain", m, strlen(m));
        } else {
            const char *m = "{\"ok\":true}";
            respond(s, 200, "application/json; charset=utf-8", m, strlen(m));
#ifdef _WIN32
            Sleep(300); exit(0);
#else
            usleep(300000); _exit(0);
#endif
        }
    } else {
        const char *m = "not found";
        respond(s, 404, "text/plain; charset=utf-8", m, strlen(m));
    }
    CLOSESOCK(s);
    return 0;
}

/* ------------------------------------------------------------------- boot */
static void make_dir(const char *p) {
#ifdef _WIN32
    CreateDirectoryA(p, NULL);
#else
    mkdir(p, 0777);
#endif
}
static void parse_args(int argc, char **argv) {
    int i;
    for (i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--port") && i + 1 < argc) g_port = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--no-browser")) g_no_browser = 1;
        else if (!strcmp(argv[i], "--console")) g_console = 1;
        else if (!strncmp(argv[i], "--dir", 5) && i + 1 < argc) snprintf(g_share, sizeof(g_share), "%s", argv[++i]);
        else if (!strcmp(argv[i], "--token") && i + 1 < argc) snprintf(g_token, sizeof(g_token), "%s", argv[++i]);
        else if (!strcmp(argv[i], "--pin") && i + 1 < argc) snprintf(g_pin, sizeof(g_pin), "%s", argv[++i]);
    }
    if (g_port < 1 || g_port > 65535) g_port = DEFAULT_PORT;
}
static void init_paths(void) {
    char sub[1200];
    if (!g_share[0]) {
#ifdef _WIN32
        char exe[1024];
        GetModuleFileNameA(NULL, exe, sizeof(exe));
        {
            char *p = strrchr(exe, '\\');
            char *q = strrchr(exe, '/');
            if (q && (!p || q > p)) p = q;
            if (p) *p = 0;
        }
        snprintf(g_share, sizeof(g_share), "%s/share", exe);
#else
        if (!getcwd(g_share, sizeof(g_share))) snprintf(g_share, sizeof(g_share), ".");
        strncat(g_share, "/share", sizeof(g_share) - strlen(g_share) - 1);
#endif
    }
    make_dir(g_share);
    snprintf(sub, sizeof(sub), "%s/Chat-Files", g_share);
    make_dir(sub);
}
static void init_identity(void) {
    char host[128], path[1200], line[128];
    FILE *f;
    host[0] = 0;
    if (gethostname(host, sizeof(host)) != 0 || !host[0])
        snprintf(host, sizeof(host), "minichat");
    snprintf(g_hostname, sizeof(g_hostname), "%s", host);
    snprintf(g_alias, sizeof(g_alias), "%s 的大厅", host);

    /* A stable instance id survives restarts and IP changes. Peers remember
       this fingerprint, not an IP, so a Mesh handover does not split the hall. */
    snprintf(path, sizeof(path), "%s/instance.id", g_share);
    f = fopen(path, "r");
    if (f) {
        if (fgets(line, sizeof(line), f)) {
            size_t n = strcspn(line, "\r\n");
            line[n] = 0;
            if (n >= 8 && n < sizeof(g_fp)) snprintf(g_fp, sizeof(g_fp), "%s", line);
        }
        fclose(f);
    }
    if (!g_fp[0]) {
        snprintf(g_fp, sizeof(g_fp), "%08x%08x",
                 (unsigned)(uintptr_t)time(NULL),
#ifdef _WIN32
                 (unsigned)GetCurrentProcessId()
#else
                 (unsigned)getpid()
#endif
        );
        f = fopen(path, "w");
        if (f) { fprintf(f, "%s\n", g_fp); fclose(f); }
    }
}

/* ------------------------------------------------------- single instance */
/* A double click used to start a second service on 53318 with its own
   separate hall. One global lock per machine prevents that. */
static int acquire_single_instance(void) {
#ifdef _WIN32
    HANDLE h = CreateMutexA(NULL, TRUE, "Local\\minichat-single-instance-v1");
    if (!h) return 1;                      /* fail open, never lock the user out */
    if (GetLastError() == ERROR_ALREADY_EXISTS) { CloseHandle(h); return 0; }
    g_single_mutex = h;                    /* intentionally never released */
    return 1;
#else
    char path[1200];
    struct flock fl;
    int fd;
    snprintf(path, sizeof(path), "%s/instance.lock", g_share);
    fd = open(path, O_CREAT | O_RDWR, 0666);
    if (fd < 0) return 1;
    memset(&fl, 0, sizeof(fl));
    fl.l_type = F_WRLCK;
    fl.l_whence = SEEK_SET;
    if (fcntl(fd, F_SETLK, &fl) != 0) { close(fd); return 0; }
    g_single_lock_fd = fd;                 /* intentionally never released */
    return 1;
#endif
}
static int read_running_port(void) {
    char path[1200];
    FILE *f;
    int p = 0;
    snprintf(path, sizeof(path), "%s/lan.port", g_share);
    f = fopen(path, "r");
    if (f) { if (fscanf(f, "%d", &p) != 1) p = 0; fclose(f); }
    return (p > 0 && p <= 65535) ? p : DEFAULT_PORT;
}
static void write_running_port(void) {
    char path[1200];
    FILE *f;
    snprintf(path, sizeof(path), "%s/lan.port", g_share);
    f = fopen(path, "w");
    if (f) { fprintf(f, "%d\n", g_port); fclose(f); }
}

/* thread spawner */
#ifdef _WIN32
typedef unsigned (__stdcall *thread_fn_t)(void *);
#else
typedef void *(*thread_fn_t)(void *);
#endif
static thread_handle start_thread_fn(thread_fn_t fn, void *arg, int detach, int *ok) {
    thread_handle h = 0;
    *ok = 0;
#ifdef _WIN32
    h = (thread_handle)_beginthreadex(NULL, 0, fn, arg, 0, NULL);
    if (h) { *ok = 1; if (detach) CloseHandle((HANDLE)h); }
#else
    if (pthread_create(&h, NULL, fn, arg) == 0) {
        *ok = 1;
        if (detach) pthread_detach(h);
    }
#endif
    return h;
}

#ifdef _WIN32
int WINAPI WinMain(HINSTANCE a, HINSTANCE b, LPSTR c, int d) {
    (void)a; (void)b; (void)c; (void)d;
    parse_args(__argc, __argv);
#else
int minichat_main(int argc, char **argv) {
    parse_args(argc, argv);
#endif
#ifdef _WIN32
    {
        WSADATA wsa;
        if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return 1;
    }
    if (g_console) {
        AllocConsole();
        freopen("CONOUT$", "w", stdout);
        freopen("CONOUT$", "w", stderr);
    }
#endif
    LOCK_INIT();
    init_paths();
    if (!acquire_single_instance()) {
        /* A second launch does not start a second hall; it just shows the
           one that is already running. */
        char url[256];
        int p = read_running_port();
        snprintf(url, sizeof(url), "http://127.0.0.1:%d/__chat__/", p);
#ifdef _WIN32
        if (!g_no_browser) ShellExecuteA(NULL, "open", url, NULL, NULL, SW_SHOWNORMAL);
#else
        fprintf(stderr, "[minichat] already running at %s\n", url);
#endif
        return 0;
    }
    init_identity();
    collect_interfaces();
    if (!g_nips) { snprintf(g_ips[0], sizeof(g_ips[0]), "127.0.0.1"); g_nips = 1; }

    {
        sock_t srv = socket(AF_INET, SOCK_STREAM, 0);
        struct sockaddr_in a;
        int p, bound = 0, ok;
        if (srv == BAD_SOCKET) { debugf("[minichat] socket failed\n"); return 1; }
#ifndef _WIN32
        { int reuse = 1; setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse)); }
#endif
        memset(&a, 0, sizeof(a));
        a.sin_family = AF_INET;
#ifdef __ANDROID__
        /* 有邀请口令才对局域网开放；口令没配好就退回只绑本机，绝不裸奔。 */
        a.sin_addr.s_addr = g_token[0] ? htonl(INADDR_ANY) : htonl(INADDR_LOOPBACK);
#else
        a.sin_addr.s_addr = htonl(INADDR_ANY);
#endif
        for (p = g_port; p < g_port + MAX_PORT_TRY && p <= 65535; p++) {
            a.sin_port = htons((unsigned short)p);
            if (bind(srv, (struct sockaddr *)&a, sizeof(a)) == 0) { g_port = p; bound = 1; break; }
        }
        if (!bound) { debugf("[minichat] no free port from %d\n", g_port); return 1; }
        if (listen(srv, 64) != 0) { debugf("[minichat] listen failed\n"); return 1; }
        write_running_port();
        g_server_ready = 1;
#ifdef __ANDROID__
        debugf("[minichat] listening on %s:%d token=%s, share=%s\n",
               g_token[0] ? "0.0.0.0" : "127.0.0.1", g_port,
               g_token[0] ? "on" : "off", g_share);
#else
        debugf("[minichat] listening on 0.0.0.0:%d, share=%s\n", g_port, g_share);
#endif
        start_thread_fn(discovery_thread, NULL, 1, &ok);
        {
            char url[256];
            snprintf(url, sizeof(url), "http://127.0.0.1:%d/__chat__/", g_port);
#ifdef _WIN32
            if (!g_no_browser) ShellExecuteA(NULL, "open", url, NULL, NULL, SW_SHOWNORMAL);
#endif
        }
        for (;;) {
            sock_t c = accept(srv, NULL, NULL);
            if (c == BAD_SOCKET) continue;
            start_thread_fn(client_thread, (void *)(intptr_t)c, 1, &ok);
            if (!ok) CLOSESOCK(c);
        }
    }
    return 0;
}

/* JNI 侧查询实际监听端口：端口被占用时 main 会向后试 MAX_PORT_TRY 个。 */
int minichat_port(void) { return g_port; }
int minichat_ready(void) { return g_server_ready; }
