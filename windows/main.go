package main

import (
	"embed"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

//go:embed index.html
var assets embed.FS

const (
	httpPort   = 8765
	beaconPort = 8766
	bufSize    = 1 << 20
)

type transfer struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Size  int64  `json:"size"`
	Got   int64  `json:"got"`
	Start int64  `json:"start"`
	End   int64  `json:"end"`
	Path  string `json:"path"`
	f     *os.File
}

type outItem struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Size int64  `json:"size"`
	Sent int64  `json:"sent"`
	Path string `json:"-"`
}

type server struct {
	mu        sync.Mutex
	inbox     map[string]*transfer
	inOrder   []string
	outbox    map[string]*outItem
	outOrder  []string
	recvDir   string
	outDir    string
	lastPhone atomic.Int64
	lastWifi  atomic.Int64
	lastUSB   atomic.Int64
	phoneName atomic.Value
	hostName  string
	seq       atomic.Int64
	bench     atomic.Int64
	bufs      sync.Pool
}

func main() {
	home, _ := os.UserHomeDir()
	s := &server{
		inbox:    map[string]*transfer{},
		outbox:   map[string]*outItem{},
		recvDir:  filepath.Join(home, "Downloads", "FastDrop"),
		outDir:   filepath.Join(os.TempDir(), "FastDrop-outbox"),
		bufs:     sync.Pool{New: func() any { b := make([]byte, bufSize); return &b }},
		hostName: "PC",
	}
	if h, err := os.Hostname(); err == nil {
		s.hostName = h
	}
	s.phoneName.Store("")
	os.RemoveAll(s.outDir)
	os.MkdirAll(s.outDir, 0o755)
	os.MkdirAll(s.recvDir, 0o755)

	ln, err := net.Listen("tcp4", fmt.Sprintf("0.0.0.0:%d", httpPort))
	if err != nil {
		// already running: just show the UI
		openUI()
		return
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		b, _ := assets.ReadFile("index.html")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(b)
	})
	mux.HandleFunc("/api/state", s.state)
	mux.HandleFunc("/api/outbox", s.addOutbox)
	mux.HandleFunc("/api/clear", s.clear)
	mux.HandleFunc("/api/open", s.openFolder)
	mux.HandleFunc("/api/qr", s.qr)
	mux.HandleFunc("/api/reveal", s.reveal)
	mux.HandleFunc("/api/quit", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("bye"))
		go func() { time.Sleep(200 * time.Millisecond); os.RemoveAll(s.outDir); os.Exit(0) }()
	})
	mux.HandleFunc("/bench/up", s.benchUp)
	mux.HandleFunc("/bench/down", s.benchDown)
	mux.HandleFunc("/up", s.up)
	mux.HandleFunc("/file/", s.file)

	go s.beacon()
	go usbLoop()
	go openUI()
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	srv.Serve(ln)
}

func openUI() {
	u := fmt.Sprintf("http://localhost:%d/", httpPort)
	if runtime.GOOS != "windows" {
		exec.Command("xdg-open", u).Start()
		return
	}
	for _, p := range []string{
		os.Getenv("ProgramFiles(x86)") + `\Microsoft\Edge\Application\msedge.exe`,
		os.Getenv("ProgramFiles") + `\Microsoft\Edge\Application\msedge.exe`,
		os.Getenv("ProgramFiles") + `\Google\Chrome\Application\chrome.exe`,
		os.Getenv("ProgramFiles(x86)") + `\Google\Chrome\Application\chrome.exe`,
		os.Getenv("LocalAppData") + `\Google\Chrome\Application\chrome.exe`,
	} {
		if _, err := os.Stat(p); err == nil {
			exec.Command(p, "--app="+u, "--window-size=980,720").Start()
			return
		}
	}
	exec.Command("rundll32", "url.dll,FileProtocolHandler", u).Start()
}

