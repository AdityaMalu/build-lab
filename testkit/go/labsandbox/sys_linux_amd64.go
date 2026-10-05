package labsandbox

const (
	auditArch     = 0xC000003E
	refuseX32     = true // x86-64 also accepts x32 syscall numbers (>= 0x40000000)
	sysClone      = 56
	sysSocket     = 41
	sysSocketpair = 53
	sysSeccomp    = 317
)

var denied = []uint32{
	59, 322, // execve, execveat
	57, 58, // fork, vfork
	101, 310, 311, 312, // ptrace, process_vm_readv/writev, kcmp
	62, 200, // kill, tkill
	165, 166, 155, 161, 272, 308, // mount, umount2, pivot_root, chroot, unshare, setns
	321, 298, 323, // bpf, perf_event_open, userfaultfd
	425, 426, 427, // io_uring
	248, 249, 250, // add_key, request_key, keyctl
	246, 175, 313, 176, // kexec_load, init_module, finit_module, delete_module
	169, 167, 168, 163, 179, // reboot, swapon, swapoff, acct, quotactl
	105, 106, 113, 114, 116, 117, 119, // setuid, setgid, setreuid, setregid, setgroups, setresuid, setresgid
}
