// Locks the process down before any submitted code runs, when LAB_SANDBOX=1 (Linux x86-64 only).
// constructor(101) runs before every ordinary static initializer, including ones in the code under test.
// Same policy as testkit/python/lab_sandbox.py and testkit/go/labsandbox.
#if defined(__linux__) && defined(__x86_64__)

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {

void deny(std::vector<sock_filter>& p, unsigned nr, unsigned err) {
    p.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, nr, 0, 1));
    p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | err));
}

__attribute__((constructor(101))) void labSandbox() {
    const char* flag = std::getenv("LAB_SANDBOX");
    if (!flag || std::strcmp(flag, "1") != 0) return;

    const unsigned denied[] = {
        59, 322,             // execve, execveat
        57, 58,              // fork, vfork
        101, 310, 311, 312,  // ptrace, process_vm_readv/writev, kcmp
        62, 200,             // kill, tkill
        165, 166, 155, 161, 272, 308,
        321, 298, 323,
        425, 426, 427,
        248, 249, 250,
        246, 175, 313, 176,
        169, 167, 168, 163, 179,
        105, 106, 113, 114, 116, 117, 119,
    };
    const unsigned dataNr = offsetof(seccomp_data, nr);
    const unsigned dataArch = offsetof(seccomp_data, arch);
    const unsigned dataArg0 = offsetof(seccomp_data, args);

    std::vector<sock_filter> p;
    p.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, dataArch));
    p.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_X86_64, 1, 0));
    p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS));
    p.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, dataNr));
    p.push_back(BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000, 0, 1));  // no x32 ABI
    p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS));
    for (unsigned nr : denied) deny(p, nr, EPERM);
    deny(p, 435, ENOSYS);  // clone3: libc falls back to clone
    // clone: threads only
    p.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 56, 0, 4));
    p.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, dataArg0));
    p.push_back(BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, CLONE_THREAD, 1, 0));
    p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM));
    p.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
    // socket / socketpair: AF_UNIX only
    for (unsigned nr : {41u, 53u}) {
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

#endif
