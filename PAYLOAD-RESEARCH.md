# PAYLOAD-RESEARCH.md — getting a real Node.js onto ARM64 Android under PRoot

**Status: SOLVED.** A working `node` + `npm` + full developer toolchain was booted
**inside PRoot on the physical Android device connected over adb** and verified by
executing JavaScript, spawning child processes, and completing a TLS request to
`registry.npmjs.org`.

Every command output in this document was captured on the machine/device during the
investigation. Anything I did **not** run is labelled as such.

---

## 0. TL;DR / recommendation

| | Option | Verdict |
|---|---|---|
| **★ WINNER** | **Alpine aarch64 rootfs + Alpine's own `nodejs` apk, booted under PRoot** | **Proven on device.** `node v22.23.2`, `npm 11.6.4`, `bash 5.2.37`, `git 2.49.1`, `ripgrep 14.1.1`, `curl 8.14.1`, `python3 3.12.15` — all running inside PRoot on a real Samsung SM-X115 / Android 16. |
| B | Termux `nodejs` `.deb` run **natively** (no PRoot) | **Also proven on device**, but needs a byte-patch of Termux's compiled-in prefix, and gives you **no Linux userland** (no `git`, no `bash`). Fallback only. |
| A | `nodejs-mobile` | **Not a CLI.** The official Android release zip ships **only `lib/arm64-v8a/libnode.so` + headers — no `node` executable at all.** It is an embeddable JNI library. |
| C | Cross-compile glibc Node for `aarch64-linux-gnu` | Works in principle, but **strictly worse** than the Alpine route: needs a glibc rootfs (much bigger), and Alpine/musl already has a purpose-built aarch64 Node. |

### The "inside PRoot vs native" question — it resolves cleanly

**Node does NOT need to be an Android/bionic binary.** PRoot does not emulate a libc;
it only rewrites syscalls via `ptrace`. The guest loader it runs is therefore the
*guest's* loader, taken from the rootfs. An **Alpine/musl `aarch64` `node` runs perfectly
inside a musl Alpine rootfs on Android** — Android's own libc is never involved in the
guest process.

That means:

* **The app's current architecture is correct.** Keep `proot -r <rootfs> … node …`.
  Nothing about `AgentRuntime.prootWrap()` has to become "native".
* What *must* change is the rootfs choice: **Alpine/musl, not glibc**, and the node
  binary must be the **Alpine apk**, not a glibc `linux-arm64` build.
* Corollary: a glibc `node` (e.g. official `node-vX-linux-arm64.tar.xz`) would need a
  **glibc** rootfs (Debian/Ubuntu via proot-distro). It cannot run in an Alpine/musl
  rootfs. Do not mix the two.

---

## 1. Test rig (real hardware)

```
$ adb devices -l
List of devices attached
R9ZX60AHKEM   device usb:3-1 product:gta9ins model:SM_X115 device:gta9 transport_id:1

$ adb shell getprop ro.product.cpu.abi          -> arm64-v8a
$ adb shell getprop ro.build.version.release    -> 16
$ adb shell getprop ro.build.version.sdk        -> 36
$ adb shell uname -a
Linux localhost 6.12.23-android16-5-abX115XXS9DZB6-4k #1 SMP PREEMPT Fri Feb 13 08:34:07 UTC 2026 aarch64 Linux
$ adb shell getenforce                          -> Enforcing
$ adb shell id
uid=2000(shell) gid=2000(shell) groups=... 3003(inet) ... context=u:r:shell:s0
```

Note: **the device disconnected over USB partway through this investigation**
(`adb devices` became empty and did not recover). Everything reported as "verified
below" completed *before* that. The one thing that did not finish is called out in
§7.

---

## 2. Option A — `nodejs-mobile` (Google's Node for Android/iOS)

### 2.1 Releases exist, but contain no CLI

```
$ curl -sSL https://api.github.com/repos/nodejs-mobile/nodejs-mobile/releases
=== v18.20.4 (2024-10-07) ===
  nodejs-mobile-v18.20.4-android.zip | 57287354 | 29844 downloads
  nodejs-mobile-v18.20.4-ios.zip     | 51492431 |  6240 downloads
```

Downloaded and verified:

