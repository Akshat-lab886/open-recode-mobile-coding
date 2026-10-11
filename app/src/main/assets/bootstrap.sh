#!/system/bin/sh
# bootstrap.sh — first-boot environment setup for DSH Mobile.
#
# Runs on first app init (via BootstrapManager). Idempotent: safe to re-run.
# Emits machine-readable progress markers on stdout for the Compose progress
# loop to parse (both stdout AND stderr are captured):
#
#   >>>STEP:<human-readable step name>
#   >>>PROGRESS:<0-100>
#   >>>LOG:<detail line>
#   >>>ERROR:<what failed + next action>
#   >>>DONE
#
# Env (all optional, sane defaults for the app's filesDir layout):
#   RUNTIME_ROOT  - PRoot distro root            (default: $PWD/dsh-runtime)
#   ROOTFS_TAR    - Alpine/Ubuntu rootfs tarball (default: $RUNTIME_ROOT/rootfs.tar.gz)
#   NODE_DIR      - guest rootfs prefix holding node (default: $RUNTIME_ROOT)
#                   Node ships as Alpine's `nodejs` apk INSIDE rootfs.tar.gz,
#                   so it lives at $NODE_DIR/usr/bin/node, not $NODE_DIR/bin/node.
#   AGENTS_DIR    - npm global prefix for agents (default: $RUNTIME_ROOT/agents)
#   MARKER_FILE   - written on success, skips work (default: $RUNTIME_ROOT/.bootstrap_done)
#   DSH_VERSION   - npm tag for DSH              (default: latest)
#
# Exit codes: 0 = ready (or already ready), 1 = architecture unsupported,
#             2 = rootfs missing/corrupt, 3 = package install failed,
#             4 = npm/agent install failed.

set -eu

RUNTIME_ROOT="${RUNTIME_ROOT:-$PWD/dsh-runtime}"
ROOTFS_TAR="${ROOTFS_TAR:-$RUNTIME_ROOT/rootfs.tar.gz}"

# ProcessBuilder does NOT give us a login shell environment: HOME, TMPDIR and
# PATH may all be unset. This script runs under `set -u`, so the first reference
# to an unset HOME aborts the entire bootstrap with
#   "bootstrap.sh[298]: HOME: parameter not set"
# `run-as` supplies HOME, which is why manual testing never reproduced it.
: "${HOME:=$RUNTIME_ROOT}"
: "${TMPDIR:=/tmp}"
: "${PATH:=/system/bin:/system/xbin}"
export HOME TMPDIR PATH
NODE_DIR="${NODE_DIR:-$RUNTIME_ROOT}"
AGENTS_DIR="${AGENTS_DIR:-$RUNTIME_ROOT/agents}"
MARKER_FILE="${MARKER_FILE:-$RUNTIME_ROOT/.bootstrap_done}"
DSH_VERSION="${DSH_VERSION:-latest}"

step() { echo ">>>STEP:$1"; }
progress() { echo ">>>PROGRESS:$1"; }
log() { echo ">>>LOG:$1"; }
fail() { echo ">>>ERROR:$1"; exit "${2:-3}"; }

# ---------------------------------------------------------------------------
# 0. Short-circuit: already bootstrapped and agents present.
# ---------------------------------------------------------------------------
if [ -f "$MARKER_FILE" ] && [ -x "$NODE_DIR/usr/bin/node" ]; then
  if [ -x "$AGENTS_DIR/dsh/bin/dsh.js" ] || [ -x "$AGENTS_DIR/bin/dsh" ]; then
    step "Environment ready"
    progress 100
    log "marker present, skipping bootstrap"
    echo ">>>DONE"
    exit 0
  fi
  log "marker present but agent entrypoint missing — reinstalling agents"
fi

mkdir -p "$RUNTIME_ROOT" "$AGENTS_DIR"

# ---------------------------------------------------------------------------
# 1. Verify arm64 architecture (non-technical users get a clean error card).
# ---------------------------------------------------------------------------
step "Checking device compatibility"
ARCH="$(uname -m 2>/dev/null || echo unknown)"
log "detected arch: $ARCH"
case "$ARCH" in
  aarch64|arm64|armv8l)
    log "arm64 confirmed"
    ;;
  *)
    fail "This device ($ARCH) is not supported — DSH Mobile needs an arm64 (64-bit) Android device." 1
    ;;
