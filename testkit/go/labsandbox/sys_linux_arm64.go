package labsandbox

// arm64 uses the generic syscall table and has no fork/vfork syscalls (processes come from clone).
const (
	auditArch     = 0xC00000B7
	refuseX32     = false
	sysClone      = 220
	sysSocket     = 198
	sysSocketpair = 199
	sysSeccomp    = 277
)

var denied = []uint32{
	221, 281, // execve, execveat
	117, 270, 271, 272, // ptrace, process_vm_readv/writev, kcmp
	129, 130, // kill, tkill
	40, 39, 41, 51, 97, 268, // mount, umount2, pivot_root, chroot, unshare, setns
	280, 241, 282, // bpf, perf_event_open, userfaultfd
	425, 426, 427, // io_uring
	217, 218, 219, // add_key, request_key, keyctl
	104, 105, 273, 106, // kexec_load, init_module, finit_module, delete_module
	142, 224, 225, 89, 60, // reboot, swapon, swapoff, acct, quotactl
	146, 144, 145, 143, 159, 147, 149, // setuid, setgid, setreuid, setregid, setgroups, setresuid, setresgid
}