// virtualIface skips docker/VM/bridge adapters: a phone can never reach the PC through them.
func virtualIface(name string) bool {
	n := strings.ToLower(name)
	for _, p := range []string{"docker", "br-", "veth", "virbr", "vmnet", "vethernet", "virtualbox", "vboxnet", "wsl", "lxc"} {
		if strings.HasPrefix(n, p) || strings.Contains(n, p) && p != "br-" && p != "veth" {
			return true
		}
	}
	return false
}

func localIPs() []string {
	var out []string
	ifs, _ := net.Interfaces()
	for _, i := range ifs {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 || virtualIface(i.Name) {
			continue
		}
		as, _ := i.Addrs()
		for _, a := range as {
			if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil && !n.IP.IsLinkLocalUnicast() {
				out = append(out, n.IP.String())
			}
		}
	}
	return out
}

// beacon broadcasts our presence so the phone finds us instantly, and answers probes.
func (s *server) beacon() {
	c, err := net.ListenUDP("udp4", &net.UDPAddr{Port: beaconPort})
	if err != nil {
		return
	}
	msg := []byte(fmt.Sprintf("FASTDROP1|%s|%d", s.hostName, httpPort))
	go func() {
		buf := make([]byte, 256)
		for {
			n, from, err := c.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if strings.HasPrefix(string(buf[:n]), "FASTDROP?") {
				c.WriteToUDP(msg, from)
			}
		}
	}()
	for {
		ifs, _ := net.Interfaces()
		for _, i := range ifs {
			if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagBroadcast == 0 {
				continue
			}
			as, _ := i.Addrs()
			for _, a := range as {
				n, ok := a.(*net.IPNet)
				if !ok || n.IP.To4() == nil {
					continue
				}
				ip, mask := n.IP.To4(), net.IP(n.Mask).To4()
				if mask == nil {
					continue
				}
				bc := make(net.IP, 4)
				for k := range bc {
					bc[k] = ip[k] | ^mask[k]
				}
				c.WriteToUDP(msg, &net.UDPAddr{IP: bc, Port: beaconPort})
			}
		}
		time.Sleep(time.Second)
	}
}

func clean(name string) string {
	name = filepath.Base(strings.ReplaceAll(name, "\\", "/"))
	name = strings.Map(func(r rune) rune {
		if strings.ContainsRune(`<>:"/\|?*`, r) || r < 32 {
			return '_'
		}
		return r
	}, name)
	name = strings.TrimRight(name, ". ")
	if name == "" || name == "." {
		name = "file"
	}
	return name
}

func uniquePath(dir, name string) string {
	p := filepath.Join(dir, name)
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	for i := 1; ; i++ {
		if _, err := os.Stat(p); os.IsNotExist(err) {
			return p
		}
		p = filepath.Join(dir, fmt.Sprintf("%s (%d)%s", base, i, ext))
	}
}

func (s *server) touchPhone(r *http.Request) {
	now := time.Now().UnixMilli()
	s.lastPhone.Store(now)
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
			s.lastUSB.Store(now) // arrives through the adb reverse tunnel = USB cable
		} else {
			s.lastWifi.Store(now)
		}
	}
	if d := r.URL.Query().Get("dev"); d != "" {
		s.phoneName.Store(d)
	}
}

func (s *server) state(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("dev") != "" {
		s.touchPhone(r)
	}
	s.mu.Lock()
	in := make([]*transfer, 0, len(s.inOrder))
	for i := len(s.inOrder) - 1; i >= 0 && len(in) < 60; i-- {
		t := s.inbox[s.inOrder[i]]
		c := *t
		c.Got = atomic.LoadInt64(&t.Got)
		in = append(in, &c)
	}
	out := make([]*outItem, 0, len(s.outOrder))
	for _, id := range s.outOrder {
		o := *s.outbox[id]
		o.Sent = atomic.LoadInt64(&s.outbox[id].Sent)
		out = append(out, &o)
	}
	s.mu.Unlock()
	phone := time.Now().UnixMilli()-s.lastPhone.Load() < 4000
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(map[string]any{
		"name": s.hostName, "ips": localIPs(), "port": httpPort, "recvDir": s.recvDir,
		"phone": phone, "phoneName": s.phoneName.Load(), "inbox": in, "outbox": out,
		"now": time.Now().UnixMilli(), "bench": s.bench.Load(),
		"wifi": time.Now().UnixMilli()-s.lastWifi.Load() < 4500, "usb": time.Now().UnixMilli()-s.lastUSB.Load() < 6500,
		"usbState": usbState.Load(),
	})
}

