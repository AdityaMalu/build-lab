"""Locks the current process down before any submitted code runs (Linux x86-64 only).

Installs a seccomp filter on every thread that refuses to:
  start programs (execve), create processes (fork / non-thread clone), open network sockets
  (anything but AF_UNIX), trace or poke other processes, send signals with kill/tkill,
  change user ids, mount filesystems, or use io_uring / bpf / perf / keyrings / modules.
The same policy is implemented for Go (testkit/go/labsandbox) and C++ (testkit/cpp/labsandbox.cpp).
Resource limits and the unprivileged user are applied by the server (prlimit + setpriv).
"""

import ctypes
import platform
import struct
import sys

AUDIT_ARCH_X86_64 = 0xC000003E
RET_KILL_PROCESS = 0x80000000
RET_ALLOW = 0x7FFF0000
LD_W_ABS, JEQ, JGE, JSET, RET = 0x20, 0x15, 0x35, 0x45, 0x06
EPERM, EACCES, ENOSYS = 1, 13, 38
CLONE_THREAD = 0x00010000
AF_UNIX = 1

# x86-64 syscall numbers that are always refused with EPERM
DENIED = [
    59, 322,            # execve, execveat
    57, 58,             # fork, vfork
    101, 310, 311, 312,  # ptrace, process_vm_readv/writev, kcmp
    62, 200,            # kill, tkill (tgkill stays allowed for the runtime's own threads)
    165, 166, 155, 161, 272, 308,  # mount, umount2, pivot_root, chroot, unshare, setns
    321, 298, 323,      # bpf, perf_event_open, userfaultfd
    425, 426, 427,      # io_uring_setup/enter/register
    248, 249, 250,      # add_key, request_key, keyctl
    246, 175, 313, 176,  # kexec_load, init_module, finit_module, delete_module
    169, 167, 168, 163, 179,  # reboot, swapon, swapoff, acct, quotactl
    105, 106, 113, 114, 116, 117, 119,  # setuid, setgid, setreuid, setregid, setgroups, setresuid, setresgid
]
SYS_CLONE, SYS_CLONE3, SYS_SOCKET, SYS_SOCKETPAIR, SYS_SECCOMP = 56, 435, 41, 53, 317


def errno(e):
    return 0x00050000 | e


def program():
    p = [
        (LD_W_ABS, 0, 0, 4), (JEQ, 1, 0, AUDIT_ARCH_X86_64), (RET, 0, 0, RET_KILL_PROCESS),
        (LD_W_ABS, 0, 0, 0), (JGE, 0, 1, 0x40000000), (RET, 0, 0, RET_KILL_PROCESS),  # no x32 ABI
    ]
    for nr in DENIED:
        p += [(JEQ, 0, 1, nr), (RET, 0, 0, errno(EPERM))]
    p += [(JEQ, 0, 1, SYS_CLONE3), (RET, 0, 0, errno(ENOSYS))]  # libc falls back to clone
    # clone: only threads (CLONE_THREAD set)
    p += [(JEQ, 0, 4, SYS_CLONE), (LD_W_ABS, 0, 0, 16), (JSET, 1, 0, CLONE_THREAD),
          (RET, 0, 0, errno(EPERM)), (RET, 0, 0, RET_ALLOW)]
    # socket / socketpair: AF_UNIX only
    for nr in (SYS_SOCKET, SYS_SOCKETPAIR):
        p += [(JEQ, 0, 4, nr), (LD_W_ABS, 0, 0, 16), (JEQ, 1, 0, AF_UNIX),
              (RET, 0, 0, errno(EACCES)), (RET, 0, 0, RET_ALLOW)]
    p += [(RET, 0, 0, RET_ALLOW)]
    return p


class SockFprog(ctypes.Structure):
    _fields_ = [("len", ctypes.c_ushort), ("filter", ctypes.c_void_p)]


def lock_down():
    if not sys.platform.startswith("linux"):
        return  # local development on Windows/macOS: no sandbox
    if platform.machine() not in ("x86_64", "AMD64"):
        raise SystemExit("sandbox: unsupported architecture " + platform.machine())
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(38, 1, 0, 0, 0) != 0:  # PR_SET_NO_NEW_PRIVS
        raise SystemExit("sandbox: prctl failed")
    code = b"".join(struct.pack("<HBBI", *ins) for ins in program())
    buf = ctypes.create_string_buffer(code, len(code))
    prog = SockFprog(len(code) // 8, ctypes.cast(buf, ctypes.c_void_p))
    # seccomp(SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog)
    if libc.syscall(ctypes.c_long(SYS_SECCOMP), ctypes.c_long(1), ctypes.c_long(1), ctypes.byref(prog)) != 0:
        raise SystemExit("sandbox: seccomp failed, errno %d" % ctypes.get_errno())