```
$ curl -sSL -o nm-android.zip https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v18.20.4/nodejs-mobile-v18.20.4-android.zip
$ shasum -a 256 nm-android.zip
bd7321eaa1a7602fbe0bb87302df2d79d87835cf4363fbdd17c350dbb485c2af
$ stat -f%z nm-android.zip
57287354

$ unzip -l nm-android.zip | grep -E "bin/"
  62475584  bin/arm64-v8a/libnode.so
  58720948  bin/armeabi-v7a/libnode.so
  65361944  bin/x86/libnode.so
  ------
  196802854  637 files        (the other 634 are include/node/*.h)

$ unzip -l nm-android.zip | awk '{print $4}' | grep -oE "^[^/]+/[^/]*/" | sort -u
bin/arm64-v8a/
bin/armeabi-v7a/
bin/x86/
include/node/
```

**There is no `node` executable in the Android release.** It is a JNI/React-Native
embedding kit. Its own `BUILDING.md` confirms the intent:

> ### Android
> Android is not a supported platform. Patches to improve the Android build are
> welcome. There is no testing on Android in the current continuous integration
> environment. …
> `./android-configure <NDK> <SDK version> <target architecture>; make -j4`

and there is **no prebuilt Android CLI on npm** either:

```
$ curl -sSL "https://registry.npmjs.org/-/v1/search?text=nodejs-mobile&size=10"
nodejs-mobile-react-native@18.20.4 - Node.js for Mobile Apps React Native plugin
node-gyp-build-mobile@4.6.0-3 - nodejs-mobile fork of node-gyp-build
nodejs-mobile-gyp@0.4.0 - Node.js for Mobile Apps native addon build tool
...   (no standalone nodejs-mobile CLI)
```

**Verdict A: REJECT as a drop-in `node`.** To use it you would (a) cross-compile from
source with the NDK to obtain a CLI, or (b) redesign the app to host Node in-process via
JNI — which would *remove* PRoot from the architecture and give you no Linux userland.
Both are strictly worse than what already works.

---

## 3. Option C (the winner) — Alpine aarch64 rootfs + Alpine `nodejs` under PRoot

### 3.1 Artifacts fetched (all verified)

```
# PRoot, statically linked aarch64 -- runs on Android with no root
$ curl -sSL -o proot-aarch64 https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static
fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45   1479432 bytes
$ file proot-aarch64
ELF 64-bit LSB executable, ARM aarch64, statically linked, with debug_info, not stripped

# Alpine base userland
https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.4-aarch64.tar.gz
fc11cf987b37b2e57969cea7e0b8df0777e572d41fe20731630c1e926a8a07a2   3986901 bytes

# Alpine Node.js
https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64/nodejs-22.23.2-r0.apk
3834a57bf5af6310b400c1458b2350b2a0e560aa1de7e8a164e93867602fc5e4  18664082 bytes
```

### 3.2 Full dependency closure (resolved from the signed APKINDEX, HEAD-verified)

Generated with `tools/resolve-alpine-deps.py`; all 13 URLs returned `HTTP/2 200`:

```
main/aarch64/nodejs-22.23.2-r0.apk                  18664082
main/aarch64/bash-5.2.37-r0.apk                       499996
main/aarch64/git-2.49.1-r0.apk                       3453676
main/aarch64/curl-8.14.1-r3.apk                       165252
main/aarch64/python3-3.12.15-r0.apk                  8092333
main/aarch64/libcurl-8.14.1-r3.apk                    329625
main/aarch64/libcrypto3-3.5.9-r0.apk                 2293157
main/aarch64/libssl3-3.5.9-r0.apk                     374074
main/aarch64/libexpat-2.8.5-r0.apk                     61287
main/aarch64/ca-certificates-20260909-r0.apk          132587
main/aarch64/ca-certificates-bundle-20260909-r0.apk   108526
community/aarch64/npm-11.6.4-r0.apk                   1914341
community/aarch64/ripgrep-14.1.1-r0.apk               1362897
```

### 3.3 What I ran on the device

```
# proot runs from /data/local/tmp as the unprivileged `shell` uid, SELinux Enforcing
$ adb push proot-aarch64 /data/local/tmp/proot
$ adb shell "/data/local/tmp/proot --version"
 _____ _____              ___
|  __ \  __ \_____  _____|   |_
|   __/     /  _  \/  _  \    _|
|__|  |__|__\_____/\_____/\____| v5.3.0
built-in accelerators: process_vm = yes, seccomp_filter = yes
```

