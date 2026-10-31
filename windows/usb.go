package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sync/atomic"
	"time"
)

var usbState atomic.Value // "no-adb" | "no-device" | "ready"

func init() { usbState.Store("no-adb") }

// usbLoop keeps an `adb reverse` tunnel up while a phone is plugged in with USB debugging on,
// so the phone can also reach this server over the cable (used together with Wi-Fi).
func usbLoop() {
	name := "adb"
	if runtime.GOOS == "windows" {
		name = "adb.exe"
	}
	adb, err := exec.LookPath(name)
	if err != nil {
		exe, _ := os.Executable()
		p := filepath.Join(filepath.Dir(exe), name)
		if _, e := os.Stat(p); e != nil {
			return
		}
		adb = p
	}
	port := fmt.Sprintf("tcp:%d", httpPort)
	for {
		cmd := exec.Command(adb, "reverse", port, port)
		hideWindow(cmd)
		if cmd.Run() != nil {
			usbState.Store("no-device")
			time.Sleep(6 * time.Second)
		} else {
			usbState.Store("ready")
			time.Sleep(4 * time.Second)
		}
	}
}
