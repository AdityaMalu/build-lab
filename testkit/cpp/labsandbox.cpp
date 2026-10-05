// Locks the process down before any submitted code runs, when LAB_SANDBOX=1 (Linux x86-64 and arm64).
// constructor(101) runs before every ordinary static initializer, including ones in the code under test.
// Same policy as testkit/python/lab_sandbox.py and testkit/go/labsandbox.
// On any other Linux architecture a sandboxed run refuses to start.
#if defined(__linux__)

#include <cstdio>
#include <cstdlib>
#include <cstring>

#if defined(__x86_64__) || defined(__aarch64__)

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <cerrno>
#include <cstddef>
#include <vector>

namespace {

#if defined(__x86_64__)
constexpr unsigned kAuditArch = AUDIT_ARCH_X86_64;
constexpr bool kRefuseX32 = true;  // x86-64 also accepts x32 syscall numbers (>= 0x40000000)
constexpr unsigned kClone = 56, kClone3 = 435, kSocket = 41, kSocketpair = 53;
constexpr unsigned kDenied[] = {
    59, 322,             // execve, execveat
    57, 58,              // fork, vfork
    101, 310, 311, 312,  // ptrace, process_vm_readv/writev, kcmp
    62, 200,             // kill, tkill
    165, 166, 155, 161, 272, 308,         // mount, umount2, pivot_root, chroot, unshare, setns
    321, 298, 323,                        // bpf, perf_event_open, userfaultfd
    425, 426, 427,                        // io_uring
    248, 249, 250,                        // add_key, request_key, keyctl
    246, 175, 313, 176,                   // kexec_load, init_module, finit_module, delete_module
    169, 167, 168, 163, 179,              // reboot, swapon, swapoff, acct, quotactl
    105, 106, 113, 114, 116, 117, 119,    // setuid, setgid, setreuid, setregid, setgroups, setresuid, setresgid
};
#else  // arm64: generic syscall table, no fork/vfork syscalls (processes come from clone)
constexpr unsigned kAuditArch = AUDIT_ARCH_AARCH64;
constexpr bool kRefuseX32 = false;
constexpr unsigned kClone = 220, kClone3 = 435, kSocket = 198, kSocketpair = 199;
constexpr unsigned kDenied[] = {
    221, 281,            // execve, execveat
    117, 270, 271, 272,  // ptrace, process_vm_readv/writev, kcmp
    129, 130,            // kill, tkill
    40, 39, 41, 51, 97, 268,              // mount, umount2, pivot_root, chroot, unshare, setns
    280, 241, 282,                        // bpf, perf_event_open, userfaultfd
    425, 426, 427,                        // io_uring
    217, 218, 219,                        // add_key, request_key, keyctl
    104, 105, 273, 106,                   // kexec_load, init_module, finit_module, delete_module
    142, 224, 225, 89, 60,                // reboot, swapon, swapoff, acct, quotactl
    146, 144, 145, 143, 159, 147, 149,    // setuid, setgid, setreuid, setregid, setgroups, setresuid, setresgid
};
#endif

void deny(std::vector<sock_filter>& p, unsigned nr, unsigned err) {
    p.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, nr, 0, 1));
    p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | err));
}

__attribute__((constructor(101))) void labSandbox() {
    const char* flag = std::getenv("LAB_SANDBOX");
    if (!flag || std::strcmp(flag, "1") != 0) return;

    const unsigned dataNr = offsetof(seccomp_data, nr);
    const unsigned dataArch = offsetof(seccomp_data, arch);
    const unsigned dataArg0 = offsetof(seccomp_data, args);

    std::vector<sock_filter> p;
    p.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, dataArch));
    p.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kAuditArch, 1, 0));
    p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS));
    p.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, dataNr));
    if (kRefuseX32) {
        p.push_back(BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000, 0, 1));
        p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS));
    }
    for (unsigned nr : kDenied) deny(p, nr, EPERM);
    deny(p, kClone3, ENOSYS);  // clone3: libc falls back to clone
    // clone: threads only (the flags are argument 0 on both architectures)
    p.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kClone, 0, 4));
    p.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, dataArg0));
    p.push_back(BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, CLONE_THREAD, 1, 0));
    p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM));
    p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
    // socket / socketpair: AF_UNIX only
    for (unsigned nr : {kSocket, kSocketpair}) {
        p.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, nr, 0, 4));
        p.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, dataArg0));
        p.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 1 /* AF_UNIX */, 1, 0));
        p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES));
        p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
    }
    p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));

    sock_fprog prog{static_cast<unsigned short>(p.size()), p.data()};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0 ||
        syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog) != 0) {
        std::fprintf(stderr, "sandbox: could not install seccomp filter (errno %d)\n", errno);
        std::_Exit(99);
    }
}

}  // namespace

#else  // Linux, but no filter for this architecture: never run submitted code unprotected

namespace {
__attribute__((constructor(101))) void labSandbox() {
    const char* flag = std::getenv("LAB_SANDBOX");
    if (flag && std::strcmp(flag, "1") == 0) {
        std::fprintf(stderr, "sandbox: unsupported architecture\n");
        std::_Exit(99);
    }
}
}  // namespace

#endif
#endif  // __linux__