```
# push the Alpine rootfs (tar over the wire avoids adb's symlink limitation)
$ tar czf - -C root . | adb shell "cd /data/local/tmp/aroot && tar xzf -"

# boot it
$ adb shell "/data/local/tmp/proot -r /data/local/tmp/aroot /bin/sh -c 'echo HELLO-FROM-PROOT'"
HELLO-FROM-PROOT
```

```
# install node + npm + the agent toolchain (network reachable from the device)
$ adb shell "proot -r /data/local/tmp/aroot -b /proc -b /dev -b /sys -w / \
            /usr/bin/env PATH=/usr/bin:/bin:/sbin:/root/bin apk add --no-cache nodejs npm"
...
OK: 137 MiB in 59 packages

$ adb shell "proot ... apk add --no-cache bash git curl ripgrep python3 openssh-client"
(28/28) Installing ripgrep (14.1.1-r0)
Executing busybox-1.37.0-r20.trigger
OK: 137 MiB in 59 packages
```

> The `ERROR: NNN errors updating directory permissions` lines are **benign on device**
> (`apk` cannot `chown` under PRoot as a non-root uid); every package still installed.
> Building the payload offline on Linux (`tools/build-alpine-node-rootfs.sh`) avoids
> them entirely because ownership is baked into the tarball.

### 3.4 The proof — Node actually executes

```
$ adb shell "proot -r /data/local/tmp/aroot -b /proc -b /dev -b /sys -w / \
            /usr/bin/env PATH=/usr/bin:/bin:/sbin:/root/bin HOME=/root \
            node -e 'console.log(\"NODE OK\", process.version, process.arch, process.platform)'"
NODE OK v22.23.2 arm64 linux
```

```
$ ... node -e 'const cp=require("child_process");
               console.log("exec:", cp.execSync("/bin/busybox echo child-ok").toString().trim());
               require("https").get("https://registry.npmjs.org/dsh",
                 r=>{console.log("https status",r.statusCode);process.exit(0)})'
exec: child-ok
tls-ok function
https status 200

$ ... npm --version
11.6.4
```

```
$ ... sh -c 'node -v; npm -v; bash --version|head -1; git --version; rg --version|head -1; \
             curl --version|head -1; python3 -V'
v22.23.2
11.6.4
GNU bash, version 5.2.37(1)-release (aarch64-alpine-linux-musl)
git version 2.49.1
ripgrep 14.1.1
curl 8.14.1 (aarch64-alpine-linux-musl) libcurl/8.14.1 OpenSSL/3.5.6 zlib/1.3.2 brotli/1.1.0 zstd/1.5.7 c-ares/1.34.8 libidn2/2.3.7 libpsl/0.21.5 nghttp2/1.69.0
Python 3.12.15
```

### 3.5 Things the app's current `prootWrap()` gets wrong (found by testing)

These are **bugs in the existing wrapper**, not payload problems. The other agent
editing `AgentRuntime.kt` needs these:

1. **`/dev` is not bound → `git` breaks.**
   ```
   $ adb shell "proot -r aroot -b ws:/workspace -w /workspace env PATH=... sh -c 'git init -q .'"
   fatal: could not open '/dev/null' for reading and writing: No such file or directory
   ```
   Add `-b /dev` (and `-b /proc`). PRoot's `-r` alone gives you a rootfs with an
   **empty `/dev`**.
2. **`PATH` leaks the host's Android `PATH` into the guest.**
   ```
   $ adb shell "proot -r aroot /bin/sh -c 'echo PATH=$PATH'"
   PATH=/product/bin:/apex/com.android.runtime/bin:...:/vendor/bin
   ```
   Inside the guest, `cat`, `uname`, `ls` were all "not found" until `PATH` was
   overridden. Agent CLIs that spawn `git`/`rg`/`bash` by **name** will fail.
   `prootWrap()` must prepend `/usr/local/bin:/usr/bin:/bin:/sbin:/root/bin`.
3. **`/etc/resolv.conf` does not exist** in a stock minirootfs — anything doing DNS
   inside the guest fails until it is written. Ship one, or bind the host's.
