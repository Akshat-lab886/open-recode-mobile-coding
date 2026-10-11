#!/usr/bin/env python3
"""
cross-check-musl.py — does musl reference any syscall Android traps?

The exhaustive scan (tools/syscall-scan-full.c) found 166 trapped syscalls.
That number sounds alarming, but almost all of them are numbers the app will
never use. The question that actually matters is narrow and answerable
offline:

    which syscalls does musl's libc/loader actually issue?

We recover that set directly from the ELF by decoding every `movz x8, #imm`
instruction (aarch64 sets the syscall number in x8), then intersect it with
the measured trapped set. Anything in the intersection is a hard blocker for
the PRoot route; anything outside it is irrelevant.

Usage:
    python3 tools/cross-check-musl.py --loader <ld-musl-aarch64.so.1> --trapped <trapped.txt>
"""

from __future__ import annotations

import argparse
import struct

# aarch64: MOVZ (64-bit) writing x8.
#   sf=1 opc=10 100101 hw=00 imm16 Rd=8
#   31    30-29 28-23  22-21 20-5  4-0
MOVZ_X8_MASK = 0xFFE0001F
MOVZ_X8_VAL = 0xD2800008

NAMES = {
    17: "getcwd", 18: "eventfd2", 19: "epoll_create", 22: "pipe",
    29: "ioctl", 39: "mount", 40: "umount2", 42: "swapoff",
    51: "acct", 56: "openat", 58: "vhangup", 63: "read", 64: "write",
    78: "readlinkat", 79: "newfstatat", 89: "readlink", 93: "exit",
    94: "exit_group", 96: "set_tid_address", 98: "futex", 99: "setuid",
    100: "setgid", 104: "getgid", 134: "rt_sigaction", 135: "rt_sigprocmask",
    160: "uname", 172: "getpid", 178: "gettid", 179: "sysinfo",
    198: "socket", 200: "bind", 214: "brk", 215: "munmap",
    217: "getdents64", 218: "set_tid_address", 220: "clone",
    222: "mmap", 226: "mprotect", 233: "madvise", 260: "wait4",
    261: "prlimit64", 278: "getrandom", 283: "membarrier", 291: "statx",
    292: "pidfd_send_signal", 293: "rseq", 425: "io_uring_setup",
    426: "io_uring_enter", 435: "clone3", 436: "close_range",
    439: "faccessat2", 422: "futex_time64",
}


def musl_syscalls(data: bytes) -> dict[int, int]:
    out: dict[int, int] = {}
    for i in range(0, len(data) - 3):
        (word,) = struct.unpack_from("<I", data, i)
        if (word & MOVZ_X8_MASK) == MOVZ_X8_VAL:
            imm = (word >> 5) & 0xFFFF
            if ((word >> 21) & 0x3) == 0:
                out[imm] = out.get(imm, 0) + 1
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--loader", required=True)
    ap.add_argument("--trapped", required=True)
    args = ap.parse_args()

    trapped = {int(x) for x in open(args.trapped) if x.strip()}
    used = musl_syscalls(open(args.loader, "rb").read())

    print(f"trapped by Android's app seccomp : {len(trapped)}")
    print(f"distinct syscalls musl issues    : {len(used)}")

    clash = sorted(n for n in used if n in trapped)
    print(f"\nINTERSECTION (musl AND trapped)  : {len(clash)}")
    for n in clash:
        print(f"   {n:4}  {NAMES.get(n, '?'):18} x{used[n]}")

    if not clash:
        print("\n=> musl never calls a trapped syscall. PRoot's guest is viable.")
    else:
        print("\n=> each of the above kills any guest process that reaches it.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())