esac
progress 8

# ---------------------------------------------------------------------------
# 2. Unpack Alpine/Ubuntu rootfs (skipped if already unpacked).
# ---------------------------------------------------------------------------
step "Preparing Linux environment"
if [ -f "$RUNTIME_ROOT/etc/os-release" ] || [ -f "$RUNTIME_ROOT/bin/sh" ]; then
  log "rootfs already unpacked, skipping extraction"
else
  if [ ! -f "$ROOTFS_TAR" ]; then
    fail "Linux system image not found ($ROOTFS_TAR). Reinstall the app to restore bundled assets." 2
  fi
  log "extracting rootfs (one-time, ~30s)…"
  # NOTE 1: an Android app CANNOT write /tmp - a `2>/tmp/...` redirect fails
  # with EACCES and that failure is what the `if` sees, so extraction would be
  # reported broken even with a perfectly good tarball.
  EXTRACT_ERR="$RUNTIME_ROOT/../dsh-logs/rootfs-extract.err"
  mkdir -p "$(dirname "$EXTRACT_ERR")" 2>/dev/null || true

  # NOTE 2: tar can exit non-zero on symlink warnings that are harmless here.
  # On Android, /bin is itself a symlink to /system/bin, so toybox tar
  # canonicalises a guest link like "./sbin/logread -> /bin/busybox" into
  # /system/bin/busybox, decides it escapes the destination, and refuses it.
  # The real success criterion is a RUNNABLE runtime, not a clean exit code, so
  # we tolerate warnings and validate the node binary below.
  tar -xzf "$ROOTFS_TAR" -C "$RUNTIME_ROOT" 2>"$EXTRACT_ERR" || true
  if [ -s "$EXTRACT_ERR" ]; then
    WARN_COUNT="$(grep -c 'tar:' "$EXTRACT_ERR" 2>/dev/null || echo 0)"
    log "tar reported $WARN_COUNT message(s); validating extracted runtime instead:"
    tail -3 "$EXTRACT_ERR" 2>/dev/null | while IFS= read -r l; do echo ">>>LOG:$l"; done
  fi
  if [ ! -x "$RUNTIME_ROOT/usr/bin/node" ]; then
    fail "Linux image unpacked but no usable Node runtime. Free at least 1 GB of storage and tap Retry." 2
  fi
  log "rootfs extracted"
fi
progress 25

# ---------------------------------------------------------------------------
# 3. Detect distro + update package index.
# ---------------------------------------------------------------------------
step "Updating system packages"
PKG="unknown"
if [ -f "$RUNTIME_ROOT/etc/alpine-release" ]; then PKG="apk"; fi
if [ -f "$RUNTIME_ROOT/etc/debian_version" ] || [ -f "$RUNTIME_ROOT/etc/lsb-release" ]; then PKG="apt"; fi
# Fallback: probe for the package manager binary inside the rootfs.
if [ "$PKG" = "unknown" ]; then
  if [ -x "$RUNTIME_ROOT/sbin/apk" ]; then PKG="apk";
  elif [ -x "$RUNTIME_ROOT/usr/bin/apt-get" ]; then PKG="apt"; fi
fi
log "package manager: $PKG"

# proot wrapper when available, else chroot-less direct run is skipped and we
# fall back to host-side equivalents (rootfs binaries run under proot only).
PROOT_BIN="$RUNTIME_ROOT/proot"
# REQUIRED: proot defaults its scratch dir to /tmp, which an Android app
# cannot write. Without this every proot invocation dies with
# "can't create temporary file: Permission denied" (observed on-device).
PROOT_TMP_DIR="$RUNTIME_ROOT/tmp"
mkdir -p "$PROOT_TMP_DIR" 2>/dev/null || true
export PROOT_TMP_DIR

# Zygote-spawned apps inherit Android's app seccomp filter; a process started by
# `run-as` does NOT. That asymmetry is why proot appeared to work under run-as
# and still failed (exit 255, no stderr) inside the real app process. proot
# installs its own seccomp filter as a fast path, and that conflicts with the
# inherited one, so we disable it. Purely a performance trade-off.
export PROOT_NO_SECCOMP=1

