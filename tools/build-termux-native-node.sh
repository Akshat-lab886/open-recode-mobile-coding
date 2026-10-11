#!/usr/bin/env bash
# FALLBACK PATH (verified working, but NOT the recommendation -- see
# PAYLOAD-RESEARCH.md). Assemble a *natively executable* Node.js for ARM64
# Android from Termux's .deb files, with no proot and no rootfs.
#
# Verified on a real ARM64 Android 16 device: after this script's steps,
#   LD_LIBRARY_PATH=<bundle>/lib OPENSSL_CONF=<bundle>/etc/openssl.cnf \
#     <bundle>/bin/node -e 'console.log(process.version)'
# printed v26.4.0, and `execSync()` worked after the prefix patch.
#
# Requires: python3 (for the .deb extraction and the ELF string patch).
# Runs fine on macOS -- nothing here needs Linux.
#
#   ./build-termux-native-node.sh /tmp/native-node
#
set -euo pipefail

OUT="${1:-/tmp/native-node}"
REPO="https://packages.termux.dev/apt/termux-main"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Versions taken from the aarch64 Packages index fetched with:
#   curl -sSL https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz | gunzip
PKGS=(
  "pool/main/n/nodejs/nodejs_26.4.0-1_aarch64.deb"
  "pool/main/z/zlib/zlib_1.3.2_aarch64.deb"
  "pool/main/c/c-ares/c-ares_1.34.8_aarch64.deb"
  "pool/main/libf/libffi/libffi_3.8.0_aarch64.deb"
  "pool/main/o/openssl/openssl_1%3A3.6.5_aarch64.deb"
  "pool/main/libi/libicu/libicu_78.3_aarch64.deb"
  "pool/main/libc/libc++/libc++_30_aarch64.deb"
  "pool/main/libs/libsqlite/libsqlite_3.54.0_aarch64.deb"
)

rm -rf "$OUT"; mkdir -p "$OUT/bin" "$OUT/lib" "$OUT/etc"

# --- 1. fetch + extract -------------------------------------------------
fetch() {
  local p="$1" f="$WORK/$(basename "$p")"
  [ -f "$f" ] || curl -fsSL -o "$f" "$REPO/$p"
  printf '%-42s %s\n' "$(basename "$p")" "$(shasum -a 256 "$f" | cut -d' ' -f1)"
  python3 - "$f" "$WORK/x-$(basename "$p")" <<'PY'
import sys, os
deb, out = sys.argv[1], sys.argv[2]
d = open(deb, 'rb').read(); off = 8; os.makedirs(out, exist_ok=True)
while off < len(d):
    h = d[off:off+60]
    if len(h) < 60: break
    name = h[0:16].decode().strip().rstrip('/')
    size = int(h[48:58].decode().strip()); off += 60
    body = d[off:off+size]; off += size + (size % 2)
    if name.startswith('data.tar'):
        open(os.path.join(out, name), 'wb').write(body)
PY
  ( cd "$WORK/x-$(basename "$p")" && tar xf data.tar.* )
}

echo "--- deb sha256 ---"
for p in "${PKGS[@]}"; do fetch "$p"; done

LIBDIR="data/data/com.termux/files/usr/lib"
BINDIR="data/data/com.termux/files/usr/bin"
cp "$WORK/x-$(basename "${PKGS[0]}")/$BINDIR/node" "$OUT/bin/node"
for d in "$WORK"/x-*; do cp -n "$d/$LIBDIR/"* "$OUT/lib/" 2>/dev/null || true; done
# sonames the loader looks for; the debs ship versioned names only
ln -sf libsqlite3.so.3.54.0 "$OUT/lib/libsqlite3.so"
ln -sf libicui18n.so.78.3      "$OUT/lib/libicui18n.so.78"
ln -sf libicuuc.so.78.3        "$OUT/lib/libicuuc.so.78"

# --- 2. neutralize Termux's compiled-in prefix --------------------------
# Termux builds node with the prefix baked in. The one that actually breaks
# child_process.exec()/execSync() is the shell libuv spawns; it is a plain
# NUL-terminated C string, so a byte-for-byte in-place substitution works.
# Verified: each replacement is padded with NULs to the ORIGINAL length, so no
# offset in the file moves.
python3 - "$OUT/bin/node" <<'PY'
import sys
p = sys.argv[1]
d = bytearray(open(p, 'rb').read())
def pad(new, n):
    assert len(new) <= n, (new, n)
    return new + b'\x00' * (n - len(new))
subs = [
    (b'/data/data/com.termux/files/usr/bin/bash', b'/system/bin/sh'),
    (b'/data/data/com.termux/files/usr/bin/sh',   b'/system/bin/sh'),
    (b'/data/data/com.termux/files/usr/tmp',      b'<TMP>'),
]
for old, new in subs:
    print('patch %-40s occurrences=%d' % (old.decode(), d.count(old)))
    d = d.replace(old, pad(new, len(old)))
open(p, 'wb').write(bytes(d))
PY

# --- 3. runtime config --------------------------------------------------
# Termux's OpenSSL looks for /data/data/com.termux/files/usr/etc/tls/openssl.cnf,
# which another app's uid cannot read -> "BIO_new_file: Permission denied".
# Supply our own and point OPENSSL_CONF at it.
mkdir -p "$OUT/etc"
cat > "$OUT/etc/openssl.cnf" <<'EOF'
openssl_conf = default_conf
[ default_conf ]
ssl_conf = ssl_sect
[ ssl_sect ]
system_default = system_default_sect
[ system_default_sect ]
Options = PrioritizeChaCha
CipherString = DEFAULT:@SECLEVEL=1
EOF

chmod 0755 "$OUT/bin/node"
chmod -R a+rX "$OUT"

cat <<EOF

Built: $OUT

Run on device:
  adb push "$OUT" /data/local/tmp/native-node
  adb shell "chmod -R 755 /data/local/tmp/native-node
             LD_LIBRARY_PATH=/data/local/tmp/native-node/lib
             OPENSSL_CONF=/data/local/tmp/native-node/etc/openssl.cnf
             /data/local/tmp/native-node/bin/node -e 'console.log(process.version)'"
EOF