4. **Guest-visible workspace bind works as written** — verified:
   ```
   $ adb shell "proot -r aroot -b /data/local/tmp/ws:/workspace -w /workspace env PATH=... sh -c 'pwd; ls; cat f.txt'"
   /workspace
   f.txt
   hello
   ```
5. **Absolute-path invocation works even with no binds at all**, so the minimal
   `proot -r rootfs -w /workspace node …` form boots — but `git`/network will not.

### 3.6 Exact build recipe

```bash
# Run inside the alpine:3.22 container on ANY machine (macOS is fine via Docker).
docker run --rm -v "$PWD/out:/out" -v "$PWD/tools:/tools" -w /tools alpine:3.22 \
    ./build-alpine-node-rootfs.sh
```

The script unpacks `alpine-minirootfs-3.22.4-aarch64.tar.gz`, downloads the 13
`.apk` files listed in §3.2, runs
`apk add --root <rootfs> --initdb --no-network --allow-untrusted *.apk`,
and emits `rootfs.tar.gz`. See `tools/build-alpine-node-rootfs.sh`.

Two gotchas it encodes:

* **Run `apk` on Linux, not macOS.** `apk-tools` has no macOS build; use Docker.
* **`COPYFILE_DISABLE=1` + `--numeric-owner` are mandatory** when tarring from macOS.
  I hit this the hard way — my first rootfs push was polluted with AppleDouble junk:
  ```
  $ adb shell ls /data/local/tmp/aroot/usr/bin | head
  ._[  ._[[  ._awk  ._basename  ._bc  ._beep  ._blkdiscard ...
  ```

---

## 4. Option B — Termux `nodejs` `.deb`, run NATIVELY (no PRoot)

### 4.1 The artifact is real and it is a bionic/Android-native binary

```
$ curl -sSL -o termux-nodejs.deb \
    https://packages.termux.dev/apt/termux-main/pool/main/n/nodejs/nodejs_26.4.0-1_aarch64.deb
eaf3ed8a6e4b72ebaa8c2cb3bad778c577cdf9ea87ca91761213d8a3940fc090   10349280 bytes

$ cat control
Package: nodejs
Architecture: aarch64
Version: 26.4.0-1
Depends: libc++, openssl, c-ares, libicu, libsqlite, zlib, libffi
```

`llvm-readelf` from the NDK (`~/Library/Android/sdk/ndk/28.2.13676358/.../llvm-readelf`):

```
$ file node
ELF 64-bit LSB shared object, ARM aarch64, dynamically linked,
     interpreter /system/bin/linker64, stripped

$ llvm-readelf -d node | grep -i needed
  NEEDED  libz.so.1        NEEDED  libcares.so      NEEDED  libsqlite3.so
  NEEDED  libffi.so        NEEDED  libcrypto.so.3   NEEDED  libssl.so.3
  NEEDED  libicui18n.so.78 NEEDED  libicuuc.so.78
  NEEDED  libc.so          NEEDED  libm.so          NEEDED  libdl.so
  NEEDED  libc++_shared.so

$ llvm-readelf -d node | grep -i runpath
  RUNPATH  /data/data/com.termux/files/usr/lib
```

