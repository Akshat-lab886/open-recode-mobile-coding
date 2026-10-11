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

---

# The agent itself: how far it actually gets

Building the runtime was only the first half. This is the measured state of
`@deepseek-ai/dsh` running inside the app.

## Getting the agent onto the device at all

`npm install @deepseek-ai/dsh` on the device **dies with a Bus error**. That is
not a dependency problem: this device has 3.7 GB RAM with ~1 GB available, and
npm resolving a ~540-package tree exhausts it. Small installs are fine
(`is-odd` -> "added 2 packages in 3s").

So the tree is assembled on the host and shipped, the same way the rootfs is:

```
npm install -g @deepseek-ai/dsh --ignore-scripts   # pure JS, so host-built is valid
rsync/prune -> tar.gz -> 39 MB -> adb/files -> extract
```

Pruning that mattered: `.map` files, docs/tests, and every prebuild for a
platform that is not ours. 288 MB -> 197 MB.

## Two native modules had to be built for bionic

Neither ships a binary our Node could load:

**Koffi (FFI, needed by the subprocess and sandbox loaders).** It resolves its
native module from `process.platform`, and on Android that is `android`, so
`@koromix/koffi-android-arm64` is the correct package - it does exist, and it
is bionic-linked. Easy to miss: `npm` on the host installs only the *host*
platform's optional dependency, so the Android build is absent by default and
dsh fails with "Cannot find the native Koffi module; did you bundle it
correctly?". Verified loading under bionic: `KOFFI-LOADED function`.

**node-pty (`pty.node`).** node-pty ships no Android prebuild, so it has to be
compiled. `tools/build-termux-node-payload.py`'s sibling logic, done with NDK
28.2 and the Node headers from Termux's own `nodejs` apk:

```sh
aarch64-linux-android24-clang++ -shared -fPIC -O2 -std=gnu++17 \
  -static-libstdc++ -include pty.h \
  -I<termux>/include/node -I<node-addon-api> \
  -DNAPI_VERSION=8 -DNODE_GYP_MODULE_NAME=pty -DBUILDING_NODE_EXTENSION \
  src/unix/pty.cc src/unix/spawn-helper.cc -o pty.node
```

Three things that are easy to get wrong:
- `forkpty`/`openpty` are in `<pty.h>` on bionic, not `<util.h>`, and they are
  `__INTRODUCED_IN(23)` - building against `android21` compiles fine and then
  fails to *link*, because the declarations are hidden at that API level.
- `-static-libstdc++` avoids depending on Termux's `libc++_shared.so`, whose
  ABI version need not match the NDK's.
- Verified by DT_NEEDED: `libc.so`, `libm.so`, `libdl.so` - bionic, not
  `libc.so.6`.

Confirmed working: `pty.spawn('/system/bin/sh', ...)` -> `PTY-HELLO`, exit 0.

## dsh also requires `--expose-internals`

Without it the plugin tree refuses to load:
`cordis-plugin-hmr: --expose-internals is required for HMR service`.

## Where it stands

`dsh --version` -> `0.1.5-rc.1`.

`dsh web --host 127.0.0.1 --port <p> --no-open` **starts and prints its URL**:

```
dsh web: http://127.0.0.1:3087/?token=...
```

The plugin tree loads, Koffi loads, node-pty spawns real PTYs. It then dies
within seconds, before the port accepts a connection, with SIGBUS or SIGSEGV
varying between runs.

### It is NOT a memory shortage

This was my first conclusion and it was wrong. Measured, sampling VmRSS/VmHWM
throughout startup:

```
t=0    rss=10 MB    t=7   rss=462 MB   t=11  rss=504 MB
t=3    rss=100 MB   t=8   rss=551 MB (peak)   t=13 rss=460 MB
                       system MemAvailable never fell below 650 MB
```

And with `--trace-gc`:

```
Scavenge 205.9 (248.2) -> 130.4 (184.7) MB, 17.03 / 0.01 ms
Incremental Mark-Compact 225.8 (292.2) -> 99.1 (157.7) MB, 41.48 / 0.01 ms
dsh web: http://127.0.0.1:3091/?token=...
Scavenge 208.5 (250.9) -> 128.5 (187.5) MB, 9.32 / 0.03 ms
Segmentation fault
```

The JS heap oscillates between ~100 and ~225 MB, every collection completes in
1-17 ms, and there is no "Reached heap limit" - then SIGSEGV. Peak RSS is
551 MB with 650 MB still free system-wide. So neither V8's heap nor the
device's RAM is the constraint. `npm`'s crash on this device is likewise not
OOM: its tombstone is `signal 7 (SIGBUS), code 2 (BUS_ADRERR)` with frame #00
inside `libc++_shared.so`.