// up receives one byte-range chunk of a file; many chunks stream in parallel.
func (s *server) up(w http.ResponseWriter, r *http.Request) {
	s.touchPhone(r)
	q := r.URL.Query()
	id := q.Get("id")
	size, _ := strconv.ParseInt(q.Get("size"), 10, 64)
	off, _ := strconv.ParseInt(q.Get("off"), 10, 64)
	if id == "" || size < 0 {
		http.Error(w, "bad request", 400)
		return
	}
	s.mu.Lock()
	t := s.inbox[id]
	if t == nil {
		name := clean(q.Get("name"))
		p := uniquePath(s.recvDir, name)
		f, err := os.OpenFile(p, os.O_CREATE|os.O_RDWR, 0o644)
		if err != nil {
			s.mu.Unlock()
			http.Error(w, err.Error(), 500)
			return
		}
		if size > 0 {
			f.Truncate(size)
		}
		t = &transfer{ID: id, Name: filepath.Base(p), Size: size, Start: time.Now().UnixMilli(), Path: p, f: f}
		s.inbox[id] = t
		s.inOrder = append(s.inOrder, id)
	}
	s.mu.Unlock()

	bp := s.bufs.Get().(*[]byte)
	defer s.bufs.Put(bp)
	buf := *bp
	pos := off
	for {
		n, err := io.ReadFull(r.Body, buf)
		if n > 0 {
			if _, werr := t.f.WriteAt(buf[:n], pos); werr != nil {
				http.Error(w, werr.Error(), 500)
				return
			}
			pos += int64(n)
			atomic.AddInt64(&t.Got, int64(n))
		}
		if err != nil {
			break
		}
	}
	if atomic.LoadInt64(&t.Got) >= t.Size {
		s.mu.Lock()
		if t.End == 0 {
			t.End = time.Now().UnixMilli()
			t.f.Close()
		}
		s.mu.Unlock()
	}
	w.Header().Set("Content-Length", "2")
	w.Write([]byte("ok"))
}

type countingWriter struct {
	http.ResponseWriter
	n *int64
}

func (c countingWriter) Write(p []byte) (int, error) {
	n, err := c.ResponseWriter.Write(p)
	atomic.AddInt64(c.n, int64(n))
	return n, err
}

func (c countingWriter) ReadFrom(r io.Reader) (int64, error) {
	if rf, ok := c.ResponseWriter.(io.ReaderFrom); ok {
		n, err := rf.ReadFrom(r)
		atomic.AddInt64(c.n, n)
		return n, err
	}
	n, err := io.Copy(struct{ io.Writer }{c.ResponseWriter}, r)
	atomic.AddInt64(c.n, n)
	return n, err
}

func (s *server) file(w http.ResponseWriter, r *http.Request) {
	s.touchPhone(r)
	id := strings.TrimPrefix(r.URL.Path, "/file/")
	s.mu.Lock()
	o := s.outbox[id]
	s.mu.Unlock()
	if o == nil {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(o.Path)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+url.PathEscape(o.Name))
	http.ServeContent(countingWriter{w, &o.Sent}, r, o.Name, time.Time{}, f)
}