# ---------------------------------------------------------------------------
# Locate a directory where the proot binary can actually be EXECUTED.
#
# Apps targeting API 29+ cannot execve() any binary in their own writable
# storage - Android removed the implicit execute_no_trans permission. At
# targetSdk 34 every candidate below was DENIED on-device:
#   filesDir, getExternalFilesDir() and cacheDir alike:
#   ".../files/dsh-runtime/proot: Permission denied"
# while the identical binary ran fine under `run-as` (a different SELinux
# domain), which is exactly why this is easy to misdiagnose. The app now
# targets SDK 28 (as Termux does), but we still do not GUESS - we copy the
# binary to each candidate location and actually try to run it.
# ---------------------------------------------------------------------------
proot_exec_dir=""
probe_exec() {
  src="$1"; d="$2"
  [ -f "$src" ] || return 1
  mkdir -p "$d" 2>/dev/null || return 1
  cp -f "$src" "$d/.dsh-probe" 2>/dev/null || return 1
  chmod 755 "$d/.dsh-probe" 2>/dev/null || { rm -f "$d/.dsh-probe" 2>/dev/null; return 1; }
  if "$d/.dsh-probe" --version >/dev/null 2>&1; then
    rm -f "$d/.dsh-probe" 2>/dev/null
    return 0
  fi
  log "exec DENIED in $d"
  rm -f "$d/.dsh-probe" 2>/dev/null
  return 1
}
for CAND in "$RUNTIME_ROOT" "$EXT_FILES_DIR" "$CACHE_DIR"; do
  if probe_exec "$PROOT_BIN" "$CAND"; then
    proot_exec_dir="$CAND"
    log "exec OK in $CAND"
    break
  fi
done
if [ -z "$proot_exec_dir" ]; then
  fail "Android blocked launching the Linux engine from every writable location on this device. This is a platform policy, not a missing file." 3
fi
PROOT_BIN="$proot_exec_dir/.dsh-proot"
cp -f "$RUNTIME_ROOT/proot" "$PROOT_BIN" 2>/dev/null || true
chmod 755 "$PROOT_BIN" 2>/dev/null || true
run_in_rootfs() {
  if [ -x "$PROOT_BIN" ]; then
    PROOT_TMP_DIR="$RUNTIME_ROOT/tmp"; mkdir -p "$PROOT_TMP_DIR" 2>/dev/null || true
    export PROOT_TMP_DIR
    export PROOT_NO_SECCOMP=1
    "$PROOT_BIN" -r "$RUNTIME_ROOT" -b /proc -b /dev -b /sys -w / "$@"
  else
    log "proot not ready yet — running host-side fallback for: $*"
    return 99
  fi
}

# The shipped rootfs is BUILT COMPLETE: node, npm, git, rg, bash, python3 and
# curl are already inside it. Running `apk update` + `apk add` on first boot
# re-downloads ~283 MiB that we already shipped (minutes on mobile data, and
# it failed the boot here: apk triggers are killed by SIGSYS under proot's
# ptrace). So we only touch the package manager when something is actually
# missing.
toolchain_complete() {
  # Alpine does NOT merge /usr into /bin (no usrmerge), so `bash` lives at
  # /bin/bash while `node` and `git` live in /usr/bin. Checking only one
  # directory reported the toolchain as incomplete and sent us back to apk.
  for b in usr/bin/node usr/bin/npm usr/bin/git bin/bash; do
    [ -x "$RUNTIME_ROOT/$b" ] || return 1
  done
  return 0
}

set +e
if toolchain_complete; then
  log "rootfs already ships a complete toolchain - skipping package manager entirely"
  RC=0
  SKIP_PKG=1
elif [ "$PKG" = "apk" ]; then
  run_in_rootfs /sbin/apk update 2>&1 | while IFS= read -r l; do echo ">>>LOG:[apk] $l"; done
  RC=${PIPESTATUS:-0}
elif [ "$PKG" = "apt" ]; then
  run_in_rootfs /usr/bin/apt-get update -y 2>&1 | while IFS= read -r l; do echo ">>>LOG:[apt] $l"; done
  RC=${PIPESTATUS:-0}
else
  log "no guest package manager detected — continuing with host-provided toolchain"
  RC=0
