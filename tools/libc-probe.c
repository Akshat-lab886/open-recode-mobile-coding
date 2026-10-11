/*
 * libc-probe.c — find the libc call that Android's seccomp filter kills.
 *
 * WHY
 * ---
 * In the app process: a static hello, a dynamic hello and the syscall
 * scanner all run to completion, but busybox dies with SIGSYS. So musl
 * startup, dynamic linking and libc init are all fine - something
 * specific that busybox does at startup is trapped.
 *
 * This probe calls those suspects one at a time, announcing each on
 * unbuffered stderr immediately BEFORE the call. The last line printed is
 * the killer.
 *
 * Built against musl 1.2.2 (no rseq) so the probe survives long enough to
 * reach the interesting calls, and shipped as a jniLib so it executes from
 * nativeLibraryDir without needing proot or a rootfs.
 */

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <pthread.h>
#include <locale.h>
#include <pwd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static void say(const char *m)
{
    size_t n = strlen(m);
    ssize_t u = write(2, m, n);
    (void)u;
}

/* Variadic: the bodies declare locals, and a declaration like
 * "struct addrinfo hints, *res" contains a comma that would otherwise be
 * read as an extra macro argument. */
#define STEP(name, ...)            \
    do {                          \
        say("-> " name "\n");     \
        errno = 0;                \
        __VA_ARGS__;              \
    } while (0)

static void *thread_main(void *arg) { (void)arg; return NULL; }

int main(void)
{
    say("libc-probe: alive\n");

    STEP("openat /etc/hosts", {
        int fd = open("/etc/hosts", O_RDONLY);
        if (fd >= 0) close(fd);
    });

    STEP("access()", {
        (void)access("/etc/hosts", R_OK);
    });

    STEP("stat()", {
        struct stat st;
        (void)stat("/etc/hosts", &st);
    });

    STEP("getpwuid()", {
        (void)getpwuid(getuid());
    });

    STEP("fopen/fread", {
        FILE *f = fopen("/etc/hosts", "r");
        if (f) { char b[8]; (void)fread(b, 1, 8, f); fclose(f); }
    });

    STEP("setlocale()", { (void)setlocale(LC_ALL, "C"); });

    STEP("clock_gettime", {
        struct timespec ts;
        (void)clock_gettime(CLOCK_MONOTONIC, &ts);
    });

    STEP("socket()", {
        int s = socket(AF_INET, SOCK_STREAM, 0);
        if (s >= 0) close(s);
    });

    STEP("getaddrinfo()", {
        struct addrinfo hints, *res = NULL;
        memset(&hints, 0, sizeof hints);
        hints.ai_family = AF_UNSPEC;
        if (getaddrinfo("localhost", NULL, &hints, &res) == 0 && res) freeaddrinfo(res);
    });

    STEP("pthread_create/join", {
        pthread_t t;
        if (pthread_create(&t, NULL, thread_main, NULL) == 0) pthread_join(t, NULL);
    });

    STEP("fork/wait4", {
        pid_t p = fork();
        if (p == 0) _exit(0);
        if (p > 0) { int st; wait4(p, &st, 0, NULL); }
    });

    /* faccessat(AT_EACCESS) is tested LAST: it is the confirmed killer, and
     * placing it first would mask every call after it. */
    STEP("faccessat AT_EACCESS", {
        (void)faccessat(AT_FDCWD, "/etc/hosts", R_OK, AT_EACCESS);
    });

    say("libc-probe: completed without being killed\n");
    return 0;
}