// addOutbox stores a file dropped in the PC window so the phone can pull it.
func (s *server) addOutbox(w http.ResponseWriter, r *http.Request) {
	name := clean(r.URL.Query().Get("name"))
	id := fmt.Sprintf("o%d-%d", time.Now().UnixNano(), s.seq.Add(1))
	dir := filepath.Join(s.outDir, id)
	os.MkdirAll(dir, 0o755)
	p := filepath.Join(dir, name)
	f, err := os.Create(p)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	bp := s.bufs.Get().(*[]byte)
	n, err := io.CopyBuffer(f, r.Body, *bp)
	s.bufs.Put(bp)
	f.Close()
	if err != nil {
		os.RemoveAll(dir)
		http.Error(w, err.Error(), 500)
		return
	}
	s.mu.Lock()
	s.outbox[id] = &outItem{ID: id, Name: name, Size: n, Path: p}
	s.outOrder = append(s.outOrder, id)
	s.mu.Unlock()
	w.Write([]byte(id))
}

func (s *server) clear(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	switch r.URL.Query().Get("kind") {
	case "outbox":
		if id := r.URL.Query().Get("id"); id != "" {
			delete(s.outbox, id)
			keep := s.outOrder[:0]
			for _, x := range s.outOrder {
				if x != id {
					keep = append(keep, x)
				}
			}
			s.outOrder = keep
			os.RemoveAll(filepath.Join(s.outDir, id))
		} else {
			s.outbox = map[string]*outItem{}
			s.outOrder = nil
			os.RemoveAll(s.outDir)
			os.MkdirAll(s.outDir, 0o755)
		}
	case "inbox":
		var ids []string
		for id, t := range s.inbox {
			if t.End != 0 {
				ids = append(ids, id)
			}
		}
		sort.Strings(ids)
		for _, id := range ids {
			delete(s.inbox, id)
		}
		keep := s.inOrder[:0]
		for _, x := range s.inOrder {
			if _, ok := s.inbox[x]; ok {
				keep = append(keep, x)
			}
		}
		s.inOrder = keep
	}
	w.Write([]byte("ok"))
}

func (s *server) openFolder(w http.ResponseWriter, r *http.Request) {
	if runtime.GOOS == "windows" {
		exec.Command("explorer", s.recvDir).Start()
	} else {
		exec.Command("xdg-open", s.recvDir).Start()
	}
	w.Write([]byte("ok"))
}

// benchUp swallows whatever the phone sends, to measure phone -> PC throughput.
func (s *server) benchUp(w http.ResponseWriter, r *http.Request) {
	s.touchPhone(r)
	bp := s.bufs.Get().(*[]byte)
	defer s.bufs.Put(bp)
	for {
		n, err := r.Body.Read(*bp)
		s.bench.Add(int64(n))
		if err != nil {
			return
		}
	}
}

// benchDown streams data until the phone hangs up, to measure PC -> phone throughput.
func (s *server) benchDown(w http.ResponseWriter, r *http.Request) {
	s.touchPhone(r)
	buf := make([]byte, bufSize)
	for i := range buf {
		buf[i] = byte(i*131 + i>>8)
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", "1099511627776")
	for {
		n, err := w.Write(buf)
		s.bench.Add(int64(n))
		if err != nil {
			return
		}
	}
}

// qr renders the connection QR: every reachable PC address in one code, so the phone can
// scan once and pick whichever one answers (Wi-Fi LAN, Tailscale, hotspot...).
func (s *server) qr(w http.ResponseWriter, r *http.Request) {
	v := url.Values{}
	v.Set("name", s.hostName)
	v.Set("port", strconv.Itoa(httpPort))
	for _, ip := range localIPs() {
		v.Add("ip", ip)
	}
	png, err := qrcode.Encode("fastdrop://connect?"+v.Encode(), qrcode.Medium, 520)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(png)
}

// reveal opens the received file's folder in the file manager with the file selected.
func (s *server) reveal(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	t := s.inbox[r.URL.Query().Get("id")]
	s.mu.Unlock()
	if t == nil {
		http.NotFound(w, r)
		return
	}
	revealFile(t.Path)
	w.Write([]byte("ok"))
}
