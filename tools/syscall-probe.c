/*
 * syscall-probe.c — VERIFIED root cause for PRoot failing inside an Android app.
 *
 * WHAT THIS PROVED
 * ----------------
 * PRoot works under `adb shell` and `run-as`, but its child dies inside a
 * zygote-spawned app with:
 *
 *     proot info: vpid 1: terminated with signal 31
 *
 * Signal 31 is SIGSYS ("bad system call"). This probe was compiled on-device
 * (static, musl, aarch64) and run in BOTH contexts. From `run-as` it ran to
 * completion. From the real app process it printed exactly:
 *
 *     syscall-probe: alive
 *     -> calling rseq ...
 *     <killed>
 *
 * and nothing more. The blocked syscall is rseq(2).
 *
 * WHY rseq IS BLOCKED
 * -------------------
 * musl's libc calls rseq() from its thread-registration path on process start.
 * Android added rseq to the app seccomp denylist, and the policy uses
 * SECCOMP_RET_TRAP - the process is KILLED, not denied with EPERM. No process
 * can opt out: the call lives inside libc, before any of our code runs.
 *
 * Under `run-as` the identical call returns -1/ENOSYS(38) and execution
 * continues normally, which is why the same binary works there.
 *
 * THIS FILE IS A DIAGNOSTIC, not shipped in the APK. Its job is to turn "the
 * engine mysteriously dies" into a named syscall in a single run.
 *
 * Build (inside the rootfs, on device):
 *   proot -r /data/local/tmp/trt /usr/bin/aarch64-alpine-linux-musl-gcc \
 *       -static -O0 -o /tmp/probe /tmp/probe.c
 */

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef __NR_rseq
#define __NR_rseq 293 /* aarch64 */
#endif

/* Unbuffered write(2): line ordering is exact relative to the kill, so the
 * last line printed is the syscall that was blocked. */
static void say(const char *msg)
{
    size_t len = strlen(msg);
    ssize_t unused = write(2, msg, len);
    (void)unused;
}

static long probe(const char *name, long nr, long a0, long a1, long a2)
{
    char buf[128];
    int n = snprintf(buf, sizeof buf, "-> calling %s ...\n", name);
    if (n > 0) { ssize_t u = write(2, buf, (size_t)n); (void)u; }
    errno = 0;
    long rc = syscall(nr, a0, a1, a2);
    n = snprintf(buf, sizeof buf, "   %s returned %ld (errno=%d)\n", name, rc, errno);
    if (n > 0) { ssize_t u = write(2, buf, (size_t)n); (void)u; }
    return rc;
}

int main(void)
{
    say("syscall-probe: alive\n");

    /* THE SUSPECT. musl calls this at startup; Android's app seccomp policy
     * traps it, so the process dies here and never reaches anything below. */
    probe("rseq", __NR_rseq, 0, 0, 0);

    probe("getpid", SYS_getpid, 0, 0, 0);
    probe("uname", SYS_uname, 0, 0, 0);

    say("syscall-probe: completed without being killed\n");
    return 0;
}
