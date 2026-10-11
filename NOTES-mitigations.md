# Why the Linux engine could not start — and how to fix it

## Summary

PRoot cannot run inside a normal (zygote-spawned) Android app because Android's
app seccomp filter **traps** several syscalls that the guest libc calls at
process startup. The trap uses `SECCOMP_RET_TRAP`, so the process is **killed
with SIGSYS (signal 31)** rather than being denied the call with `EPERM`. There
is no opt-out: the calls live inside libc, before any of our code runs.

This is a platform restriction, not a bug in the app.

## Evidence (measured on a Samsung SM-X115, Android 16 / API 36)

`tools/syscall-scan.c` forks a child per syscall, so one run finds *every*
trap instead of dying on the first. Run in both contexts with the same binary:

| Context | Seccomp | Trapped syscalls |
|---|---|---|
| `adb shell` | unfiltered | 0 |
| `run-as` | `Seccomp: 0` | **0** |
| app process (zygote) | `Seccomp: 2`, 1 filter | **5** |

The five trapped syscalls, verified by `signal 31` on each:

```
rseq(293)             *** TRAPPED ***   musl calls this at thread registration
io_uring_setup(425)   *** TRAPPED ***   used by libuv / Node
io_uring_enter(426)   *** TRAPPED ***   used by libuv / Node
faccessat2(439)       *** TRAPPED ***   used by newer glibc/musl stat paths
futex_time64(422)     *** TRAPPED ***   64-bit-time futex on new kernels
```

Everything else the agent needs is allowed: `clone`, `futex`, `mmap`,
`mprotect`, `openat`, `read`, `write`, `wait4`, `statx`, `membarrier`,
`prlimit64`, `rt_sigaction`, and the rest — 29 of 34 probed syscalls pass.

### Ruled out along the way

- **Not PRoot's own seccomp.** `PROOT_NO_SECCOMP=1` is already exported by
  `bootstrap.sh` and changes nothing: it disables proot's *own* filter, not
  libc's `rseq` call.
- **Not `targetSdk`.** Lowering to 28 fixed `execve()` (verified: denied in
  `filesDir`, `getExternalFilesDir()` and `cacheDir` at 34) but has no effect
  on the syscall traps.
- **Not SELinux.** No `avc: denied` entries appear in `dmesg` at all.

## What we already tried (and what it cost)

**Downgrading musl — tested, does NOT work.**
musl gained `rseq` in 1.2.3, so Alpine 3.15 (musl 1.2.2, verified to contain no
`rseq` syscall at all) looked like the cheap fix. We built that rootfs
(102 MB, Node v16.20.2) and re-ran the scan from the app process:

```
rseq(293)           *** TRAPPED ***   (the scanner binary itself calls it)
io_uring_setup(425) *** TRAPPED ***
io_uring_enter(426) *** TRAPPED ***
faccessat2(439)     *** TRAPPED ***
futex_time64(422)   *** TRAPPED ***
```

Identical list. And `/bin/echo` (busybox on musl 1.2.2) still died with signal
31 in the app process while working under `run-as`. So **musl is not the thing
calling these** — or at least, not only musl. Node 16 vs Node 22 changed
nothing, which rules out "too new a Node".

The scanner is sound: run under `run-as` it reports **0 trapped**, and only
those same 5 light up under the app's filter.

## So what does call them?

Almost certainly **libc's startup path in general, not one libc's quirk** — and
`io_uring_*` strongly suggests **libuv**, which Node links regardless of
version. Node 16 and Node 22 share it, which fits the identical result.

## The fix, now that we know the real shape

The blocker is **Node/libuv in the guest**, not proot and not musl.

1. **Build Node without io_uring.** libuv consults an env/feature path that can
   be forced off; if Node then runs, we have a working engine. **Cheapest thing
   to try, do this first.**
2. **A guest that isn't Node-based** — or a pure-Java ELF loader that
   translates the trapped calls to safe equivalents. Large effort, no external
   dependency.
3. **Companion app (Termux).** Verified to work today on this device: Termux
   holds a permissive profile and already runs Alpine guests. Not
   self-contained — the user must install it — but it is the only route
   confirmed to work end to end on real hardware today.

## Re-verify after any change

Never assume. Re-run the scanner from the real app process and require zero
`TRAPPED` lines **and** a Node that actually starts:

```sh
# build the scanner inside the candidate rootfs
<proot> -r <rootfs> /usr/bin/aarch64-alpine-linux-musl-gcc -static -O0 -o /tmp/scan scan.c
# then, from the app process:
<proot> -r <rootfs> -b $RUNTIME_ROOT:/probe -w /probe ./scanbin | grep TRAPPED   # must be empty
```

A clean scan is necessary but not sufficient — the only real proof is the agent
starting inside the guest from the app process.

## Verifying a fix

Never assume it worked. Re-run the scanner against the new rootfs from the real
app process and require **zero** `TRAPPED` lines:

```sh
proot -r <newrootfs> /usr/bin/aarch64-alpine-linux-musl-gcc -static -O0 -o /tmp/scan /tmp/scan.c
# then, from the app process:
<proot> -r <newrootfs> ... -w /probe ./scan | grep TRAPPED   # must print nothing
```

A clean run is necessary but not sufficient — the real proof is Node actually
starting inside the guest from the app process.
