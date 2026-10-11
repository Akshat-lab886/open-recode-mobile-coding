#!/usr/bin/env python3
"""
patch-musl-faccessat2.py — make musl stop calling faccessat2(2).

WHY
---
Android's app seccomp filter traps faccessat2(2) with SECCOMP_RET_TRAP, which
KILLS the process rather than returning ENOSYS. musl's faccessat() calls
faccessat2(2) first and falls back to plain faccessat(2) only on -ENOSYS:

    if (flag) {
            int ret = __syscall(SYS_faccessat2, fd, filename, amode, flag);
            if (ret != -ENOSYS) return __syscall_ret(ret);
    }

So on Android the fallback is unreachable and the process dies. This was
confirmed on device: a libc probe that exercises one call at a time survives
openat/access/stat/getpwuid/fopen/setlocale/clock_gettime/socket/getaddrinfo/
pthread_create/fork and dies on exactly faccessat(AT_EACCESS).

WHAT THIS DOES
--------------
There is exactly ONE `mov x8, #439` instruction in musl's libc for aarch64
(verified by disassembling every instruction and collecting every immediate
written to x8: 231 distinct syscall numbers, of which 439 occurs once). We
rewrite that single immediate to an unassigned syscall number, so the kernel
answers -ENOSYS and musl takes its normal, portable fallback path.

This is a one-instruction change with no other behaviour affected. We refuse
to patch unless the count is exactly 1, so a different musl build can never be
silently corrupted.

The same script also REPORTS whether musl references rseq (293) or io_uring
(425/426). It does not: both are 0 in musl, which independently confirms that
rseq was never a real blocker and that io_uring is libuv's, not libc's.

Usage:
    python3 tools/patch-musl-faccessat2.py --in <libc.so> --out <libc.so>
    python3 tools/patch-musl-faccessat2.py --check <libc.so>
"""

from __future__ import annotations

import argparse
import hashlib
import struct
import sys

# Unassigned on aarch64 (the valid range stops at ~276 for the generic ABI,
# vendor space is far away), so the kernel returns -ENOSYS rather than doing
# anything. 1023 is in the "unused" range and is never implemented.
UNASSIGNED = 1023

# aarch64: MOVZ (wide immediate) writing to x8.
#   sf=0 opc=10 100101 hw=00 imm16 Rd   -> 0x52800008 masked with Rd bits
MOVZ_X8_MASK = 0x7F80001F
MOVZ_X8_VALUE = 0x52800008

FACESSAT2_NR = 439  # __NR_faccessat2
RSEQ_NR = 293
IO_URING_SETUP_NR = 425
IO_URING_ENTER_NR = 426


def syscall_immediates(data: bytes) -> dict[int, list[int]]:
    """Map syscall number -> list of byte offsets, for movz x8, #NR."""
    found: dict[int, list[int]] = {}
    for i in range(0, len(data) - 3):
        (word,) = struct.unpack_from("<I", data, i)
        if (word & MOVZ_X8_MASK) == MOVZ_X8_VALUE:
            imm = (word >> 5) & 0xFFFF
            hw = (word >> 21) & 0x3
            if hw == 0:
                found.setdefault(imm, []).append(i)
    return found


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src")
    ap.add_argument("--out", dest="dst")
    ap.add_argument("--check", dest="check")
    args = ap.parse_args()

    if args.check:
        data = open(args.check, "rb").read()
        imms = syscall_immediates(data)
        print(f"libc: {len(data)} bytes")
        print(f"distinct syscall immediates: {len(imms)}")
        for nr, label in [
            (FACESSAT2_NR, "faccessat2"),
            (RSEQ_NR, "rseq"),
            (IO_URING_SETUP_NR, "io_uring_setup"),
            (IO_URING_ENTER_NR, "io_uring_enter"),
        ]:
            n = len(imms.get(nr, []))
            note = "PATCH TARGET" if nr == FACESSAT2_NR else ""
            print(f"  {label:16} nr={nr:4} count={n} {note}")
        return 0

    if not (args.src and args.dst):
        ap.error("--in and --out are required (or use --check)")

    data = bytearray(open(args.src, "rb").read())
    before = hashlib.sha256(bytes(data)).hexdigest()
    imms = syscall_immediates(bytes(data))
    sites = imms.get(FACESSAT2_NR, [])

    if len(sites) != 1:
        print(
            f"REFUSING TO PATCH: expected exactly 1 faccessat2 call site, "
            f"found {len(sites)}. Refusing so an unexpected musl build is "
            f"never silently corrupted.",
            file=sys.stderr,
        )
        return 1

    off = sites[0]
    (word,) = struct.unpack_from("<I", data, off)
    old_imm = (word >> 5) & 0xFFFF
    new_word = (word & ~(0xFFFF << 5)) | (UNASSIGNED << 5)
    struct.pack_into("<I", data, off, new_word)

    open(args.dst, "wb").write(data)
    after = hashlib.sha256(data).hexdigest()

    print(f"patched 1 instruction at offset {off} (0x{off:x})")
    print(f"  mov x8, #{old_imm}  ->  mov x8, #{UNASSIGNED}")
    print(f"  kernel will return -ENOSYS, so musl uses its faccessat fallback")
    print(f"  before: {before}")
    print(f"  after:  {after}")

    verify = syscall_immediates(bytes(data))
    assert FACESSAT2_NR not in verify, "patch did not take"
    assert len(verify.get(UNASSIGNED, [])) == 1, "unexpected extra patch"
    print("verified: faccessat2 no longer referenced")
    return 0


if __name__ == "__main__":
    sys.exit(main())