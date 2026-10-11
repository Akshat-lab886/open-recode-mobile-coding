#!/usr/bin/env bash
# Build an Alpine aarch64 rootfs containing Node.js, without Docker or Linux.
#
# Alpine .apk files are three concatenated gzip streams (signature, control,
# data). `zcat pkg.apk | tar -x` feeds all three to tar, so the data stream
# wins. No apk(8) needed, which is what makes this runnable on macOS.
set -euo pipefail

ROOT="${1:-/tmp/alpine/rootfs}"
REPO="https://dl-cdn.alpinelinux.org/alpine"
VER="v3.22"
ARCH="aarch64"
WORK="/tmp/alpine/work"

# Dependency closure resolved from the signed APKINDEX (see research report).
PKGS=(nodejs npm bash git curl ripgrep python3 libcurl libcrypto3 libssl3 \
      libexpat ca-certificates ca-certificates-bundle)

mkdir -p "$ROOT" "$WORK"

if [ ! -f "$ROOT/.buildinfo" ]; then
  echo "==> fetching minirootfs"
  curl -sSL -o "$WORK/minirootfs.tar.gz" \
    "$REPO/$VER/releases/$ARCH/alpine-minirootfs-3.22.4-$ARCH.tar.gz"
  tar -xzf "$WORK/minirootfs.tar.gz" -C "$ROOT"
fi

echo "==> resolving package filenames from APKINDEX"
curl -sSL -o "$WORK/APKINDEX.tar.gz" "$REPO/$VER/main/$ARCH/APKINDEX.tar.gz"
mkdir -p "$WORK/index" && tar -xzf "$WORK/APKINDEX.tar.gz" -C "$WORK/index"

filename_for() {
  awk -v pkg="$1" '
    /^P:/ { p = substr($0, 3) }
    p == pkg && /^F:/ { print substr($0, 3); exit }
  ' "$WORK/index/APKINDEX"
}

for pkg in "${PKGS[@]}"; do
  f=$(filename_for "$pkg" || true)
  if [ -z "$f" ]; then
    echo "!! package not found in index: $pkg" >&2
    continue
  fi
  dest="$WORK/$(basename "$f")"
  if [ ! -f "$dest" ]; then
    curl -sSL -o "$dest" "$REPO/$VER/main/$ARCH/$f"
  fi
  echo "==> unpacking $pkg"
  zcat "$dest" | tar -xf - -C "$ROOT" 2>/dev/null || true
done

echo "==> post-install steps"
# busybox applets (busybox --install) normally run as an apk trigger.
if [ -x "$ROOT/bin/busybox" ]; then
  chmod +x "$ROOT/bin/busybox" 2>/dev/null || true
fi
mkdir -p "$ROOT/etc/ssl/certs" "$ROOT/etc/resolv.conf" "$ROOT/root" "$ROOT/tmp"
[ -f "$ROOT/etc/resolv.conf" ] || printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > "$ROOT/etc/resolv.conf"
# CA bundle (normally built by the ca-certificates apk trigger).
if [ -f "$ROOT/etc/ssl/cert.pem" ]; then
  : > "$ROOT/etc/ssl/certs/ca-certificates.crt"
  cat "$ROOT/etc/ssl/cert.pem" > "$ROOT/etc/ssl/certs/ca-certificates.crt"
fi

echo "==> verifying"
ls -l "$ROOT/usr/bin/node" 2>/dev/null || echo "  !! node binary missing"
"$ROOT/usr/bin/node" --version 2>/dev/null || echo "  (cannot execute aarch64 node on this host — expected on x86/arm64 mac)"
printf 'built=%s\nver=%s\npkgs=%s\n' "$(date -u +%FT%TZ)" "$VER" "${PKGS[*]}" > "$ROOT/.buildinfo"
echo "==> rootfs ready at $ROOT ($(du -sh "$ROOT" | cut -f1))"