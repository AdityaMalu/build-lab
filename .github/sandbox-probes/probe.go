package ratelimiter

// Sandbox probe (CI only): runs in init(), after the sandbox package; every line must say BLOCKED or OK.

import (
	"fmt"
	"net"
	"os"
	"syscall"
	"time"
)

func probe(name string, err error) {
	if err != nil {
		fmt.Println("PROBE BLOCKED:", name, "-", err)
	} else {
		fmt.Println("PROBE ALLOWED:", name)
	}
}

func init() {
	_, err := os.ReadFile("/etc/shadow")
	probe("read /etc/shadow", err)
	probe("write into the app", os.WriteFile("/app/web/pwned.txt", []byte("x"), 0o644))
	c, err := net.DialTimeout("tcp", "1.1.1.1:80", 3*time.Second)
	if c != nil {
		c.Close()
	}
	probe("internet socket", err)
	_, err = syscall.ForkExec("/bin/echo", []string{"echo", "hi"}, nil)
	probe("start a process", err)
	probe("signal pid 1", syscall.Kill(1, 0))
	if f, err := os.CreateTemp("", "ok"); err == nil {
		f.Close()
		fmt.Println("PROBE OK: write own temp file")
	} else {
		fmt.Println("PROBE BROKEN: own temp file", err)
	}
}
