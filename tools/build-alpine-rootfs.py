#!/usr/bin/env python3
"""
Build the DSH mobile runtime payload on macOS: an Alpine aarch64 rootfs that
already contains Node.js, npm and the toolchain an AI coding agent needs, plus
a macOS-safe rootfs.tar.gz and the PRoot binary.

Why not `apk add --root`: apk(8) does not run on macOS. An Alpine .apk is a
sequence of concatenated gzip members (signature, control, data), so we split
them and extract only the LAST one -- the data segment -- which is what apk
itself installs. That also stops apk's own metadata (.PKGINFO, .post-install,
.SIGN.RSA.*) from leaking into the rootfs root.

Layering, in order:
  1. alpine-minirootfs  -> busybox + applet symlinks (/bin/sh!), /etc/passwd,
                           /var /dev /proc /sys, CA bundle. This base layer is
                           NOT optional: without it there is no shell, and every
                           child_process.exec() / npm script / agent CLI dies.
  2. .apk closure       -> nodejs, npm, git, bash, curl, ripgrep, python3, ...

Alpine splits packages across TWO repositories. `npm` and `ripgrep` live in
`community`, not `main`; indexing only `main` silently drops them. Both indexes
are fetched and merged here, and `main` wins on a name conflict.

An unresolved dependency is a HARD FAILURE, not a warning: a rootfs that is
missing one .so fails at exec time with a linker error that is very hard to
diagnose from an Android logcat.

Usage:
    python3 tools/build-alpine-rootfs.py --out /tmp/alpine/out

Verified target: Node.js v22.23.2 (Alpine 3.22, aarch64) inside PRoot on
ARM64 Android 16 -- see PAYLOAD-RESEARCH.md.
"""
import argparse
import gzip
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zlib

REPO = "https://dl-cdn.alpinelinux.org/alpine"
VER = os.environ.get("ALPINE_VER", "v3.22")
ARCH = "aarch64"
# Alpine splits the package set across two repositories. npm and ripgrep live in
# `community`, everything else we seed is in `main`. Indexing only `main` left
# them unresolved, and the rootfs then had no npm to install the agent.
REPOS = ["main", "community"]
REPOSITORIES = ("main", "community")  # order matters: first wins on conflict

SEEDS = [
    "nodejs", "npm", "git", "bash", "curl", "ripgrep", "python3",
    "ca-certificates",
    # Base-layer guards. The minirootfs already provides these, but seeding them
    # means the closure stays correct if the base image is ever swapped for a
    # slimmer one that only carries the loader.
    "busybox", "alpine-baselayout", "musl",
]

# Diagnostic-only additions. `build-base` + `musl-dev` let us compile a syscall
# probe INSIDE the rootfs on the device, which is how we identify the syscall
# Android's app seccomp filter kills (see tools/syscall-probe.c). Adds ~200 MB,
# so this is opt-in and must stay out of the shipping payload.
if os.environ.get("WITH_TOOLCHAIN") == "1":
    SEEDS += ["build-base", "musl-dev"]

# Every one of these must exist in the finished rootfs or the build is rejected.
# Alpine installs shells into /bin (not /usr/bin): bash, busybox and the busybox
# applet symlinks all live there.
REQUIRED_BINARIES = [
    "bin/sh", "bin/busybox", "bin/bash",
    "usr/bin/node", "usr/bin/npm", "usr/bin/git",
    "usr/bin/rg", "usr/bin/curl", "usr/bin/python3",
    "lib/ld-musl-aarch64.so.1",
    "etc/ssl/certs/ca-certificates.crt",
    "etc/passwd", "etc/resolv.conf",
]

PROOT_URL = ("https://github.com/proot-me/proot/releases/download/v5.3.0/"
             "proot-v5.3.0-aarch64-static")
PROOT_SHA256 = "fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45"

TAR_ENV = dict(os.environ, COPYFILE_DISABLE="1")


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "dsh-payload-build"})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# --------------------------------------------------------------------------
# APKINDEX
# --------------------------------------------------------------------------
def parse_index(raw, repo):
    """APKINDEX.tar.gz bytes -> {name: {...,'repo': repo}}."""
    text = gzip.decompress(raw).decode("utf-8", "replace")
    pkgs, cur = {}, {}
    for line in text.splitlines():
        if not line:
            if cur.get("name"):
                pkgs[cur["name"]] = cur
            cur = {}
            continue
        k, _, v = line.partition(":")
        if k == "P":
            cur["name"] = v
        elif k == "V":
            cur["version"] = v
        elif k == "D":
            cur["depends"] = v.split()
        elif k == "p":
            # A single p: line carries SPACE-separated provides, e.g.
            #   p:nodejs-lts=22.23.2-r0 cmd:node=22.23.2-r0
            # so:libz.so.1=1.3.2
            # Splitting only on "=" yields one bogus token and silently loses
            # every provider but the first.
            for item in v.split():
                cur.setdefault("provides", []).append(item.split("=")[0])
    if cur.get("name"):
        pkgs[cur["name"]] = cur
    for p in pkgs.values():
        p["repo"] = repo
        # APKINDEX has no F: field; Alpine lays packages out as <name>-<version>.apk
        p["filename"] = "%s-%s.apk" % (p["name"], p["version"])
    return pkgs


