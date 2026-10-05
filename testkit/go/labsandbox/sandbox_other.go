//go:build !(linux && (amd64 || arm64))

// Package labsandbox has no filter for this platform. That is fine for local development on
// Windows/macOS, but a sandboxed run on any other Linux architecture refuses to start.
package labsandbox

import (
	"fmt"
	"os"
	"runtime"
)

func init() {
	if runtime.GOOS == "linux" && os.Getenv("LAB_SANDBOX") == "1" {
		fmt.Fprintln(os.Stderr, "sandbox: unsupported architecture", runtime.GOARCH)
		os.Exit(99)
	}
}
