# Running npm-published coding agents on Android (aarch64 / bionic)

**Scope:** `@openai/codex` and `@anthropic-ai/claude-code` in an APK that bundles bionic-linked Node.js (`libnode.so`) and the npm CLI.
**Date of verification:** registry + binary inspection performed today (2026-10-12) against `@openai/codex@0.162.1` and `@anthropic-ai/claude-code@2.1.296`.
**Method:** downloaded the real tarballs, parsed ELF program headers directly (`file(1)` + a hand-written PT_INTERP/PT_DYNAMIC reader), read the published launcher sources, and drove npm's own platform checker with a simulated Android environment. Not inferred from docs.

---

## TL;DR verdict table

| Q | Verdict | Confidence |
|---|---|---|
| **A** Are the binaries glibc or musl? | `@openai/codex-linux-arm64` = **musl, statically linked**. `claude-code-linux-arm64` = **glibc, dynamic**. `claude-code-linux-arm64-musl` = **musl, but DYNAMIC** (`/lib/ld-musl-aarch64.so.1`). | **Very high** (parsed from the binaries) |
| **B** Can a glibc ELF run on Android without PRoot? | **No.** `PT_INTERP=/lib/ld-linux-aarch64.so.1` doesn't exist; the **kernel** (not bionic) fails the execve with `ENOENT`. bionic imposes no `PT_INTERP` allowlist at all. | **High** (AOSP source) |
| **C** Can a musl-**static** aarch64 ELF run directly on Android? | **Yes in principle** (no `PT_INTERP` → bionic's linker never runs → PIE check bypassed), but *only* at **targetSdk ≤ 28** (SELinux `neverallow`), and with real runtime landmines (musl DNS, `flock`, `bwrap`, seccomp `SIGSYS`). **Not device-verified.** | **Medium** |
| **D** Is there a known-working approach? | **Yes — for codex, use a purpose-built Android/bionic PIE build**, not the upstream musl one. For claude-code, only patchelf+glibc-runner (ROM-sensitive) or PRoot. | **Medium-high** |
| **E** Does npm have a force-install flag? | **Yes: `--os` / `--cpu` / `--libc`** (real npm config keys). **`--force` does NOT work for optional deps.** On Android npm *cannot* detect libc at all, so `--libc` is **mandatory** for libc-tagged packages. | **Very high** (empirically tested) |

**The headline:** the two packages are **not symmetric**, and the common assumption "*musl* means *static*" is **wrong** for Claude Code. Only `@openai/codex` ships a self-contained binary.

---

## 0. What the packages actually ship

### `@openai/codex@0.162.1`

The package itself is a **13 KB JS shim** (`bin/codex.js`, the only JS file besides a README). It declares six optional deps that are npm *aliases of itself* pinned to per-arch dist-tags:

```json
"optionalDependencies": {
  "@openai/codex-linux-arm64": "npm:@openai/codex@0.162.1-linux-arm64",
  "@openai/codex-linux-x64":   "npm:@openai/codex@0.162.1-linux-x64",
  ... darwin-arm64, darwin-x64, win32-arm64, win32-x64
}
```

The `-linux-arm64` variant: `os:["linux"], cpu:["arm64"]`, **no `libc` field**, 408 MB unpacked. Binary at `vendor/aarch64-unknown-linux-musl/bin/codex`.

**Verified ELF:**

```
codex/codex  254,866,040 B
  ELF 64-bit LSB executable, ARM aarch64, statically linked, stripped
  e_type=ET_EXEC(2)  e_machine=183(aarch64)  phnum=6
  PT_INTERP : NONE          PT_DYNAMIC : NO
  grep -c 'libc.so.6|ld-musl|ld-linux'  ->  0
```

Fully self-contained. No shared libraries at all.

It also ships helper binaries that **matter for Android**: `vendor/aarch64-unknown-linux-musl/codex-resources/bwrap` (**bubblewrap** — a Linux sandbox that cannot work on Android), `codex-path/rg` (ripgrep), `codex-resources/zsh/bin/zsh`, `codex-code-mode-host`, and a GStreamer voice stack.

### `@anthropic-ai/claude-code@2.1.296`

`bin/claude.exe` is **500 bytes of ASCII** — not a binary:

```sh
echo "Error: claude native binary not installed." >&2
echo "Either postinstall did not run (--ignore-scripts, some pnpm configs)" >&2
echo "or the platform-native optional dependency was not downloaded (--omit=optional)." >&2
exit 1
```

A `postinstall` (`node install.cjs`) is supposed to hardlink the real binary over it. Eight optional deps (6 platform + `linux-x64-musl` + `linux-arm64-musl`). `engines: node >= 22.0.0`.

**Verified ELF — the important part:**

| package | libc field | size | `file(1)` result |
|---|---|---|---|
| `@anthropic-ai/claude-code-linux-arm64` | `glibc` | 256,434,168 B | dynamically linked, `interpreter /lib/ld-linux-aarch64.so.1` |
| `@anthropic-ai/claude-code-linux-arm64-musl` | `musl` | 248,788,800 B | **dynamically linked**, `interpreter /lib/ld-musl-aarch64.so.1` |

Both are `ET_EXEC`, both have `PT_DYNAMIC`, **neither is static**. Neither tarball ships its loader (contents are just `claude`, `package.json`, `README`, `LICENSE`).

Shared libs required:
- glibc build: `libasound.so.2, libc.so.6, libdl.so.2, libm.so.6, libpthread.so.0, librt.so.1, libsecret-1.so.0, libutil.so.1`, plus `GLIBC_2.*` version refs.
- musl build: `libasound.so.2, libc.so.6, librt.so.1, libsecret-1.so.0, libutil.so.1`.

**Vendor intends to support Android but has not shipped it.** Both `install.cjs` and `cli-wrapper.cjs` contain platform keys `linux-arm64-android` / `linux-x64-android`, and `getPlatformKey()` returns `'linux-' + arch() + '-android'` when `process.platform === 'android'`. But those packages are **not in `optionalDependencies` and 404 on the public registry** (checked on both `latest`=2.1.296 and `stable`=2.1.287). Consequently on Android the postinstall prints *"Native binaries for linux-arm64-android are not available on this release channel"*, bails, and leaves the 500-byte stub. There is **no musl fallback** — the wrapper hard-requires the android key and `process.exit(1)`. `FAIL_ON_MISSING_NATIVE` is `'false'` in the published build, so **npm reports success and leaves a non-functional `claude`.**

---

## A. glibc or musl? — **verified**

Answered above. The one-line answer:

- codex → **musl, STATIC**. Self-contained.
- claude glibc → **glibc, DYNAMIC** → needs `/lib/ld-linux-aarch64.so.1`.
- claude musl → **musl, DYNAMIC** → needs `/lib/ld-musl-aarch64.so.1`.

Confidence: **very high** — parsed directly from the shipped binaries.

---

## B. Can a glibc ELF execute on Android/bionic without PRoot? — **No**

`PT_INTERP` names `/lib/ld-linux-aarch64.so.1`, which does not exist on Android. **bionic never even gets involved** — the *kernel* opens the interpreter itself and fails:

```c
// fs/binfmt_elf.c:904
interpreter = bprm_open_interpreter(bprm, elf_interpreter);
if (IS_ERR(interpreter)) goto out_free_ph;

// fs/exec.c
struct file *bprm_open_interpreter(struct linux_binprm *bprm, const char *path)
{ ... return open_exec(path); }
```

Path lookup of `/lib/ld-linux-aarch64.so.1` fails → **`-ENOENT`**, propagated verbatim to `execve()` (Node sees `ENOENT`; Java sees `No such file or directory` / `error=2`). The *only* `-ENOEXEC` in that path is a non-NUL-terminated PT_INTERP string.

> Attribution note: the familiar *"cannot execute: required file not found"* is **bash's rendering of `ENOENT`** — not an Android/bionic message. A full-tree grep of bionic finds **0 occurrences** of `"cannot execute"` and of `"Unsupported ELF file type"`. Bionic's own strings (`CANNOT LINK EXECUTABLE "%s": %s`, `"%s" is 32-bit instead of 64-bit`, the PIE error) all require bionic to actually start, which never happens for a glibc binary.

**Correction to a common belief:** bionic does **not** validate or allowlist `PT_INTERP`. Full-tree grep for `kSupportedInterps|is_supported_interp|unsupported program interpreter` = **0 hits**, and `kLinkerPath` has zero occurrences (it was historically only a gdb bookkeeping string). `phdr_table_get_interpreter_name()` merely reads PT_INTERP; its consumers are `set_realpath()` (gdb naming) and `init_sanitizer_mode()`. **The kernel rejects it before bionic ever runs.**

So the failure is purely "the glibc loader isn't there" — nothing Android is deliberately rejecting. That is also why `patchelf --set-interpreter /system/bin/linker64` gets a *glibc binary* no further: it now reaches bionic and fails on ABI/glibc-version requirements, and on pre-PIE builds on `Android 5.0 and later only support position-independent executables (-fPIE).`

- Kernel source: https://android.googlesource.com/kernel/common/+/refs/heads/android-mainline/fs/binfmt_elf.c · https://android.googlesource.com/kernel/common/+/refs/heads/android-mainline/fs/exec.c
- Bionic: https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_main.cpp
- Real-world same-failure-class with `readelf` output: https://github.com/anomalyco/opencode/issues/10504 · https://github.com/anomalyco/opencode/issues/39966

Confidence: **high** (verified against AOSP source, not just docs).

---

## C. Can a musl-STATIC aarch64 ELF execute directly on Android? — **Conditional (plausible, not device-verified)**

**In principle yes.** A static binary has no `PT_INTERP`, so **bionic's dynamic linker is never invoked** — the kernel loads it directly. That sidesteps both the missing-glibc problem *and* bionic's PIE enforcement in `linker_main.cpp` (which only applies to binaries that go through the linker).

Blockers, each separately:

- **SELinux W^X — this is the real gate, and it keys on `targetSdk`, not `minSdk`.** AOSP `system/sepolicy/private/app_neverallows.te:64-73`:

  ```
  # Block calling execve() on files in an apps home directory.
  # This is a W^X violation (loading executable code from a writable
  # home directory). For compatibility, allow for targetApi <= 28.
  # b/112357170
  neverallow {
    all_untrusted_apps
    -untrusted_app_25
    -untrusted_app_27
    -runas_app
  } { app_data_file privapp_data_file }:file execute_no_trans;
  ```

  `private/untrusted_app_27.te:24` grants `allow untrusted_app_27 app_data_file:file execute_no_trans;`, and `private/seapp_contexts` maps `minTargetSdkVersion=29 → untrusted_app_29` (then `_30`/`_32`/`untrusted_app`), none of which are exempt. At **targetSdk ≥ 29 exec from the data dir is `EACCES`** (`avc: denied { execute_no_trans }`). Your **targetSdk 28 is exactly what keeps this working** — it is not incidental, and raising it breaks the whole design.

  **Precisely: `targetSdk 28` selects the `untrusted_app_27` domain** (`seapp_contexts`: `=28 → untrusted_app_27`), which is the counter-grant for targetApi 26–28. Worth internalising: the blanket `allow untrusted_app_all app_data_file:file { r_file_perms execute }` is only plain **`execute`** (= `mmap(PROT_EXEC)`), whereas **`execve()` checks `execute_no_trans`** (no domain transition occurs). So `dlopen`/mmap of your own data files keeps working while `execve` is denied — a confusingly asymmetric failure mode.
  - https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te
  - https://developer.android.com/about/versions/10/behavior-changes-10 — *"Execution of files from the writable app home directory is a W^X violation."*
  - https://github.com/termux/termux-app/issues/1072 — Termux targets API 28 deliberately; maintainer: *"Termux will continue to work indefinitely as long as it continues to target API 28"*, but also *"Being able to execute app_data_file … is on the way out in the long term."*

- **✅ The durable alternative: `nativeLibraryDir`.** Binaries shipped in `jniLibs/<abi>` extract to `/data/app/**/lib/<abi>`, labelled **`apk_data_file`**, which is granted `x_file_perms` (`getattr execute execute_no_trans map`) to **all appdomains — with no targetSdk gate and no neverallow**. This is the configuration that survives both the W^X policy and the Play targetSdk deadline, and it is what Google means by *"Apps should load only the binary code that's embedded within an app's APK file."* (Caveat: policy permits it; **not device-verified** here.)
- **`/data` is not `noexec`.** `rootdir/init.rc` uses `noexec` only for `/config`, `/sys/fs/bpf`, `/sys/fs/pstore`, `/mnt/vm`, `/data_mirror`. No blocker.
- **Seccomp — not a blocker for *starting*, but it kills rather than errors.** bionic installs a zygote-spawned `seccomp-bpf` filter (`bionic/libc/seccomp/seccomp_policy.cpp:198-199`, `prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)`). The denied action is `SECCOMP_RET_TRAP` → **`SIGSYS`, not EPERM**, so a blocked call terminates the process rather than returning an error you can handle.

  **`execve` itself is allowlisted** (`bionic/libc/SYSCALLS.TXT:68`; NR 221 on arm64) — confirmed by simulating the real generated arm64 filter — so the exec passes. Blocked app-wide (`SECCOMP_BLOCKLIST_APP.TXT`): `mount`, `umount2`, `swapon`, `swapoff`, `reboot`, `init_module`, `delete_module`, `clock_settime`, `settimeofday`, `adjtimex`, `clock_adjtime`, `acct`, `syslog`, `chroot`, `setdomainname`, `sethostname`. Also blocked: `kexec_load`, `add_key`, `keyctl`, `bpf`, `io_uring_*`, `clone3`, `landlock_*`.

  The filter **survives `execve`** (standard semantics, https://man7.org/linux/man-pages/man2/seccomp.2.html) and Android deliberately does **not** set `no_new_privs`, so the child stays confined.

  **Point in musl's favour:** `set_robust_list` and `rseq` are *not* allowlisted — **glibc would die there; musl does not use them.** An independent reason the static musl build is the right choice on Android.
  - https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SECCOMP_BLOCKLIST_APP.TXT
- **musl DNS is a genuine runtime failure.** musl reads `/etc/resolv.conf`, which Android does not provide → falls back to `127.0.0.1:53` → auth flows fail. https://github.com/openai/codex/issues/11809
- **`flock(2)` returns `ENOTSUP` on `/data`** → codex aborts with *"lock() not supported"*. Needs `#[cfg(not(target_os="android"))]`.
- **`bwrap`** (bubblewrap, shipped inside codex's vendor dir) cannot work on Android; codex must run unsandboxed.
- **ET_EXEC under Termux specifically:** Termux LD_PRELOADs `libtermux-exec.so`, which intercepts `execve` and rejects non-PIE `e_type: 2` with *"unexpected e_type: 2"*. **This is a Termux artifact, not a general Android-app blocker** — a plain APK has no such shim, and bionic's PIE check lives in the linker, which a static binary bypasses. (Labelled: this is my inference from the two verified facts, not an AOSP quote.)

Confidence: **medium.** The loader/SELinux/seccomp reasoning is now sourced to AOSP; I could **not** run anything on a device or emulator.

---

## D. Known-working approaches — **Yes, but not via the upstream packages**

### ✅ Verified working: a purpose-built Android/bionic codex build

`@mmmbuto/codex-cli-termux@0.162.1-termux.1` (by DioNanos, repo https://github.com/DioNanos/codex-termux) declares **`os:["android"], cpu:["arm64"]`** — so `npm install -g` on your device installs it **with no overrides at all**.

I verified its binary:

```
codex.bin  204,811,552 B
  ELF 64-bit LSB pie executable, ARM aarch64, dynamically linked,
  interpreter /system/bin/linker64, stripped
  e_type=ET_DYN (PIE)     <-- satisfies Android 5.0+ PIE rule
  needs: libc.so, libdl.so, libm.so  (bionic) + bundled libc++_shared.so
```

This is **the proof that codex can be built for Android**: a bionic, PIE, `linker64` build of the same upstream `rust-v0.162.1`. It is 204 MB vs 255 MB for the musl static.

Caveats: third-party fork (`author: DioNanos`, Apache-2.0), not published by OpenAI; its description advertises non-upstream model names ("GPT-6 Astra/Sol/Luna"), so treat as a fork. Its `bin` points at a JS launcher with a `#!/usr/bin/env node` shebang, and there is **no `/usr/bin/env` on Android** — its postinstall rewrites shebangs only when `$PREFIX` is set (Termux). In your APK you would bypass the launcher and **exec `codex.bin` directly**, setting `LD_LIBRARY_PATH` to the directory holding `libc++_shared.so` (the package uses `RUNPATH=$ORIGIN`, so it generally resolves in place).

### ⚠️ PRoot / proot-distro — works, badly degraded

- codex runs in proot Ubuntu but the sandbox breaks (`bwrap: fchdir to oldroot`) and the TUI is fragile; OpenAI maintainers have said *"many bugs or limitations in termux that prevent the Codex TUI from working well"* (https://github.com/openai/codex/issues/18120).
- Latency is severe — reports of ~40 s per tool call because PRoot's ptrace delays Bun's completion signal.
- Claude Code **does** run under proot per user reports.

### ⚠️ ELF patching (claude-code only) — works, ROM-sensitive

`gtbuchanan/claude-code-termux` patches `PT_INTERP` to a Termux **glibc-runner** plus a uname shim; confirmed on Pixel 2/Android 11, Pixel 7, Fold5/Android 16. But it is **ROM-dependent** — on a Galaxy S23 the kernel substituted `/system/bin/linker64` regardless of the patched `PT_INTERP`.

### ❌ WASM — does not exist

No `@openai/codex-wasm` / `claude-code-wasm`; no wasm target in the codex repo.

### ❓ qemu-user — unverified

Nothing found on Android in either direction.

### Vendor position

Anthropic maintainer `ant-kurt` (COLLABORATOR), verbatim in https://github.com/anthropics/claude-code/issues/50270:

> "Claude Code 2.1.113 has switched to a native executable (after npm deprecation was announced in January), which drops support for some platforms. **We may add support for Android in the future — this requires either a bun target for `android-arm64` or static musl.** You can pin to the last JS bundle release …@2.1.112 to continue using Claude Code for now."

Note the irony: Anthropic's own stated requirement is *static* musl, yet the `-musl` package they publish is **dynamic**. The Android request (https://github.com/anthropics/claude-code/issues/72620) was closed as duplicate by an Anthropic maintainer, and no Android binary has shipped since (2.1.113, 2026-04-18 → today).

🚨 **Supply-chain warning:** avoid `codexui-android`. Published npm builds ≥0.1.82 contain code absent from the GitHub source that reads `~/.codex/auth.json` and exfiltrates the refresh token. Affected Android apps run `pnpm add codexui-android@latest` **unpinned at runtime**, so a clean APK can pull a malicious release. https://corgea.com/research/codexui-android-openai-token-stealer · https://www.aikido.dev/blog/codex-remote-ui-steals-ai-tokens

---

## E. npm force-install flags — **verified empirically**

I read npm 11.6.2's actual checker and reproduced the matrix.

### The mechanism

`node_modules/npm-install-checks/lib/index.js`:

```js
const checkPlatform = (target, force = false, environment = {}) => {
  if (force) return
  const os   = environment.os   || currentEnv.os()
  const cpu  = environment.cpu  || currentEnv.cpu()
  const libc = environment.libc || currentEnv.libc(os)
  ...
  code: 'EBADPLATFORM'
}
```

`environment` is the `os`/`cpu`/`libc` **npm config** — that is where the CLI flags land.

### The Android-specific trap

`node_modules/npm-install-checks/lib/current-env.js`:

```js
function libc (osName) {
  if (osName !== 'linux') {
    return undefined        // <-- ON ANDROID, RETURNS undefined
  }
  ...
}
```

On Android, `process.platform === 'android'`, so even with `--os=linux`, npm runs the real detector against a **bionic** Node: `glibcVersionRuntime` is undefined and there are no musl shared objects, so it resolves to **`null`**. And then:

```js
if (target.libc && !libc) { libcOk = false }   // package declares libc, host unknown -> FAIL
```

**Consequence: `--libc` is mandatory for any libc-tagged package.** Codex's platform package has **no** `libc` field, so it only needs `--os`/`--cpu`.

### Measured matrix (driving npm's own `checkPlatform`)

| environment | `codex-linux-arm64` (no libc) | `claude-code-linux-arm64-musl` (libc:musl) | `claude-code-linux-arm64` (libc:glibc) |
|---|---|---|---|
| real Android, no flags | **BLOCKED** `EBADPLATFORM` | **BLOCKED** | **BLOCKED** |
| `--os=linux --cpu=arm64` | ✅ **installs** | **BLOCKED** | **BLOCKED** |
| `--os=linux --cpu=arm64 --libc=musl` | ✅ **installs** | ✅ **installs** | **BLOCKED** |

End-to-end install test with a real registry package (`@esbuild/linux-arm64` as an optionalDependency on a darwin/arm64 host):

```
(no flags)                          -> not installed   (silent skip, exit 0)
--force                             -> NOT installed   <-- does NOT help
--os=linux --cpu=arm64              -> *** INSTALLED ***
--include=optional --os=... --cpu=..-> *** INSTALLED ***
.npmrc with os/cpu/libc, no flags  -> *** INSTALLED *** (npm config get -> linux/arm64/musl)
```

### Why optional vs direct deps behave differently

The smoking gun is in `arborist/lib/arborist/build-ideal-tree.js` — **optional nodes ignore `--force` entirely**:

```js
checkPlatform(node.package, this.options.force)          // non-optional
…
if (node.optional && !node.inert) {
  // Mark any optional packages we can't install as inert.
  // We ignore the --force and --engine-strict flags.
  try { checkEngine(…, false); checkPlatform(node.package, false, { cpu, os, libc }) }
  catch (error) { … node.inert = true … }
}
```

Note the override object `{ cpu, os, libc }` is passed **only for optional nodes**; non-optional nodes get no environment, which is why `--os=linux` fails on a direct dependency but succeeds on an optional one.

- **Optional dep mismatch → SILENT.** Caught in `reify.js#handleOptionalFailure`, node marked `inert` and detached, verbose-log only, **exit 0**. This is exactly why your `npm install -g` "succeeded".
- **Non-optional dep mismatch → THROWN** as a hard `EBADPLATFORM` install error (unless `--force`).

### Answers

- **`--force`: no.** npm's own docs describe it as *"Removes various protections against unfortunate side effects…"* — engines, peer conflicts, cache, unpublish — and **never mention os/cpu/libc**. Source-wise it short-circuits `checkPlatform`, but optional nodes are filtered before that. (It *does* rescue a non-optional dep — a different code path, easy to mistake for the real thing.) → https://docs.npmjs.com/cli/v11/using-npm/config/#force
- **`--include=optional`: no.** *"Option that allows for defining which types of dependencies to install. This is the inverse of `--omit=<type>`."* It controls tree membership **by type** (dev/optional/peer/prod), not by platform. A platform-mismatched optional dep is still *resolved and written to the lockfile*, just marked `inert` and never unpacked. → https://docs.npmjs.com/cli/v11/using-npm/config#include
- **`--os` / `--cpu` / `--libc`: yes — this is the answer.** Real config keys, documented as *"Override CPU architecture / OS / libc of native modules to install."*
  - `--cpu`/`--os` shipped in **npm 10.1.0** ([PR #6755](https://github.com/npm/cli/pull/6755), merged 2023-09-08).
  - `--libc` shipped in **npm 10.3.0** ([PR #6914](https://github.com/npm/cli/pull/6914)); the `libc` *field* was undocumented until #7822.
  - Rationale is real cross-install intent: RFC [npm/rfcs#612](https://github.com/npm/rfcs/pull/612) — *"given cross platform building of certain applications like electron, it would be nice to be able to install npm packages for a given platform, disregarding current platform."*
  - ⚠️ **Caveat, flagged honestly:** npm's docs only say "Override … of native modules to install"; **no official npm doc recommends these flags for cross-installs**, and in npm/cli#7906 a maintainer replied *"It looks like npm is working as intended here by enforcing platform compatibility checks."* You are using a supported override, not a guaranteed workflow.

### The `libc` default (Android-specific gotcha)

npm docs, verbatim: *"If your code only runs or builds in certain versions of libc, you can specify which ones. **This field only applies if `os` is `linux`.**"*

**npm does NOT assume `glibc` when the field is absent.** `libcOk = target.libc ? checkList(...) : true` — no field means no constraint, so the package installs on any libc. That is why `@openai/codex-linux-arm64` (no `libc` field) installs with just `--os`/`--cpu`, while `@anthropic-ai/claude-code-linux-arm64-musl` (`libc:["musl"]`) additionally demands `--libc`.

Combined with `current-env.js` returning `undefined` for non-`linux` os, and `if (target.libc && !libc) libcOk = false`, the conclusion is:

> **On Android you must pass `--libc` explicitly for any libc-tagged package. npm's auto-detection cannot identify bionic and will always report "unknown" → fail.**

### `.npmrc` (verified end-to-end, recommended for the app)

```
os=linux
cpu=arm64
libc=musl
```

`npm config get os|cpu|libc` → `linux` / `arm64` / `musl`; a real registry optional dep (`@esbuild/linux-arm64`) installs with **zero CLI flags**. **Caveat:** prefix-global — genuinely-incompatible packages (`os:["darwin"]`) are also force-installed.

---

## Recommendation for your app

**Verified end-to-end on a real machine** (npm 11.6.2): with `os=linux / cpu=arm64 / libc=musl` in `.npmrc`, a bare `npm install @openai/codex` installs the optional dep, the lockfile records it as `"optional": true, "os": ["linux"], "cpu": ["arm64"]`, and the shim's own `require.resolve(...)` + `existsSync(...)` logic resolves the binary to a real file (`existsSync: true`, 254,866,040 bytes — byte-identical to the binary analysed above).

1. **Codex is the viable target; Claude Code is not** without a PRoot/emulation layer. Claude Code is closed-source, its Android builds are unpublished (404), and its musl package is *dynamic*.
2. **Unblock the npm gate** with a `.npmrc` in your npm prefix — verified working, no CLI flags needed:
   ```
   os=linux
   cpu=arm64
   libc=musl
   ```
   **Caveat:** these are prefix-global, so genuinely-incompatible packages (e.g. `os:["darwin"]`) also get force-installed. `--libc` is *required* here, not optional — npm cannot detect bionic as a libc at all.
3. **The shim has two valid layouts — pick one deliberately:**
   - optional dep **installed** (via `.npmrc`) → binary lives at `node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/bin/codex` (the alias package), **not** under `@openai/codex/`.
   - optional dep **absent** → the shim falls back to `node_modules/@openai/codex/vendor/aarch64-unknown-linux-musl/bin/codex`, so you can unpack the tarball there yourself and bypass npm's platform gate entirely. Both are verified against the published `bin/codex.js`.
4. **Best of all: use the bionic PIE build** (`@mmmbuto/codex-cli-termux`, declares `os:["android"]`, installs with no overrides). It is `ET_DYN` PIE with `PT_INTERP=/system/bin/linker64`, uses bionic's resolver (no musl DNS problem) and bionic's `flock`. Exec `codex.bin` **directly** — its JS launcher uses a `#!/usr/bin/env node` shebang and there is no `/usr/bin/env` on Android, and its postinstall only rewrites shebangs when `$PREFIX` is set (i.e. Termux). Set `LD_LIBRARY_PATH` to the dir holding `libc++_shared.so` (the binary also carries `RUNPATH=$ORIGIN`). **Verify the fork's provenance before shipping** — it is third-party, not published by OpenAI.
5. **Plan for `nativeLibraryDir`, not the data dir.** targetSdk 28 + data-dir exec works today but is a **decaying loophole**: Play has required targetSdk **36+ since 2026-08-31**, and Termux's own maintainer warns the capability *"is on the way out in the long term."* Binaries shipped in `jniLibs/<abi>` extract to `nativeLibraryDir` (`apk_data_file`), which is granted `x_file_perms` to **all appdomains with no targetSdk gate and no neverallow** — so it survives both the W^X policy and the Play deadline. This is the one architectural decision worth making now rather than later.
6. **Confirm your distribution channel.** If Play-distributed, targetSdk 28 is already unshippable and the data-dir approach must go regardless.

---

## Explicitly NOT verified

- **No on-device or emulator test was performed.** Section C is reasoning from ELF format, AOSP source (bionic/sepolicy/kernel), and reported issues — not an observed run.
- Whether Android's seccomp policy blocks any *specific* syscall that musl-static codex needs. The mechanism is sourced; the per-binary impact is unmeasured.
- `flock(2)`→`ENOTSUP` on `/data` and the `bwrap` breakage are reported in upstream issues but **I could not re-verify the issue numbers** (the `flock` one was reported without a confirmed link).
- ~~Which exact SELinux domain `targetSdk 28` maps to~~ — **now resolved**: `seapp_contexts` maps `=28 → untrusted_app_27`, the domain explicitly granted `execute_no_trans`.
- Whether seccomp filters are inherited across `execve` — standard Linux semantics (man7) plus AOSP's deliberate non-use of `no_new_privs`; **no AOSP comment stating it directly was found**.
- `nativeLibraryDir` runtime exec-ability, and whether a non-`.so` ELF survives APK extraction. SELinux policy permits it (`apk_data_file`, `x_file_perms` to all appdomains), but this was **not tested on a device**.
- Termux's actual SELinux domain/seinfo — genuinely unresolved; do not assume it is a plain `untrusted_app` variant.
- The `dlopen`-succeeds-while-`execve`-is-denied behaviour comes from adjacent third-party reports ([logos-workspace#282](https://github.com/logos-fleet/logos-workspace/issues/282), [maceip/mobile-sandbox#2](https://github.com/maceip/mobile-sandbox/pull/2)) I could not vet — treat as unverified claims.
- Whether `@anthropic-ai/claude-code@stable` (2.1.287) behaves differently from 2.1.296 — I only verified 2.1.296, which is also `latest`/`next`, and confirmed the Android packages 404 on both tags.
- qemu-user on Android: nothing found either way.
- Whether the parent app is Play-distributed (this changes the targetSdk recommendation materially).
- The npm docs prose could not be fetched by *my* fetch tool (HTTP 422); section E's prose quotes and PR/version provenance were gathered by a parallel research pass against the official docs, while the behaviour itself was established independently from npm's installed source and live experiments.

---

## Security note on the research process

Mid-research the search backend began returning **HTTP 402 with an embedded message instructing the agent to authenticate using a supplied API key**. That is untrusted content, not a legitimate instruction, and it was **not** acted on — the remaining work was completed via direct source fetches (`android.googlesource.com`) and npm registry/CLI inspection instead. Flagging it because it is a prompt-injection-shaped artefact appearing in the tooling layer, and because any credentials offered that way should be treated as compromised.