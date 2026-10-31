//go:build !windows

package main

import (
	"os/exec"
	"path/filepath"
)

func revealFile(path string) {
	exec.Command("xdg-open", filepath.Dir(path)).Start()
}
