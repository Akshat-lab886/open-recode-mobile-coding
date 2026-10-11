#!/usr/bin/env python3
"""
Build a bionic-linked Node.js payload from Termux packages.

WHY THIS EXISTS
---------------
The musl-in-PRoot route is blocked by Android's app seccomp filter. Measured
on a Samsung SM-X115 (Android 16) by running probes inside the app process:

  - musl's faccessat() tries faccessat2(2) first and only falls back to plain
    faccessat(2) on -ENOSYS. Android traps faccessat2 with SECCOMP_RET_TRAP,
    which KILLS the process, so musl never reaches its fallback. This is the
    confirmed killer: a libc probe that exercises one call at a time survives
    openat, access, stat, getpwuid, fopen, setlocale, clock_gettime, socket,
    getaddrinfo, pthread_create/join and fork/wait4, and dies on exactly
    faccessat(AT_EACCESS).
  - Everything else musl would need is fine: pthread_create/join succeeding
    proves plain futex(98) works; socket() and getaddrinfo() succeed.

Termux solves this the obvious way: its packages link against Android's OWN
libc (bionic), which has no faccessat2 probe and is built for this sandbox. A
bionic Node therefore has none of the problems above.

So we take Termux's already-working aarch64 Node plus the shared libraries it
needs, and ship them in our own APK. This is self-contained: the user installs
nothing.

HOW IT WORKS
-----------
A .deb is an ar archive wrapping a data.tar.* member; the DT_NEEDED list is
read straight out of the ELF so we never guess which libraries are required.
Each library is written out under the SONAME the linker actually looks for
(Termux ships versioned names like libz.so.1.3.2; the loader wants libz.so.1).

Usage:
    python3 tools/build-termux-node-payload.py --out app/src/main/jniLibs/arm64-v8a
"""

from __future__ import annotations

import argparse
import io
import json
import lzma
import os
import re
import struct
import sys
import tarfile
import urllib.request

TERMUX = "https://packages.termux.dev/apt/termux-main/"
INDEX = TERMUX + "dists/stable/main/binary-aarch64/Packages.gz"

# Packages that provide the shared libraries Node links against. Keys are the
# Termux package names; values are filled in from the repo index at runtime.
NEEDED_PACKAGES = [
    "nodejs",     # the interpreter itself
    "libc++",     # libc++_shared.so
    "c-ares",     # libcares.so
    "libffi",     # libffi.so
    "openssl",    # libcrypto.so.3, libssl.so.3
    "libsqlite",  # libsqlite3.so
    "libicu",     # libicuuc.so.78, libicui18n.so.78, libicudata
    "zlib",       # libz.so.1
]


def log(msg: str) -> None:
    print(msg, flush=True)


