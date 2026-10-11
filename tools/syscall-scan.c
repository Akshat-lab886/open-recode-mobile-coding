/*
 * syscall-scan.c — find EVERY syscall Android's app seccomp filter traps.
 *
 * tools/syscall-probe.c proved rseq(2) is trapped and kills the process. But
 * a process can only die once, so that probe finds exactly one blocker per
 * run. This one forks a child per syscall instead: a TRAP kills only that
 * child, and the parent reports which ones died. One run, full list.
 *
 * The parent is a fork of an already-running process, so it already survived
 * libc startup - which is precisely why the child must be a fresh exec for
 * this to model what an agent process actually hits.
 *
 * Usage:  syscall-scan            (parent: runs children, prints report)
 *         syscall-scan <nr> ...   (child mode: call one syscall)
 */

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

/* aarch64 syscall numbers. */
#ifndef __NR_rseq
#define __NR_rseq 293
#endif
#ifndef __NR_membarrier
#define __NR_membarrier 283
#endif
#ifndef __NR_statx
#define __NR_statx 291
#endif
#ifndef __NR_clone3
#define __NR_clone3 435
#endif
#ifndef __NR_io_uring_setup
#define __NR_io_uring_setup 425
#endif
#ifndef __NR_io_uring_enter
#define __NR_io_uring_enter 426
#endif
#ifndef __NR_process_madvise
#define __NR_process_madvise 448
#endif
#ifndef __NR_faccessat2
#define __NR_faccessat2 439
#endif
#ifndef __NR_close_range
#define __NR_close_range 436
#endif
#ifndef __NR_getrandom
#define __NR_getrandom 278
#endif
#ifndef __NR_futex_time64
#define __NR_futex_time64 422
#endif

struct probe {
    int nr;
    const char *name;
};

/* Called with all-zero-ish args; we only care whether the CALL ITSELF is
 * trapped, not what it returns. */
static struct probe PROBES[] = {
    { __NR_rseq, "rseq(293)" },
    { __NR_io_uring_setup, "io_uring_setup(425)" },
    { __NR_io_uring_enter, "io_uring_enter(426)" },
    { __NR_membarrier, "membarrier(283)" },
    { __NR_statx, "statx(291)" },
    { __NR_clone3, "clone3(435)" },
    { __NR_process_madvise, "process_madvise(448)" },
    { __NR_faccessat2, "faccessat2(439)" },
    { __NR_close_range, "close_range(436)" },
    { __NR_getrandom, "getrandom(278)" },
    { __NR_getpid, "getpid(172)" },
    { __NR_uname, "uname(160)" },
    { __NR_futex_time64, "futex_time64(422)" },
    { 220, "clone(220)" },
    { 98, "futex(98)" },
    { 129, "kill(129)" },
    { 178, "gettid(178)" },
    { 124, "sched_yield(124)" },
    { 131, "tgkill(131)" },
    { 134, "rt_sigaction(134)" },
    { 135, "rt_sigprocmask(135)" },
    { 222, "mmap(222)" },
    { 226, "mprotect(226)" },
    { 233, "madvise(233)" },
    { 260, "wait4(260)" },
    { 261, "prlimit64(261)" },
    { 78, "readlinkat(78)" },
    { 79, "newfstatat(79)" },
    { 56, "openat(56)" },
    { 63, "read(63)" },
    { 64, "write(64)" },
    { 93, "exit(93)" },
    { 94, "exit_group(94)" },
    { 172, "getpid(172)" },
};

static void run_child(long nr)
{
    errno = 0;
    long rc = syscall((int)nr, 0L, 0L, 0L, 0L, 0L, 0L);
    _exit(rc == -1 && errno == ENOSYS ? 10 : 0);
}

int main(int argc, char **argv)
{
    size_t n = sizeof(PROBES) / sizeof(PROBES[0]);

    /* child mode */
    if (argc > 1) {
        long nr = atol(argv[1]);
        run_child(nr);
        return 0;
    }

    printf("scanning %zu syscalls, one child each\n", n);
    printf("%-24s %s\n", "SYSCALL", "VERDICT");
    for (size_t i = 0; i < n; i++) {
        pid_t pid = fork();
        if (pid == 0) { run_child(PROBES[i].nr); }
        int status = 0;
        waitpid(pid, &status, 0);
        const char *verdict;
        if (WIFSIGNALED(status)) {
            char buf[64];
            snprintf(buf, sizeof buf, "*** TRAPPED (signal %d) ***", WTERMSIG(status));
            verdict = buf;
        } else if (WIFEXITED(status) && WEXITSTATUS(status) == 10) {
            verdict = "absent (ENOSYS)";
        } else {
            verdict = "allowed";
        }
        printf("%-24s %s\n", PROBES[i].name, verdict);
        fflush(stdout);
    }
    return 0;
}
