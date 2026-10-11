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

---

# RESOLVED: the seccomp blocker, measured rather than guessed

Everything above describes the investigation. This is where it landed.

## How big the trap really is

The earlier hand-picked scan of 34 syscalls said 5 were trapped. That was
wrong, and in a way that mattered: fixing all five did **not** make the guest
start. `tools/syscall-scan-full.c` scans the whole plausible aarch64 range,
forking a child per syscall number so one run enumerates everything, and the
real figure is:

```
scanned 368 syscalls, 166 trapped, 1 blocked
```

166 sounds fatal. It is not, and `tools/cross-check-musl.py` is why — it
replays musl's actual syscall set straight out of the ELF (every `movz x8,
#imm`) and intersects it with the measured trapped set. The intersection is 42,
and every entry is a code path musl *contains* but a normal program never
reaches: `mount`, `umount2`, `acct`, `vhangup`, and the whole `setuid` family.
Node does not call any of them.

The two that did bite:

| syscall | who calls it | why it was unavoidable |
|---|---|---|
| `faccessat2` (439) | `faccessat()`, i.e. plain `access()`/`stat()` | musl tries `faccessat2` first and only falls back on `-ENOSYS`; Android traps it instead of returning `-ENOSYS`, so the fallback is unreachable |

## The fix

`tools/patch-musl-faccessat2.py` rewrites musl's single `movz x8, #439` into
`movz x8, #1023`. 1023 is unassigned, so the kernel returns `-ENOSYS` and musl
takes the fallback path it always intended. One instruction, one byte changed,
and the patcher refuses unless exactly one site matches.

Result: the guest progressed from dying at its first `access()` to getting
through file I/O, locale, sockets, DNS, threads and `fork`.

## Why the app ships its own Node instead

The guest is still not clean enough to be the primary path: 42 syscall sites
remain inside musl, and proving each unreachable is an ongoing cost. So the
app now carries a **bionic-linked Node** — bionic being Android's own libc,
designed for exactly this sandbox.

`tools/build-termux-node-payload.py` assembles it from Termux packages
(nodejs 26.4.0, libc++, c-ares, libffi, openssl, libsqlite, icu, zlib). Two
packaging constraints shaped it and both are easy to get wrong silently:

1. **The interpreter must be a jniLib.** At `targetSdk` 29+ Android's
   `execute_no_trans` blocks `execve` in app-writable dirs (`filesDir`,
   `cacheDir`, external files dir). `nativeLibraryDir` is not app-writable, so
   it is the only place an app may still exec. Hence `node` ships as
   `libnode.so`, and hence the build-level `dshTargetSdk` knob.
2. **Its libraries must NOT be jniLibs.** AGP packages only `lib*.so`, so
   `libz.so.1`, `libssl.so.3` and `libicuuc.so.78` are dropped without a word
   and the loader fails with `library "libz.so.1" not found`. They ship as
   `assets/nativelibs`, unpacked to `filesDir` (read-only mmap needs no exec
   permission) and found via `LD_LIBRARY_PATH`.

### Verified on device (Samsung SM-X115, Android 16)

```
node --version              -> v26.4.0
console.log(...)            -> BIONIC-NODE-OK v26.4.0 arm64
fs write + read + sha256    -> WORK-OK {"ok":true,"body":"hello-from-node",...}
https.get(registry.npmjs.org/-/ping) -> HTTPS-OK 200
npm --version               -> 11.20.0
npm install is-odd          -> added 2 packages in 3s
```

### Two hardcoded-prefix traps found the same way

Both were found by reading the actual error, not by reasoning about it:

- Termux's Node hardcodes its OpenSSL config to
  `/data/data/com.termux/files/usr/etc/tls/openssl.cnf`, aborting every run
  with `BIO_new_file:Permission denied`. Fixed by shipping `openssl.cnf` and
  setting `OPENSSL_CONF`.
- `os.tmpdir()` likewise returns Termux's prefix. Fixed by setting `TMPDIR`.

## Open

- The full agent tree (`@deepseek-ai/dsh`, ~540 packages, ~290 MB unpacked)
  does not yet install on-device: npm dies with a `Bus error` (mmap failure)
  on this device's 1.7 GB of free space. Small installs are fine.
- PRoot + the patched rootfs stays as a fallback runtime.