def load_indexes():
    merged, per_repo = {}, {}
    for repo in REPOSITORIES:
        url = "%s/%s/%s/%s/APKINDEX.tar.gz" % (REPO, VER, repo, ARCH)
        raw = fetch(url)
        digest = hashlib.sha256(raw).hexdigest()
        idx = parse_index(raw, repo)
        per_repo[repo] = (len(idx), digest)
        for name, rec in idx.items():
            # main wins: iterate in REPOSITORIES order and never overwrite.
            merged.setdefault(name, rec)
    return merged, per_repo


def build_provider_map(pkgs):
    """Provides token (so:libz.so.1, cmd:node, ...) -> owning package name."""
    prov = {}
    for name, p in pkgs.items():
        prov.setdefault(name, name)
        for item in p.get("provides", []):
            prov.setdefault(item, name)
    return prov


def resolve(pkgs, seeds):
    """Walk depends/provides. Returns (closure, missing).

    Dedup is keyed on the RESOLVED package name, not on the request token:
    `libgcc`, `so:libgcc.so.1` and `cmd:gcc` are three different tokens that all
    resolve to one package. Keying on the token adds that package to the closure
    three times (and downloads it three times).
    """
    prov = build_provider_map(pkgs)
    seen, closure, missing = set(), [], []
    unresolved_tokens = set()
    stack = list(seeds)
    while stack:
        want = stack.pop(0)
        name = prov.get(want)
        if name is None or name not in pkgs:
            unresolved_tokens.add(want)
            continue
        if name in seen:
            continue
        seen.add(name)
        closure.append(pkgs[name])
        for dep in pkgs[name].get("depends", []):
            if dep.startswith("!"):
                continue                      # conflict marker, not a requirement
            token = dep.split("=")[0].split(">")[0].split("<")[0]
            # "/bin/sh" is a path dependency: satisfied by the minirootfs base
            # layer, never by an .apk in the closure.
            if token and not token.startswith("/"):
                stack.append(token)
    closure.sort(key=lambda p: p["name"])
    return closure, sorted(unresolved_tokens)


# --------------------------------------------------------------------------
# .apk extraction
# --------------------------------------------------------------------------
def apk_data_segment(apk_bytes):
    """Return the LAST gzip member of an .apk (the data tar).

    Layout verified on Alpine 3.22 apks:
      member 0 -> .SIGN.RSA...   (signature)
      member 1 -> .PKGINFO etc.  (control)
      member 2 -> usr/...        (data)   <-- this one
    """
    pos, last = 0, None
    while pos < len(apk_bytes):
        obj = zlib.decompressobj(31)
        try:
            out = obj.decompress(apk_bytes[pos:])
            out += obj.flush()
        except zlib.error:
            break
        consumed = len(apk_bytes) - pos - len(obj.unused_data)
        if consumed <= 0:
            break
        last = out
        pos += consumed
    if last is None:
        raise ValueError("no gzip members found in .apk")
    return last


def unpack(apk_bytes, root):
    tar_bytes = apk_data_segment(apk_bytes)
    p = subprocess.run(
        ["tar", "--no-mac-metadata", "-pxf", "-", "-C", root],
        input=tar_bytes, env=TAR_ENV,
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
    )
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace").strip())


# --------------------------------------------------------------------------
def stage_minirootfs(root, work):
    """Layer 1: the base image. Provides /bin/sh via busybox + full /etc."""
    listing = fetch("%s/%s/releases/%s/" % (REPO, VER, ARCH)).decode("utf-8", "replace")
    names = sorted(set(re.findall(r"alpine-minirootfs-[0-9.]+-%s\.tar\.gz" % ARCH, listing)))
    if not names:
        raise RuntimeError("no alpine-minirootfs listed at %s/%s/releases/%s/" % (REPO, VER, ARCH))
    name = names[-1]
    url = "%s/%s/releases/%s/%s" % (REPO, VER, ARCH, name)
    blob = fetch(url)
    archive = os.path.join(work, name)
    with open(archive, "wb") as f:
        f.write(blob)
    print("    %s  %d B  sha256:%s" % (name, len(blob), sha256_file(archive)))
    subprocess.run(["tar", "--no-mac-metadata", "-xzf", archive, "-C", root],
                   env=TAR_ENV, check=True)
    return name


