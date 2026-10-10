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
#   NODE_DIR      - embedded Node install dir    (default: $RUNTIME_ROOT/node)
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
NODE_DIR="${NODE_DIR:-$RUNTIME_ROOT/node}"
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
if [ -f "$MARKER_FILE" ] && [ -x "$NODE_DIR/bin/node" ]; then
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
  if ! tar -xzf "$ROOTFS_TAR" -C "$RUNTIME_ROOT" 2>/tmp/dsh-rootfs-err.log; then
    cat /tmp/dsh-rootfs-err.log 2>/dev/null || true
    fail "Could not unpack the Linux image. Free at least 1 GB of storage and tap Retry." 2
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
run_in_rootfs() {
  if [ -x "$PROOT_BIN" ]; then
    "$PROOT_BIN" -r "$RUNTIME_ROOT" -0 -w / "$@"
  else
    log "proot not ready yet — running host-side fallback for: $*"
    return 99
  fi
}

set +e
if [ "$PKG" = "apk" ]; then
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
set +e
if [ "$PKG" = "apk" ]; then
  run_in_rootfs /sbin/apk add --no-cache \
    build-base python3 make g++ git curl ca-certificates nodejs npm \
    2>&1 | while IFS= read -r l; do echo ">>>LOG:[apk] $l"; done
  RC=${PIPESTATUS:-0}
elif [ "$PKG" = "apt" ]; then
  run_in_rootfs /usr/bin/apt-get install -y \
    build-essential python3 make g++ git curl ca-certificates nodejs npm \
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
# 5. Ensure Node 20+ is available.
# ---------------------------------------------------------------------------
step "Checking Node.js runtime"
NODE_BIN="$NODE_DIR/bin/node"
if [ -x "$NODE_BIN" ]; then
  NODE_VER="$("$NODE_BIN" --version 2>/dev/null || echo unknown)"
  log "embedded node: $NODE_VER"
else
  # Fall back to any node on PATH (guest or host), then link it in.
  CANDIDATE="$(command -v node || true)"
  if [ -n "$CANDIDATE" ]; then
    log "using system node at $CANDIDATE ($("$CANDIDATE" --version 2>/dev/null || echo ?))"
    mkdir -p "$NODE_DIR/bin"
    ln -sf "$CANDIDATE" "$NODE_BIN" 2>/dev/null || true
    NPM_CANDIDATE="$(command -v npm || true)"
    if [ -n "$NPM_CANDIDATE" ]; then
      ln -sf "$NPM_CANDIDATE" "$NODE_DIR/bin/npm" 2>/dev/null || true
    fi
  else
    fail "Node.js runtime not found. Reinstall the app to restore the bundled Node 20 binary." 3
  fi
fi
progress 70

# ---------------------------------------------------------------------------
# 6. Install the real agent CLIs globally into AGENTS_DIR.
# ---------------------------------------------------------------------------
step "Installing DeepSeek Harness"
NPM_BIN="$NODE_DIR/bin/npm"
if [ ! -x "$NPM_BIN" ]; then NPM_BIN="$(command -v npm || true)"; fi
if [ -z "$NPM_BIN" ]; then
  fail "npm not found next to node ($NODE_DIR). Reinstall the app to restore the toolchain." 4
fi
export NPM_CONFIG_PREFIX="$AGENTS_DIR"
export PATH="$NODE_DIR/bin:$PATH"
log "npm prefix: $AGENTS_DIR"

set +e
"$NPM_BIN" install -g "@deepseek-ai/dsh@$DSH_VERSION" \
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
"$NPM_BIN" install -g opencode-ai 2>&1 | while IFS= read -r l; do echo ">>>LOG:[npm] $l"; done
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
if [ -x "$AGENTS_DIR/bin/dsh" ] || [ -x "$AGENTS_DIR/dsh/bin/dsh.js" ] || \
   [ -x "$AGENTS_DIR/lib/node_modules/@deepseek-ai/dsh/bin/dsh.js" ]; then
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