What it actually is remains open. It is native code, it is not GC, and it is
not allocation pressure.

### How to get a backtrace

`run-as` children produce no tombstone, which is why this stalled. The fix is
also work we need anyway: have the **app** spawn dsh, so Android's own
debuggerd captures it as a real app crash with a full native backtrace. Hand-run
shell commands will not tell us this.

### Rejected hypotheses worth not re-testing

- **Memory / V8 heap.** Disproved above.
- **libc++ ABI mismatch.** `koffi.node` DT_NEEDED is only `libm`, `libdl`,
  `libc`; and `pty.node` is built `-static-libstdc++`, so neither loads
  Termux's `libc++_shared.so`.
- **Corrupt ICU blob.** `libicudata.so.78` is byte-correct and
  `new Intl.DateTimeFormat()` works.
- **Koffi or node-pty in isolation.** Both load and work standalone
  (`KOFFI-LOADED`, `PTY-HELLO`). The fault appears only in dsh's full startup.

So the runtime problem is solved and the agent boots; what remains is making it
survive on a memory-constrained device, then wiring it into `AgentRuntime` so
the app drives it instead of a hand-run shell command.

### One false lead worth recording

A probe run failed with:

```
libicudata.so.78 has invalid shdr offset/size: 33108192/640
CANNOT LINK EXECUTABLE libnode.so
```

This looked like a corrupt ICU blob, but the file is fine: its section header
table ends exactly at EOF (33108192 + 10*64 = 33108832 = file size), and
`node -e "new Intl.DateTimeFormat()..."` formats a date correctly both before
and after. It was a transient read of a file being rewritten by the asset
unpacker. Verify against a live run before chasing a corruption that is not
there.

---

# CORRECTION: it IS memory pressure, just not the V8 heap

I previously concluded "it is not a memory shortage". That was too strong, and
the evidence that settles it is a controlled experiment rather than a reading.

Same build, same device, only the free memory changed:

| MemAvailable at launch | Result |
|---|---|
| ~650 MB | SIGSEGV / SIGBUS within seconds |
| ~1,170 MB | serves stably: 303 -> `/` -> 401, alive 3+ minutes |

Background apps were force-stopped to open the headroom, nothing else changed.
So the crash is triggered by memory pressure. I was wrong to rule it out.

## Why the GC trace looked perfectly healthy

This is the part that confused me, and it is the actual insight:

```
Scavenge 205.9 (248.2) -> 130.4 (184.7) MB, 17.03 / 0.01 ms
dsh web: http://127.0.0.1:3091/?token=...
Scavenge 208.5 (250.9) -> 128.5 (187.5) MB, 9.32 / 0.03 ms
Segmentation fault
```

V8's heap is nowhere near its limit and every collection finishes. **Because
the allocation that fails is not a V8 allocation.** It is native - a malloc or
mmap inside one of the loaded native modules (Koffi's FFI, node-pty, OpenSSL),
or a thread stack. Those never appear in `--trace-gc`, and a failed mmap on
Android surfaces as SIGBUS with no JavaScript error and no JS heap message.

That reconciles everything that looked contradictory:
- V8 heap healthy and small (100-225 MB)
- yet RSS peaks at 551 MB
- yet the process dies under pressure

## The peak is a startup spike, not steady state

Worth knowing when tuning this: RSS peaks at **551 MB during startup and then
falls to ~60 MB** once the plugin tree has loaded and V8 has collected. So the
budget problem is the transient, not the running server. Constraining the
startup heap (`--max-old-space-size`) and reducing concurrently-loaded modules
should remove it without slowing the steady state.

## On "can we just use the vacant swap?"

No, and it is worth being precise about why, because the numbers look
inviting: `SwapTotal` 8.0 GB with `SwapFree` 6.3 GB.

- That is **zram**, which is *compressed physical RAM*, not separate storage.
  Pages in zram still consume physical memory, in compressed form. It buys
  capacity by compressing, not by adding capacity.
- Swap is kernel-managed. There is no app-level API to route a page to it.
  `madvise` and `mlock` do not do this, and creating a swapfile needs root.
- The kernel already does this automatically under pressure - that is what
  zram is for - and it still was not enough at ~650 MB.

So the headroom cannot simply be claimed. The real lever is lowering the
551 MB startup spike so the agent fits in a tight budget at all.