def post_install(root):
    """Steps apk's triggers would normally perform. All are pure filesystem work
    because the payloads are aarch64 and cannot be executed on this host."""
    for d in ("etc/ssl/certs", "tmp", "root", "var/tmp", "var/cache", "var/log",
              "dev", "proc", "sys", "run", "home"):
        os.makedirs(os.path.join(root, d), exist_ok=True)
    os.chmod(os.path.join(root, "tmp"), 0o1777)
    rc = os.path.join(root, "etc", "resolv.conf")
    if not os.path.exists(rc):
        with open(rc, "w") as f:
            f.write("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    # CA bundle. Normally apk's ca-certificates trigger runs
    # `update-ca-certificates`, which concatenates /usr/share/ca-certificates/*
    # into /etc/ssl/certs/ca-certificates.crt. That script is aarch64 and cannot
    # run here, so do the concatenation directly.
    #
    # Do NOT "copy /etc/ssl/cert.pem into place": that path is a SYMLINK to
    # certs/ca-certificates.crt, so opening it for write truncates the very file
    # being read and silently produces a 0-byte CA store. That broke the first
    # build of this script.
    src_dir = os.path.join(root, "usr", "share", "ca-certificates")
    bundle = os.path.join(root, "etc", "ssl", "certs", "ca-certificates.crt")
    certs = []
    if os.path.isdir(src_dir):
        for dirpath, _, files in os.walk(src_dir):
            certs.extend(os.path.join(dirpath, f) for f in files if f.endswith(".crt"))
    if certs:
        with open(bundle, "wb") as out:
            for c in sorted(certs):
                with open(c, "rb") as f:
                    blob = f.read().rstrip() + b"\n"
                out.write(blob)
        os.chmod(bundle, 0o644)
        print("    CA bundle: %d certs -> %d B" % (len(certs), os.path.getsize(bundle)))
    else:
        print("    !! no /usr/share/ca-certificates/*.crt found")


def verify(root):
    # lexists, not exists: /bin/sh is a symlink to the ABSOLUTE guest path
    # /bin/busybox, which by definition does not resolve on the macOS host.
    # os.path.exists() follows the link and would report a perfectly good rootfs
    # as broken.
    missing = [b for b in REQUIRED_BINARIES
               if not os.path.lexists(os.path.join(root, b))]
    if missing:
        print("!! MISSING required rootfs entries: %s" % ", ".join(missing))
        return False
    node = os.path.join(root, "usr", "bin", "node")
    kind = subprocess.run(["file", "-b", node], capture_output=True, text=True).stdout.strip()
    if "aarch64" not in kind or "ld-musl-aarch64.so.1" not in kind:
        print("!! usr/bin/node is not a musl/aarch64 binary:\n   %s" % kind)
        return False
    print("    node: %s" % kind)
    # A present-but-empty CA bundle defeats every TLS call with an opaque error
    # at runtime, so check the size here rather than discovering it on device.
    ca = os.path.join(root, "etc", "ssl", "certs", "ca-certificates.crt")
    ca_size = os.path.getsize(ca)
    if ca_size < 10_000:
        print("!! CA bundle is only %d B -- TLS will fail" % ca_size)
        return False
    print("    CA bundle: %d B" % ca_size)
    return True


def make_archive(root, out_dir):
    """macOS-safe rootfs.tar.gz containing the rootfs CONTENTS at top level.

    proot's `-r` points straight at the runtime root, so the archive must
    unpack to usr/, bin/, lib/, ... directly -- NOT to a rootfs/ wrapper dir.

    COPYFILE_DISABLE + --no-mac-metadata stop bsdtar injecting AppleDouble
    '._*' sidecars (which we hit on our first device push). --uid/--gid/--uname/
    --gname pin ownership to root so the archive unpacks correctly under PRoot
    regardless of which uid built it.
    """
    dest = os.path.join(out_dir, "rootfs.tar.gz")
    cmd = ["tar", "--no-mac-metadata", "--uid", "0", "--gid", "0",
           "--uname", "root", "--gname", "root",
           "-czf", dest, "-C", root, "."]
    subprocess.run(cmd, env=TAR_ENV, check=True)

    # Verify rather than assume: no AppleDouble, no apk metadata, node present,
    # and the rootfs contents really are at the top level.
    bad, names = [], []
    with tarfile.open(dest, "r:gz") as tf:
        names = tf.getnames()
    for member in names:
        base_name = os.path.basename(member)
        if base_name.startswith("._"):
            bad.append(member)
        if base_name in (".PKGINFO", ".post-install", ".post-deinstall",
                         ".pre-deinstall", ".trigger", ".SIGN.RSA.alpine-devel@lists.alpinelinux.org-616ae350.rsa.pub"):
            bad.append(member)
    if bad:
        print("!! archive contains %d junk entries, first: %s" % (len(bad), bad[:5]))
        return None
    node_entry = [m for m in names if m.lstrip("./") == "usr/bin/node"]
    if not node_entry:
        print("!! archive has no top-level usr/bin/node")
        return None
    top = sorted({m.split("/")[1] for m in names
                  if m.startswith("./") and len(m.split("/")) > 2})
    print("    %d entries, 0 AppleDouble, 0 apk-metadata leaks" % len(names))
    print("    top level: %s" % " ".join(top))
    print("    node entry: %s" % node_entry[0])
    return dest


def fetch_proot(out_dir):
    dest = os.path.join(out_dir, "proot")
    if not os.path.exists(dest):
        blob = fetch(PROOT_URL)
        with open(dest, "wb") as f:
            f.write(blob)
    digest = sha256_file(dest)
    ok = digest == PROOT_SHA256
    size = os.path.getsize(dest)
    kind = subprocess.run(["file", "-b", dest], capture_output=True, text=True).stdout.strip()
    print("    proot: %d B  sha256:%s  %s" % (size, digest, "MATCH" if ok else "MISMATCH"))
    print("            %s" % kind)
    if not ok:
        raise SystemExit("proot checksum mismatch: expected %s" % PROOT_SHA256)
    os.chmod(dest, 0o755)
    return dest


def human(n):
    for unit in ("B", "KiB", "MiB", "GiB"):
        if n < 1024 or unit == "GiB":
            return "%.1f %s" % (n, unit)
        n /= 1024.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="/tmp/alpine/out",
                    help="output dir for rootfs.tar.gz and proot")
    ap.add_argument("--work", default="/tmp/alpine/work", help="scratch dir")
    ap.add_argument("--root", default=None, help="rootfs staging dir")
    ap.add_argument("--keep", action="store_true", help="reuse an existing rootfs")
    args = ap.parse_args()

    out_dir = os.path.abspath(args.out)
    root = os.path.abspath(args.root or os.path.join(os.path.dirname(out_dir), "rootfs"))
    work = os.path.abspath(args.work)
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(work, exist_ok=True)

    if os.path.exists(root) and not args.keep:
        shutil.rmtree(root)
    os.makedirs(root, exist_ok=True)

    print("==> indexing Alpine %s %s (%s)" % (VER, ARCH, "+".join(REPOSITORIES)))
    idx, per_repo = load_indexes()
    for repo, (count, digest) in per_repo.items():
        print("    %-10s %5d packages  APKINDEX sha256:%s" % (repo, count, digest))
    print("    merged    %5d packages" % len(idx))

    closure, missing = resolve(idx, SEEDS)
    if missing:
        print("\n!! MISSING (%d unresolved providers) -- the rootfs would fail at "
              "link/exec time:" % len(missing))
        for m in missing:
            print("     MISSING: %s" % m)
        raise SystemExit(2)
    print("==> dependency closure: %d packages" % len(closure))
    for p in closure:
        print("    %-22s %-14s %s" % (p["name"], p["version"], p["repo"]))

    print("==> layer 1: minirootfs base (busybox /bin/sh, /etc/passwd, base dirs)")
    stage_minirootfs(root, work)

    print("==> layer 2: apk closure")
    for p in closure:
        url = "%s/%s/%s/%s/%s" % (REPO, VER, p["repo"], ARCH, p["filename"])
        blob = fetch(url)
        unpack(blob, root)
        print("    ok %-22s %-14s %9d B  (%s)" % (p["name"], p["version"], len(blob), p["repo"]))

    print("==> post-install")
    post_install(root)

    print("==> verify")
    if not verify(root):
        raise SystemExit(3)
    for b in REQUIRED_BINARIES:
        p = os.path.join(root, b)
        kind = "symlink" if os.path.islink(p) else "file"
        print("    %-40s %-8s %d B" % (b, kind, os.path.getsize(p) if kind == "file" else 0))

    print("==> packaging")
    archive = make_archive(root, out_dir)
    if archive is None:
        raise SystemExit(4)
    size = os.path.getsize(archive)
    print("    rootfs.tar.gz  %s (%d B)" % (human(size), size))
    print("    sha256        %s" % sha256_file(archive))

    print("==> proot")
    fetch_proot(out_dir)

    print("\nOK  out dir: %s" % out_dir)


if __name__ == "__main__":
    main()