fi
set -e
if [ "${RC:-0}" = "99" ]; then
  log "package index update deferred until proot extraction completes"
elif [ "${RC:-0}" -ne 0 ]; then
  log "package index update returned $RC — continuing (offline mirror tolerated)"
fi
progress 45

# ---------------------------------------------------------------------------
# 4. Install native build dependencies.
# ---------------------------------------------------------------------------
step "Installing build tools"
# The shipped rootfs is BUILT COMPLETE: node, npm, git, rg, bash, python3 and
# curl are already inside it. Running `apk update` + `apk add` on first boot
# re-downloads ~283 MiB that we already shipped (minutes on mobile data, and
# it failed the boot here: apk triggers are killed by SIGSYS under proot's
# ptrace). So we only touch the package manager when something is actually
# missing.
toolchain_complete() {
  # Alpine does NOT merge /usr into /bin (no usrmerge), so `bash` lives at
  # /bin/bash while `node` and `git` live in /usr/bin. Checking only one
  # directory reported the toolchain as incomplete and sent us back to apk.
  for b in usr/bin/node usr/bin/npm usr/bin/git bin/bash; do
    [ -x "$RUNTIME_ROOT/$b" ] || return 1
  done
  return 0
}

set +e
if toolchain_complete; then
  log "rootfs already ships a complete toolchain - skipping package manager entirely"
  RC=0
  SKIP_PKG=1
elif [ "$PKG" = "apk" ]; then
  run_in_rootfs /sbin/apk add --no-cache \
    build-base python3 make g++ git curl ca-certificates \
    2>&1 | while IFS= read -r l; do echo ">>>LOG:[apk] $l"; done
  RC=${PIPESTATUS:-0}
elif [ "$PKG" = "apt" ]; then
  run_in_rootfs /usr/bin/apt-get install -y \
    build-essential python3 make g++ git curl ca-certificates \
    2>&1 | while IFS= read -r l; do echo ">>>LOG:[apt] $l"; done
  RC=${PIPESTATUS:-0}
else
  log "no guest package manager — expecting host-side node toolchain"
  RC=0
fi
set -e
if [ "${RC:-0}" = "99" ]; then
  log "build-tool install deferred (no proot) — host toolchain will be used"
elif [ "${RC:-0}" -ne 0 ]; then
  fail "Could not install build tools (code $RC). Check your connection and tap Retry." 3
fi
progress 62

# ---------------------------------------------------------------------------
# 5. Verify the Node runtime that ships inside the rootfs.
#    Node is Alpine's own `nodejs` apk (musl, aarch64). A glibc build such as
#    node-v24-linux-arm64.tar.xz CANNOT run here - musl has no libc.so.6.
#    We deliberately do NOT fall back to a host node: a host (Android/bionic)
#    binary cannot execute inside the guest at all.
step "Checking Node.js runtime"
NODE_BIN="$NODE_DIR/usr/bin/node"
if [ ! -x "$NODE_BIN" ]; then
  fail "Node.js not found at $NODE_BIN. The rootfs did not unpack correctly - reinstall the app to restore bundled assets." 3
fi
# Guest binaries MUST be launched through proot. Executing them directly from
# the host shell cannot work at all: the guest is Alpine/musl while the host is
# Android/bionic, so the dynamic loader is missing and you get
# "No such file or directory" for a file that is plainly there.
NODE_PROBE_ERR="$RUNTIME_ROOT/../dsh-logs/node-probe.err"
# Guest binaries MUST be launched through proot. Executing them directly from
# the host shell cannot work: the guest is Alpine/musl while the host is
# Android/bionic, so the dynamic loader is missing and you get
# "No such file or directory" for a file that is plainly there.
# A working directory that exists INSIDE the guest is required: PRoot
# chdir()s to the process CWD in the guest and hard-exits (255) when it is
# absent ("can't chdir(.../files/./.) in the guest rootfs"). The manager
# launches this script with RUNTIME_ROOT as its cwd for that reason.
NODE_PROBE="$(run_in_rootfs /bin/echo ready 2>&1)" || NODE_PROBE="FAILED"
if [ "$NODE_PROBE" != "ready" ]; then
  fail "Android blocked the Linux engine from starting inside the app. This is a device security policy (the app seccomp filter), not a missing download. Try reinstalling, or use a device without a restricted app profile." 3
