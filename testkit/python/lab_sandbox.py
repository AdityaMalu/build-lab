"""Locks the current process down before any submitted code runs (Linux x86-64 and arm64).

Installs a seccomp filter on every thread that refuses to:
  start programs (execve), create processes (fork / non-thread clone), open network sockets
  (anything but AF_UNIX), trace or poke other processes, send signals with kill/tkill,
  change user ids, mount filesystems, or use io_uring / bpf / perf / keyrings / modules.
The same policy is implemented for Go (testkit/go/labsandbox) and C++ (testkit/cpp/labsandbox.cpp).
Resource limits and the unprivileged user are applied by the server (prlimit + setpriv).
Any other architecture refuses to run rather than run unprotected.
"""

import ctypes
import platform
import struct
import sys

RET_KILL_PROCESS = 0x80000000
RET_ALLOW = 0x7FFF0000
LD_W_ABS, JEQ, JGE, JSET, RET = 0x20, 0x15, 0x35, 0x45, 0x06
EPERM, EACCES, ENOSYS = 1, 13, 38
CLONE_THREAD = 0x00010000
AF_UNIX = 1

ARCHES = {
    "x86_64": dict(
        audit=0xC000003E,
        x32=True,  # x86-64 also accepts x32 syscall numbers (>= 0x40000000): refuse them
        clone=56, clone3=435, socket=41, socketpair=53, seccomp=317,
        denied=[
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
        ],
    ),
    # arm64 uses the generic syscall table and has no fork/vfork syscalls (processes come from clone)
    "aarch64": dict(
        audit=0xC00000B7,
        x32=False,
        clone=220, clone3=435, socket=198, socketpair=199, seccomp=277,
        denied=[
            221, 281,           # execve, execveat
            117, 270, 271, 272,  # ptrace, process_vm_readv/writev, kcmp
            129, 130,           # kill, tkill
            40, 39, 41, 51, 97, 268,  # mount, umount2, pivot_root, chroot, unshare, setns
            280, 241, 282,      # bpf, perf_event_open, userfaultfd
            425, 426, 427,      # io_uring_setup/enter/register
            217, 218, 219,      # add_key, request_key, keyctl
            104, 105, 273, 106,  # kexec_load, init_module, finit_module, delete_module
            142, 224, 225, 89, 60,  # reboot, swapon, swapoff, acct, quotactl
            146, 144, 145, 143, 159, 147, 149,  # setuid, setgid, setreuid, setregid, setgroups, setresuid, setresgid
        ],
    ),
}
ARCHES["AMD64"] = ARCHES["x86_64"]
ARCHES["arm64"] = ARCHES["aarch64"]


def errno(e):
    return 0x00050000 | e


def program(a):
    p = [(LD_W_ABS, 0, 0, 4), (JEQ, 1, 0, a["audit"]), (RET, 0, 0, RET_KILL_PROCESS)]
    p += [(LD_W_ABS, 0, 0, 0)]
    if a["x32"]:
        p += [(JGE, 0, 1, 0x40000000), (RET, 0, 0, RET_KILL_PROCESS)]
    for nr in a["denied"]:
        p += [(JEQ, 0, 1, nr), (RET, 0, 0, errno(EPERM))]
    p += [(JEQ, 0, 1, a["clone3"]), (RET, 0, 0, errno(ENOSYS))]  # libc falls back to clone
    # clone: only threads (CLONE_THREAD set); the flags are argument 0 on both architectures
    p += [(JEQ, 0, 4, a["clone"]), (LD_W_ABS, 0, 0, 16), (JSET, 1, 0, CLONE_THREAD),
          (RET, 0, 0, errno(EPERM)), (RET, 0, 0, RET_ALLOW)]
    # socket / socketpair: AF_UNIX only
    for nr in (a["socket"], a["socketpair"]):
        p += [(JEQ, 0, 4, nr), (LD_W_ABS, 0, 0, 16), (JEQ, 1, 0, AF_UNIX),
              (RET, 0, 0, errno(EACCES)), (RET, 0, 0, RET_ALLOW)]
    p += [(RET, 0, 0, RET_ALLOW)]
    return p


class SockFprog(ctypes.Structure):
    _fields_ = [("len", ctypes.c_ushort), ("filter", ctypes.c_void_p)]


def lock_down():
    if not sys.platform.startswith("linux"):
        return  # local development on Windows/macOS: no sandbox
    a = ARCHES.get(platform.machine())
    if a is None:
        raise SystemExit("sandbox: unsupported architecture " + platform.machine())
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(38, 1, 0, 0, 0) != 0:  # PR_SET_NO_NEW_PRIVS
        raise SystemExit("sandbox: prctl failed")
    code = b"".join(struct.pack("<HBBI", *ins) for ins in program(a))
    buf = ctypes.create_string_buffer(code, len(code))
    prog = SockFprog(len(code) // 8, ctypes.cast(buf, ctypes.c_void_p))
    # seccomp(SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog)
    if libc.syscall(ctypes.c_long(a["seccomp"]), ctypes.c_long(1), ctypes.c_long(1), ctypes.byref(prog)) != 0:
        raise SystemExit("sandbox: seccomp failed, errno %d" % ctypes.get_errno())
