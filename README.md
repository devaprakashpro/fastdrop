# FastDrop

Quick file sharing between a **Windows PC** and an **Android phone** over the same Wi-Fi,
optionally **plus a USB cable at the same time**. No cloud, no account, no encryption overhead:
files move at the speed of your link.

| | |
|---|---|
| PC app | `FastDrop.exe` — one portable file (Go), opens its own window (Edge/Chrome app mode) |
| Phone app | `FastDrop.apk` — native Android (Kotlin), Android 10+ |
| Measured here | 66–72 MB/s over Wi-Fi, ~39–47 MB/s over a USB 2 cable, **108–115 MB/s with both** (1 GB files) |

> Security is deliberately not a goal: traffic is plain HTTP and any device on the same network can
> send to or pull from the PC while the app is open. Use it on networks you trust and close it otherwise.

---

## 1. Install

### Phone
1. Copy `FastDrop.apk` to the phone (USB, Drive, `adb install -r FastDrop.apk`).
2. Allow "install unknown apps" if asked, open **FastDrop**, allow notifications.

### Windows PC
1. Unzip `FastDrop-Windows-Package.zip`.
2. Right-click **`Install-FastDrop.bat` → Run as administrator** (once). It copies the app to
   `%LOCALAPPDATA%\FastDrop`, opens the Windows Firewall for it (TCP 8765, UDP 8766) and adds a
   desktop shortcut.
3. If SmartScreen warns (the exe is unsigned): **More info → Run anyway**.

---

## 2. Connect the phone

Pick any one — they all end in the same connection:

* **Scan QR (recommended).** PC window → **📱 Scan to connect** (it also pops up by itself when no
  phone is connected) → phone → **Scan QR to connect** → point at the code. The QR carries *every*
  address of the PC (Wi-Fi LAN, Tailscale, …); the phone tries them all and keeps the fastest one.
* **Automatic.** With both apps open on the same Wi-Fi the phone finds the PC by itself (UDP broadcast).
* **Type the address.** Enter `192.168.x.x` (shown at the bottom of the PC window) in the phone and tap **Connect**.

The phone's top card shows **Wi-Fi connected / Cable active**; the PC window shows the same as chips.

## 3. Wi-Fi + USB cable together (optional, ~+60 % speed)

1. Phone: Settings → About → tap *Build number* 7× → Developer options → **USB debugging ON**.
2. PC: install Android *platform-tools* (`adb`) and put `adb.exe` on `PATH` (or next to `FastDrop.exe`).
3. Plug the phone in, tap **Allow** on the phone.

FastDrop sets up the tunnel by itself. Choose the mode on the phone:
**Wi-Fi + Cable** (default, fastest) · **Wi-Fi only** · **Cable only**.
A USB 3 cable in a USB 3 port is much faster than a charging (USB 2) cable.

## 4. Use it

| Goal | How |
|---|---|
| PC → phone | Drag one or many files into the PC window (or click the drop zone). The phone downloads them automatically (switch *Auto-receive* on the phone). |
| Phone → PC | Phone: **Send files to PC**, or **Share → FastDrop** from any app (gallery, files, …). |
| See progress | Every file has a 0–100 % animated bar with MB/s and time left; an overall bar covers the whole batch; a live speed gauge sits on top of both apps. |
| Open received files | **PC:** 📁 on a received file selects it in Explorer; *Open folder* opens the folder. **Phone:** 📁 on a received file or *Open received files in file manager* opens `Download/FastDrop` in the Files app. |
| Find the best speed | Phone → **Speed test**: measures 1/2/4/8 parallel streams both ways and keeps the fastest. |

Received files go to **PC** `Downloads\FastDrop` and **phone** `Download/FastDrop`.

---

## 5. Troubleshooting

| Symptom | Fix |
|---|---|
| Phone says "Looking for PC…" | Open FastDrop on the PC; same Wi-Fi; allow the Windows Firewall prompt; router "AP / client isolation" must be off; or scan the QR / type the address. |
| "Can't reach the PC" on a send | PC app closed or on another network. Start it and send again. |
| Cable chip says "install adb" / "plug in phone + USB debugging" | Follow section 3. The Wi-Fi link keeps working without it. |
| Slow (≈15–25 MB/s) | Phone far from router, 2.4 GHz band, or phone Wi-Fi power-save. Move closer, use 5 GHz, keep the screen on, run **Speed test**. |
| Speed drops with many streams | Normal: more than ~4 streams per link causes loss on Wi-Fi. Speed test chooses for you. |
| QR scanner "unavailable" | Needs Google Play services (the scanner module downloads once, needs internet that first time). Use the typed address instead. |

---

## 6. What is in this folder

```
fastdrop/
├── README.md                     this file (user guide)
├── docs/DEVELOPER.md             architecture, protocol, build, tests, research notes
├── windows/                      PC app (Go)  → FastDrop.exe
├── android/                      phone app (Kotlin) → FastDrop.apk
├── dist/                         ready-to-install: FastDrop.exe, FastDrop.apk, Install-FastDrop.bat, README.txt
└── FastDrop-Windows-Package.zip  dist/ bundle to copy to the Windows laptop
```

Developer details (protocol, how it is fast, build commands, test results, decisions) are in
[`docs/DEVELOPER.md`](docs/DEVELOPER.md).