fi
log "linux engine reachable from inside the app"
NODE_VER="$(run_in_rootfs /usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin:/sbin:/root/bin HOME=/root /usr/bin/node --version 2>/dev/null || echo unknown)"
log "rootfs node: $NODE_VER"
case "$NODE_VER" in
  v2[0-9]*|unknown) : ;;
  *) fail "Node runtime in the Linux image is unusable (got '$NODE_VER'). Reinstall the app." 3 ;;
esac
NPM_BIN="$NODE_DIR/usr/bin/npm"
if [ ! -x "$NPM_BIN" ]; then
  # npm is a separate Alpine package (community repo) and may be a symlink.
  NPM_BIN="$(cd "$NODE_DIR" 2>/dev/null && command -v npm || true)"
  [ -n "$NPM_BIN" ] || fail "npm missing from the rootfs. Reinstall the app to restore bundled assets." 3
fi
progress 70

# ---------------------------------------------------------------------------
# 6. Install the real agent CLIs globally into AGENTS_DIR.
# ---------------------------------------------------------------------------
step "Installing DeepSeek Harness"
NPM_BIN="$NODE_DIR/usr/bin/npm"
if [ ! -x "$NPM_BIN" ]; then NPM_BIN="$(command -v npm || true)"; fi
if [ -z "$NPM_BIN" ]; then
  fail "npm not found inside the rootfs ($NODE_DIR/usr/bin). Reinstall the app to restore bundled assets." 4
fi
log "npm prefix: $AGENTS_DIR"

# See step 5: npm is a guest binary and must go through proot too. `env` carries
# the guest environment, which is what npm and any native postinstall needs.
guest_env() {
  printf '%s' "PATH=/usr/local/bin:/usr/bin:/bin:/sbin:/root/bin HOME=/root TMPDIR=/tmp NPM_CONFIG_PREFIX=$AGENTS_DIR"
}

set +e
run_in_rootfs /usr/bin/env $(guest_env) /usr/bin/npm install -g \
  "@deepseek-ai/dsh@$DSH_VERSION" \
  2>&1 | while IFS= read -r l; do echo ">>>LOG:[npm] $l"; done
RC=${PIPESTATUS:-0}
set -e
if [ "$RC" -ne 0 ]; then
  fail "Could not download DeepSeek Harness (npm exit $RC). Check your connection and tap Retry." 4
fi
progress 88

# ---------------------------------------------------------------------------
# 7. Standard plugin modules (best-effort: never fail the whole bootstrap).
# ---------------------------------------------------------------------------
step "Installing agent plugins"
set +e
run_in_rootfs /usr/bin/env $(guest_env) /usr/bin/npm install -g opencode-ai \
  2>&1 | while IFS= read -r l; do echo ">>>LOG:[npm] $l"; done
log "opencode install exit: ${PIPESTATUS:-?}"
# Claude Code + Codex CLIs are installed on demand when the user first picks
# that backend (keeps first-boot fast); record the intent for the gateway.
touch "$AGENTS_DIR/.plugins-probed" 2>/dev/null || true
set -e
progress 96

# ---------------------------------------------------------------------------
# 8. Verify + stamp success.
# ---------------------------------------------------------------------------
step "Verifying installation"
# VERIFIED npm layout for `--prefix <agents>`:
#   <agents>/bin/dsh                                           (shim)
#   <agents>/lib/node_modules/@deepseek-ai/dsh/lib/bin.js       (real entry)
if [ -x "$AGENTS_DIR/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" ] || \
   [ -x "$AGENTS_DIR/bin/dsh" ] || \
   [ -x "$AGENTS_DIR/lib/node_modules/.bin/dsh" ]; then
  log "dsh entrypoint verified"
else
  # npm may have placed the bin under lib/node_modules/.bin — accept that too.
  if [ -x "$AGENTS_DIR/lib/node_modules/.bin/dsh" ]; then
    log "dsh verified via .bin symlink"
  else
    fail "Install finished but the dsh binary is missing. Tap Retry to reinstall." 4
  fi
fi

date -u +"%Y-%m-%dT%H:%M:%SZ" > "$MARKER_FILE"
step "Setup complete"
progress 100
echo ">>>DONE"