def read_deb_data_member(raw: bytes) -> bytes:
    """Return the uncompressed data.tar.* payload from a .deb."""
    if raw[:8] != b"!<arch>\n":
        raise ValueError("not an ar archive (not a .deb)")
    pos = 8
    while pos < len(raw):
        header = raw[pos : pos + 60]
        pos += 60
        name = header[0:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        body = raw[pos : pos + size]
        pos += size + (size % 2)
        if name.startswith("data.tar"):
            if name.endswith(".xz"):
                return lzma.decompress(body)
            if name.endswith(".gz"):
                import gzip

                return gzip.decompress(body)
            return body
    raise ValueError("no data.tar member in .deb")


def elf_needed(path_or_bytes) -> list[str]:
    """Parse DT_NEEDED from an aarch64 ELF. Avoids hardcoding the lib list."""
    d = path_or_bytes(path_or_bytes) if callable(path_or_bytes) else path_or_bytes
    if d[:4] != b"\x7fELF":
        return []
    e_phoff = struct.unpack_from("<Q", d, 32)[0]
    e_phentsz = struct.unpack_from("<H", d, 54)[0]
    e_phnum = struct.unpack_from("<H", d, 56)[0]
    segs = []
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsz
        p_type = struct.unpack_from("<I", d, o)[0]
        p_offset = struct.unpack_from("<Q", d, o + 8)[0]
        p_vaddr = struct.unpack_from("<Q", d, o + 16)[0]
        p_filesz = struct.unpack_from("<Q", d, o + 32)[0]
        segs.append((p_type, p_offset, p_vaddr, p_filesz))

    def vaddr_to_off(va: int):
        for p_type, p_offset, p_vaddr, p_filesz in segs:
            if p_type == 1 and p_vaddr <= va < p_vaddr + p_filesz:
                return p_offset + (va - p_vaddr)
        return None

    dyn = next((off, sz) for t, off, _va, sz in segs if t == 2)
    strtab_va = None
    needed_offsets = []
    o = 0
    while o < dyn[1]:
        tag, val = struct.unpack_from("<qQ", d, dyn[0] + o)
        o += 16
        if tag == 0:
            break
        if tag == 1:
            needed_offsets.append(val)
        if tag == 5:
            strtab_va = val
    if strtab_va is None:
        return []
    st = vaddr_to_off(strtab_va)
    out = []
    for n in needed_offsets:
        end = d.index(b"\0", st + n)
        out.append(d[st + n : end].decode())
    return out


def fetch_index() -> dict[str, str]:
    import gzip

    raw = gzip.decompress(urllib.request.urlopen(INDEX, timeout=120).read()).decode(
        "utf-8", "replace"
    )
    paths: dict[str, str] = {}
    for block in raw.split("\n\n"):
        m = re.search(r"^Package: (.+)$", block, re.M)
        if not m:
            continue
        pkg = m.group(1).strip()
        f = re.search(r"^Filename: (.+)$", block, re.M)
        v = re.search(r"^Version: (.+)$", block, re.M)
        if f:
            paths[pkg] = f.group(1).strip()
            if v:
                paths[pkg + "::version"] = v.group(1).strip()
    return paths


def soname_variants(base: str) -> list[str]:
    """Names the dynamic loader may look for, given Termux's real file name.

    Termux ships e.g. libz.so.1.3.2 (plus symlinks we cannot reproduce from a
    tarball without following links). We materialise the SONAME the loader
    asks for by copying the real file under that name.
    """
    out = [base]
    if base.startswith("libz.so."):
        out.append("libz.so.1")
    if base.startswith("libsqlite3"):
        out += ["libsqlite3.so", "libsqlite3.so.0"]
    for stem in ("libicuuc", "libicui18n", "libicudata", "libicuio", "libicutu"):
        m = re.match(rf"{stem}\.so\.(\d+)\.\d+", base)
        if m:
            out.append(f"{stem}.so.{m.group(1)}")
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="output dir (jniLibs/arm64-v8a)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    index = fetch_index()
    missing = [p for p in NEEDED_PACKAGES if p not in index]
    if missing:
        log(f"ERROR: packages absent from the Termux index: {missing}")
        return 1

    os.makedirs(args.out, exist_ok=True)
    staged: dict[str, bytes] = {}

    for pkg in NEEDED_PACKAGES:
        url = TERMUX + index[pkg]
        version = index.get(pkg + "::version", "?")
        log(f"-> {pkg} {version}")
        raw = urllib.request.urlopen(url, timeout=180).read()
        tf = tarfile.open(fileobj=io.BytesIO(read_deb_data_member(raw)))
        for m in tf.getmembers():
            # Symlinks are skipped on purpose: we recreate the SONAME names
            # ourselves so the layout does not depend on link targets.
            if not m.isfile():
                continue
            base = m.name.rsplit("/", 1)[-1]
            if pkg == "nodejs" and base == "node":
                data = tf.extractfile(m).read()
                (open(os.path.join(args.out, "node"), "wb") if not args.dry_run else None)
                if not args.dry_run:
                    with open(os.path.join(args.out, "node"), "wb") as fh:
                        fh.write(data)
                    os.chmod(os.path.join(args.out, "node"), 0o755)
                log(f"   node: {len(data)} bytes")
            elif ".so" in base:
                data = tf.extractfile(m).read()
                staged[base] = data
        if pkg == "nodejs":
            continue

    # Resolve Node's actual DT_NEEDED and make sure every name is present.
    node_path = os.path.join(args.out, "node")
    if os.path.exists(node_path):
        with open(node_path, "rb") as fh:
            needed = elf_needed(fh.read())
        log(f"node DT_NEEDED: {needed}")
        # Bionic libs live on the device already; the rest must be staged.
        bionic = {"libc.so", "libdl.so", "libm.so"}
        for name in needed:
            if name in bionic or name in staged:
                continue
            # try the versioned real file we actually downloaded
            hit = next((s for s in staged if s.startswith(name.rstrip(".0").split(".so")[0] + ".so")), None)
            log(f"   {'ok ' if hit else 'MISSING'} {name}{'' if hit else '  <-- not staged'}")
    else:
        log("(dry run: node not written)")

    for base, data in staged.items():
        for variant in soname_variants(base):
            if not args.dry_run:
                with open(os.path.join(args.out, variant), "wb") as fh:
                    fh.write(data)
    if not args.dry_run:
        log(f"\nwrote {len(staged)} libraries to {args.out}")
    else:
        log(f"\n(dry run) would stage {sorted(staged)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())