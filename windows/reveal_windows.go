//go:build windows

package main

import (
	"os/exec"
	"syscall"
)

// revealFile opens Explorer with the file selected (raw command line: Explorer dislikes Go's quoting).
func revealFile(path string) {
	c := exec.Command("explorer.exe")
	c.SysProcAttr = &syscall.SysProcAttr{CmdLine: `explorer.exe /select,"` + path + `"`}
	c.Start()
}
