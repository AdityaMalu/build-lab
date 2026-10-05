// Package labsandbox locks the test binary down when LAB_SANDBOX=1 (Linux x86-64).
//
// The lab server imports it from inside the package under test, so it is initialised before that
// package (and therefore before any submitted code) runs. Same policy as testkit/python/lab_sandbox.py.
package labsandbox

import (
	"fmt"
	"os"
	"runtime"
	"syscall"
	"unsafe"
)

const (
	auditArchX8664 = 0xC000003E
	retKill        = 0x80000000
	retAllow       = 0x7FFF0000
	ldWAbs         = 0x20
	jeq            = 0x15
	jge            = 0x35
	jset           = 0x45
	ret            = 0x06
	cloneThread    = 0x00010000
	sysSeccomp     = 317
)

func errno(e uint32) uint32 { return 0x00050000 | e }

var denied = []uint32{
	59, 322, 57, 58, 101, 310, 311, 312, 62, 200,
	165, 166, 155, 161, 272, 308, 321, 298, 323, 425, 426, 427,
	248, 249, 250, 246, 175, 313, 176, 169, 167, 168, 163, 179,
	105, 106, 113, 114, 116, 117, 119,
}

func program() []syscall.SockFilter {
	ins := func(code uint16, jt, jf uint8, k uint32) syscall.SockFilter {
		return syscall.SockFilter{Code: code, Jt: jt, Jf: jf, K: k}
	}
	p := []syscall.SockFilter{
		ins(ldWAbs, 0, 0, 4), ins(jeq, 1, 0, auditArchX8664), ins(ret, 0, 0, retKill),
		ins(ldWAbs, 0, 0, 0), ins(jge, 0, 1, 0x40000000), ins(ret, 0, 0, retKill),
	}
	for _, nr := range denied {
		p = append(p, ins(jeq, 0, 1, nr), ins(ret, 0, 0, errno(1)))
	}
	p = append(p, ins(jeq, 0, 1, 435), ins(ret, 0, 0, errno(38)))
	p = append(p, ins(jeq, 0, 4, 56), ins(ldWAbs, 0, 0, 16), ins(jset, 1, 0, cloneThread),
		ins(ret, 0, 0, errno(1)), ins(ret, 0, 0, retAllow))
	for _, nr := range []uint32{41, 53} {
		p = append(p, ins(jeq, 0, 4, nr), ins(ldWAbs, 0, 0, 16), ins(jeq, 1, 0, 1),
			ins(ret, 0, 0, errno(13)), ins(ret, 0, 0, retAllow))
	}
	return append(p, ins(ret, 0, 0, retAllow))
}

func init() {
	if os.Getenv("LAB_SANDBOX") != "1" {
		return
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	filter := program()
	prog := syscall.SockFprog{Len: uint16(len(filter)), Filter: &filter[0]}
	if _, _, e := syscall.RawSyscall6(syscall.SYS_PRCTL, 38, 1, 0, 0, 0, 0); e != 0 {
		fmt.Fprintln(os.Stderr, "sandbox: prctl failed:", e)
		os.Exit(99)
	}
	// seccomp(SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog): applies to every runtime thread
	if _, _, e := syscall.RawSyscall(sysSeccomp, 1, 1, uintptr(unsafe.Pointer(&prog))); e != 0 {
		fmt.Fprintln(os.Stderr, "sandbox: seccomp failed:", e)
		os.Exit(99)
	}
	runtime.KeepAlive(filter)
}
