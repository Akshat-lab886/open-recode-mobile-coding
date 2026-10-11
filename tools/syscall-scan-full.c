/*
 * syscall-scan-full.c — find EVERY trapped syscall, not a guessed subset.
 *
 * WHY THIS EXISTS
 * ---------------
 * tools/syscall-scan.c tests a hand-picked list of 34 syscalls. That list
 * turned out to be incomplete: after the musl faccessat2 patch was applied,
 * the guest started dying inside getpwuid(), which uses a syscall the
 * hand-picked list never exercised. Guessing one call at a time is how we
 * ended up wrong twice.
 *
 * So this scans the whole plausible aarch64 range, forking a child per
 * syscall number. A SECCOMP_RET_TRAP kills only that child, so one run
 * enumerates every blocked syscall the app can reach.
 *
 * Denylisted numbers are skipped: as an unprivileged app they would just fail
 * with EPERM, but there is no reason to invite reboots or module loads.
 */

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>

#define MIN_NR 0
#define MAX_NR 340
#define EXTRA_LO 420
#define EXTRA_HI 470

static int denied(int nr)
{
    static const int skip[] = {
        38,   /* setitimer   - touches the shared timer for the process */
        165,  /* delete_module */
        142,  /* reboot      */
        112,  /* clock_settime */
        170,  /* settimeofday */
        169,  /* sethostname  */
        163,  /* init_module */
        105,  /* setuid      - would change our identity */
        106,  /* setgid      */
        116,  /* setpriority - can renice ourselves */
        117,  /* setreuid    */
        119,  /* setregid    */
        113,  /* setpgid     */
        174,  /* rt_sigaction - replacing handlers could break the tracer */
        175,  /* rt_sigprocmask */
        /* Blocking calls. We invoke every syscall with null/zero arguments,
         * which for these means "wait forever" rather than "fail fast", so
         * the scan never terminates. Skipped, not because they are trapped,
         * but because we cannot probe them this way. */
        7,    /* poll        - poll(NULL, 0, NULL) blocks forever */
        23,   /* select      */
        73,   /* ppoll       */
        270,  /* pselect6    */
        232,  /* epoll_wait  */
        281,  /* epoll_pwait  */
        441,  /* epoll_pwait2 */
        22,   /* pipe        - blocking until a writer appears */
        281,  /* epoll_pwait (dup) */
        123,  /* sched_setaffinity - can stall the thread */
    };
    for (size_t i = 0; i < sizeof skip / sizeof skip[0]; i++)
        if (skip[i] == nr) return 1;
    return 0;
}

static void run_child(long nr)
{
    errno = 0;
    long rc = syscall((int)nr, 0L, 0L, 0L, 0L, 0L, 0L);
    _exit(rc == -1 && errno == ENOSYS ? 10 : 0);
}

/* Probe one syscall in a child, with a hard timeout. Several syscalls given
 * null arguments WAIT rather than fail (poll, pselect, ptrace(PTRACE_TRACEME),
 * ...), so an unbounded waitpid makes the whole scan hang. We report those as
 * BLOCKED instead of stalling. */
#define WAIT_MS 120
static int probe(long nr)   /* 0=ran, 10=ENOSYS, 1=trapped, 2=blocked */
{
    pid_t pid = fork();
    if (pid == 0) run_child(nr);
    int st = 0, waited = 0, rc = -1;
    for (;;) {
        pid_t r = waitpid(pid, &st, WNOHANG);
        if (r == pid) {
            if (WIFSIGNALED(st)) rc = 1;
            else if (WIFEXITED(st) && WEXITSTATUS(st) == 10) rc = 10;
            else rc = 0;
            break;
        }
        usleep(2000);
        waited += 2;
        if (waited >= WAIT_MS) { kill(pid, SIGKILL); waitpid(pid, &st, 0); rc = 2; break; }
    }
    return rc;
}


int main(int argc, char **argv)
{
    if (argc > 1) { run_child(atol(argv[1])); return 0; }

    int trapped[600], ntrap = 0;
    int blocked[600], nblock = 0;
    int total = 0;

    for (int nr = MIN_NR; nr <= MAX_NR; nr++) {
        if (denied(nr)) continue;
        total++;
        int rc = probe(nr);
        if (rc == 1) trapped[ntrap++] = nr;
        else if (rc == 2) blocked[nblock++] = nr;
    }
    for (int nr = EXTRA_LO; nr <= EXTRA_HI; nr++) {
        if (denied(nr)) continue;
        total++;
        int rc = probe(nr);
        if (rc == 1) trapped[ntrap++] = nr;
        else if (rc == 2) blocked[nblock++] = nr;
    }

    printf("scanned %d syscalls, %d trapped, %d blocked\n", total, ntrap, nblock);
    for (int i = 0; i < ntrap; i++) printf("TRAPPED %d\n", trapped[i]);
    printf("--blocked (skipped as unprobed)--\n");
    for (int i = 0; i < nblock; i++) printf("BLOCKED %d\n", blocked[i]);
    fflush(stdout);
    return 0;
}