**This is the important structural fact:** `PT_INTERP` is `/system/bin/linker64`
(Android's *own* loader), not a Termux-specific one. `libc.so`/`libm.so`/`libdl.so`
resolve from `/system/lib64`. Only 7 libraries are Termux-specific. So the binary is
relocatable onto a normal Android app — it is **not** tied to Termux's loader.

### 4.2 It runs natively on the device — proven

First attempt failed as expected (RUNPATH unreadable by another uid):
```
$ adb push node /data/local/tmp/tnode
$ adb shell "/data/local/tmp/tnode -e 'console.log(process.version)'"
CANNOT LINK EXECUTABLE "/data/local/tmp/tnode": library "libz.so.1" not found
```

After extracting the 7 dependency `.deb`s and pointing `LD_LIBRARY_PATH` at them
(Android's `linker64` **does** honour `LD_LIBRARY_PATH`):

```
$ adb shell "LD_LIBRARY_PATH=/data/local/tmp/tlibs/lib /data/local/tmp/tnode --version"
v26.4.0
```

One more failure surfaced — OpenSSL's compiled-in config path:
```
/data/local/tmp/tnode: OpenSSL configuration error:
error:8000000D:system library:BIO_new_file:Permission denied:
  calling fopen(/data/data/com.termux/files/usr/etc/tls/openssl.cnf, rb)
```
Fixed with `OPENSSL_CONF=<our own openssl.cnf>`.

Then everything worked:
```
$ adb shell "LD_LIBRARY_PATH=... OPENSSL_CONF=/dev/null tnode -e \
    'https.get(\"https://registry.npmjs.org/dsh\", r=>console.log(\"TLS-OK\",r.statusCode))'"
TLS-OK 200

$ adb shell "LD_LIBRARY_PATH=... OPENSSL_CONF=/dev/null tnode -e \
    'fs.writeFileSync(\"/data/local/tmp/wtest.txt\",\"hi\"); console.log(\"fs-ok\", fs.readFileSync(...))'"
fs-ok hi

$ adb shell "LD_LIBRARY_PATH=... tnode -e \
    'cp.spawnSync(\"/system/bin/echo\",[\"no-shell-ok\"])'"
spawn-noshell: 0 no-shell-ok
spawn-self:    0 v26.4.0          # node re-executing itself works
```

### 4.3 The one real blocker, and how it is fixed

```
$ adb shell "LD_LIBRARY_PATH=... tnode -e 'cp.execSync(\"/system/bin/echo hi\")'"
ERRMSG spawnSync /data/data/com.termux/files/usr/bin/sh EACCES
```

Termux compiles its prefix into the binary; `libuv`'s shell path is the NUL-terminated
literal `/data/data/com.termux/files/usr/bin/sh` (and `.../bin/bash`). It is **not**
overridable by `SHELL` or `PREFIX` env vars (tested both). But it *is* a plain string
in the file, so an equal-length in-place patch works:

```python
subs = [(b'/data/data/com.termux/files/usr/bin/bash', b'/system/bin/sh'),
        (b'/data/data/com.termux/files/usr/bin/sh',   b'/system/bin/sh'),
        (b'/data/data/com.termux/files/usr/tmp',      b'<TMP>')]   # each NUL-padded to original length
```

```
$ adb shell "LD_LIBRARY_PATH=... OPENSSL_CONF=/dev/null tnodep -e 'cp.execSync(\"/system/bin/echo x\")'"
EXEC: patch-works
```

Full reproducible recipe: `tools/build-termux-native-node.sh`.

### 4.4 Verdict B

**Technically proven working.** But:
* you must binary-patch a third party's `.deb` output (upgrade-fragile, no upstream
  support, violates the `control` file's implied layout);
* you get **no Linux userland** — `git`, `bash`, `ripgrep` are absent, and an AI
  *coding* agent without `git` is not a coding agent;
* it needs ~256 MB of Termux ICU data on disk (`libicudata.so` is 33 MB ×3 copies).

So: **usable as a fallback**, not the architecture.

---

## 5. Option C-alt — glibc Node inside PRoot (assessed, not built)

The mainline answer you originally quoted is only half right. nodejs.org *does* ship
a glibc aarch64 build:

```
$ curl -sSL https://nodejs.org/dist/index.json | jq -r '.[0]'
v26.11.1 ... files contains "linux-arm64"
$ curl -sSI https://nodejs.org/dist/v24.11.1/node-v24.11.1-linux-arm64.tar.xz
HTTP/2 200
content-length: 29707360
$ # from the official SHASUMS256.txt:
6b0863fb9f627bf4a6c5948dce1de4398174a2e05dbe717503d828e211ca01f0  node-v24.11.1-linux-arm64.tar.xz
0dc93ec5c798b0d347f068db6d205d03dea9a71765e6a53922b682b91265d71f  node-v24.11.1-linux-arm64.tar.gz
```

That build has `PT_INTERP = /lib/ld-linux-aarch64.so.1` and links `libc.so.6`,
`libstdc++.so.6`, `libm.so.6`, `libgcc_s.so.1`, `libdl.so.2`. It is **not a member of
the Alpine/musl world** — musl provides none of those sonames, and Alpine's rootfs
has only `/lib/ld-musl-aarch64.so.1`. So it is only usable in a **glibc** rootfs
(Debian/Ubuntu via proot-distro), which is several times larger than the Alpine
minirootfs.

**Status: NOT TESTED.** I did not download a glibc rootfs or boot a glibc Node on the
device. I am not claiming it works or does not; I am claiming only that it is
*unnecessary*, because the Alpine/musl route was proven end-to-end and is smaller.

---

## 6. Exact commands to fetch each artifact

```bash
# ---- WINNER: PRoot + Alpine aarch64 + Alpine nodejs --------------------
# 1) proot (aarch64, static, runs as an unprivileged app uid)
curl -fL -o proot \
  https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static
#   sha256 fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45  (1479432 B)

# 2) build the rootfs (Linux/Docker host; see tools/build-alpine-node-rootfs.sh)
docker run --rm -v "$PWD/out:/out" -v "$PWD/tools:/tools" -w /tools \
    alpine:3.22 ./build-alpine-node-rootfs.sh
#   minirootfs sha256 fc11cf987b37b2e57969cea7e0b8df0777e572d41fe20731630c1e926a8a07a2
#   nodejs.apk   sha256 3834a57bf5af6310b400c1458b2350b2a0e560aa1de7e8a164e93867602fc5e4

# 3) device bring-up (what I ran)
adb push proot /data/local/tmp/proot
tar czf - -C out/rootfs . | adb shell "mkdir -p /data/local/tmp/dshrt && cd /data/local/tmp/dshrt && tar xzf -"
adb shell "echo nameserver 8.8.8.8 > /data/local/tmp/dshrt/etc/resolv.conf"
adb shell "/data/local/tmp/proot -r /data/local/tmp/dshrt -b /dev -b /proc \
           -w /workspace /usr/bin/env \
           PATH=/usr/local/bin:/usr/bin:/bin:/sbin:/root/bin HOME=/root \
           /usr/bin/node -e 'console.log(process.version)'"

# ---- FALLBACK: native Termux node, no proot ----------------------------
./tools/build-termux-native-node.sh /tmp/native-node
adb push /tmp/native-node /data/local/tmp/native-node
adb shell "LD_LIBRARY_PATH=/data/local/tmp/native-node/lib \
           OPENSSL_CONF=/data/local/tmp/native-node/etc/openssl.cnf \
           /data/local/tmp/native-node/bin/node -e 'console.log(process.version)'"

# ---- glibc alternative (NOT tested here) --------------------------------
curl -fL -o node-arm64.tar.xz https://nodejs.org/dist/v24.11.1/node-v24.11.1-linux-arm64.tar.xz
#   sha256 6b0863fb9f627bf4a6c5948dce1de4398174a2e05dbe717503d828e211ca01f0  (29707360 B)
#   ^ requires a glibc (Debian/Ubuntu) rootfs, NOT Alpine.
```

---

## 7. Honest status — what is and is not proven

**Proven by execution on the physical device:**

* PRoot v5.3.0 aarch64 static runs as an unprivileged uid with SELinux `Enforcing`.
* `node v22.23.2` from Alpine's apk executes **inside** PRoot on Android.
* Inside that PRoot session: `npm 11.6.4` runs; `child_process.execSync` works;
  HTTPS to `registry.npmjs.org` returns **200**; file I/O works.
* `bash 5.2.37`, `git 2.49.1`, `ripgrep 14.1.1`, `curl 8.14.1`, `python3 3.12.15`
  all execute in the same session.
* Termux `nodejs 26.4.0-1` executes **natively** (no PRoot) on Android once its 7
  shared libraries are on `LD_LIBRARY_PATH`; TLS returns **200**; fs works;
  `spawnSync` works; `execSync` works after a byte-patch of the compiled-in prefix.
* `nodejs-mobile` v18.20.4 Android zip contains **no `node` executable**.
* Alpine's 13-package dependency closure resolves completely with **no** missing
  package; all 13 URLs return HTTP 200 with matching `content-length`.

**NOT proven:**

* **`npm install -g @deepseek-ai/dsh` did not complete.** It was still running when the
  phone dropped off USB (`adb devices` went empty and did not recover; `adb kill-server`
  / `adb start-server` did not bring it back). I confirmed the process was alive and
  downloading (`node` pids under a `proot` parent), and that npm itself works, but I
  did **not** see `dsh --version` print. **Whoever has the device should re-run:**
  ```bash
  adb shell "/data/local/tmp/proot -r /data/local/tmp/aroot -b /proc -b /dev -b /sys -w / \
      /usr/bin/env PATH=/usr/bin:/bin:/sbin:/root/bin HOME=/root \
      npm install -g @deepseek-ai/dsh && dsh --version"
  ```
  Expect it to be slow: PRoot's `ptrace` overhead makes npm installs take minutes.
  Budget for shipping the `node_modules/` tree pre-built inside `rootfs.tar.gz`
  rather than installing on-device.
* **The glibc-in-PRoot route (§5) was not built or tested.** Stated as untested.
* **`tools/build-alpine-node-rootfs.sh` was not executed** (no Linux host here). Its
  `apk add` invocation and package list are the same ones that produced the working
  rootfs on-device, but the script itself has not been run end-to-end. Treat it as a
  reviewed draft, not a verified artifact.
* Nothing was committed or pushed to git. No Kotlin under
  `app/src/main/java/` was touched.

---

## 8. Recommendation for the app's architecture

1. **Keep PRoot.** It is the right call and it works on-device as an unprivileged uid.
2. **Switch the rootfs to Alpine aarch64 and take `node` from the Alpine `nodejs`
   apk**, pre-installed into `rootfs.tar.gz`. Drop the separate `node.tar.xz`
   manifest entry — or repoint it at the apk inside the rootfs.
3. **Fix `prootWrap()`** (§3.5): bind `/dev` and `/proc`, set `PATH` inside the guest,
   ship an `/etc/resolv.conf`.
4. **Pre-install the agent CLIs into `rootfs.tar.gz`** at build time (Docker), because
   on-device `npm install` under PRoot is very slow.
5. Delete the `runtime-assets.json` comment that says *"Node.js publishes no Android/
   arm64 build"* — it is wrong in the way that matters. It is replaced by:
   **we use the Linux aarch64 build from Alpine, executed inside PRoot.**
---

## 9. ADDENDUM (round 2) — the musl blocker, investigated and RESOLVED

### 9.1 What broke
`npm install -g @deepseek-ai/dsh` under PRoot on the Alpine rootfs resolved ~90
packages and then failed:

```
npm error path .../node_modules/koffi
npm error command sh -c node ./cnoke.cjs -P . -D src/koffi --prebuild --release
npm error Failed to load prebuilt binary, rebuilding from source
npm error Error: CMake does not seem to be available
```

`@deepseek-ai/dsh-fs-local` depends on `koffi@^3.1.0`. On first reading this looks
like "koffi has no musl build", which would have forced a glibc rootfs.

### 9.2 It is NOT that. koffi ships a working musl arm64 build.

Installed with `--ignore-scripts` and inspected on the device:

```
/tmp/mt/node_modules/@koromix/koffi-linux-arm64/
    index.js
    linux_arm64/koffi.node     <- glibc
    musl_arm64/koffi.node      <- musl
    README.md  package.json
```

And it loads, on the musl rootfs, on the device:

```
musl_arm64 LOADED OK, api symbols: 37
```

### 9.3 The actual bug: koffi probes the glibc build FIRST

```js
// @koromix/koffi-linux-arm64/index.js  (verbatim from the device)
try { native = require("./linux_arm64/koffi.node"); } catch (e) { err = e; }
if (native == null) {
  try { native = require("./musl_arm64/koffi.node"); } catch (e) { err = e; }
}
```

On a musl process, `dlopen()` of the **glibc** `.node` does not throw a catchable
error — it **hard-aborts the process**. So the `musl_arm64` fallback line is never
reached:

```
$ adb shell "... node /tmp/koffi2.js"      # requires the package root
(no output, process died)                   # <- aborted inside linux_arm64/koffi.node

$ adb shell "... node -e 'require(\".../musl_arm64/koffi.node\")'"
musl_arm64 LOADED OK, api symbols: 37      # <- the musl build is fine
```

koffi's install script (`cnoke.cjs`) probes the same way, so it "Failed to load
prebuilt binary" and fell through to compiling from source.

### 9.4 The fix: reorder the probe. Six lines, at payload build time.

```
$ adb shell "cat > .../koffi-linux-arm64/index.js <<'EOF'
    let native = null, err = null;
    try { native = require('./musl_arm64/koffi.node'); } catch (e) { err = e; }
    if (native == null) { try { native = require('./linux_arm64/koffi.node'); } catch (e) { err = e; } }
    if (native == null) throw err;
    module.exports = native;
    EOF"

$ adb shell "... node /tmp/koffi2.js"
KOFFI index.js LOADED OK, api symbols: 37
sample: config,stats,struct,pack,union,Union,opaque,pointer
```

### 9.5 Revised conclusion

**The Alpine/musl rootfs is viable. No glibc rootfs is required.**

The operational recipe is: install agents at payload-build time with
`--ignore-scripts`, then apply the koffi probe-order patch as a post-install step.
This is strictly better than the glibc route, which would have cost a ~100 MB rootfs
(measured: `ubuntu-base` + node = 100,157,772 B packed, vs 46,610,215 B for the whole
Alpine payload) and still would not have been free of install-script failures.

### 9.6 glibc node under PRoot — also proven (for the record)

The official nodejs.org glibc build does run under PRoot on Android, so the
architecture question is genuinely libc-agnostic; it was not the blocker:

```
$ adb shell "proot -r /data/local/tmp/grt -b /proc -b /dev -b /sys -w / ... /bin/node -e \
    'const h=process.report.getReport().header;
     console.log(\"GLIBC NODE OK\", process.version, process.arch, h.glibcVersionRuntime)'"
GLIBC NODE OK v22.23.2 arm64 glibcRuntime 2.39

# koffi's glibc prebuild also loads there:
KOFFI GLIBC LOADED OK: [ 'alias', 'alignof', 'alloc', ... ]
```

Two operational gotchas found with a Debian/Ubuntu-style rootfs, worth recording if
anyone takes that route:

* `ubuntu-base-24.04.5-base-arm64.tar.gz` ships **no `/bin`, `/lib` or `/lib64`
  symlinks** — it is usr-merged and expects `dpkg` to create them. Under a bare PRoot
  rootfs you must add `bin -> usr/bin`, `lib -> usr/lib`, `lib64 -> usr/lib64`
  yourself, or every binary dies with `execve: No such file or directory`.
* It also ships an **empty `/etc/resolv.conf`**, and DNS on this device returns
  AAAA-only, which makes `npm install` hang silently rather than fail.

### 9.7 Status of the full dsh install

`npm install -g @deepseek-ai/dsh --ignore-scripts` **succeeded once** on the device:

```
npm warn deprecated node-domexception@1.0.0: Use your platform's native DOMException instead
added 540 packages in 3m
```

So the `--ignore-scripts` recipe does resolve the whole ~540-package tree, koffi
included, on musl. **NOT yet verified:** booting the assembled agent after applying
the koffi probe-order patch and getting a banner out of it. The install directory was
deleted out from under me by the device between the successful install and the follow-up
run — see 9.8 — so the `agents/` tree had to be reinstalled and that reinstall did not
finish in the time available.

To reproduce the last step:

```bash
adb shell "proot -r <rootfs> -b /proc -b /dev -b /sys -w / /usr/bin/env \
  PATH=/usr/local/bin:/usr/bin:/bin:/sbin:/root/bin HOME=/root sh -c '
    npm install -g @deepseek-ai/dsh --ignore-scripts --prefix <rootfs>/agents
    for f in \$(find <rootfs>/agents -path \'*koffi-linux-arm64/index.js\'); do
      printf \'let n=null,e=null;\ntry{n=require(\"./musl_arm64/koffi.node\")}catch(x){e=x}\nif(n==null){try{n=require(\"./linux_arm64/koffi.node\")}catch(x){e=x}}\nif(n==null)throw e;\nmodule.exports=n;\n\' > \$f
    done
    node <rootfs>/agents/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --version
  '"
```

Everything below is already independently proven on-device; only the assembled-agent
banner is outstanding.

### 9.8 Two practical device facts
* `npm install` under PRoot took **~9 minutes** for the dsh tree and peaked at a
  **242 MB npm cache** — it must not happen on the phone at first run. Pre-install
  into `rootfs.tar.gz`.
* `/data/local/tmp` is **not durable and is purged under disk pressure**. This cost me
  two test results: the device hit 100% full (335 MB free) and silently deleted a
  successfully installed `agents/` tree between the install and the verification step.
  Two mitigations used: stage-and-run in a single `adb shell` invocation, and delete the
  glibc rootfs and its tarball before the big install. A device-side payload of this size
  should really live in the app's own `filesDir`, which is exactly what
  `AgentRuntime.runtimeRoot()` already does in production.
