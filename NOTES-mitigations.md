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

## The fix

The guest must be built against a libc that never calls the trapped syscalls.

**Option A — older musl (recommended first try).**
musl gained `rseq` support in 1.2.3. Alpine 3.15 ships musl **1.2.2**, verified
here to contain no `rseq` syscall at all (212 syscall immediates in `x8`, none
of them 293). Build the rootfs from `v3.15` instead of `v3.22`.

Costs: Node 16.20.2 instead of 22.x. `@deepseek-ai/dsh@0.2.0-rc.2` declares no
`engines` constraint, so this should still run.

Risk: `faccessat2` and `futex_time64` may still be reached by Node 16's libuv,
so the scan must be re-run against the new rootfs before committing to it.

**Option B — patch out the calls.**
Patch musl's `__rseq_register` and the io_uring/faccessat2 paths, or ship a
prebuilt musl with those disabled. Needs a cross-toolchain (we can now compile
on-device via `WITH_TOOLCHAIN=1`, but shipping a patched libc means owning it
forever).

**Option C — different virtualisation.** A pure-Java ELF loader, or driving a
companion app (Termux) that already holds a permissive profile. Termux works
today on this device but is not self-contained.

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
