# QuickDrop (Option A: Windows .exe + Android app)

Send photos, videos, documents and any other files between an Android phone and a Windows PC over your home Wi-Fi. No cloud, no cable.

- `pc/quickdrop.py`  Windows program (Python, packaged into `QuickDrop.exe` by GitHub Actions). Also runs on Mac/Linux/Termux.
- `app/`             Android app (Kotlin + Jetpack Compose)
- GitHub Actions builds both. Download `QuickDrop.exe` and `app-debug.apk` from Releases.

Both use the same simple protocol (TCP 5005 for files, UDP 5006 for finding devices, optional PIN).

## Use
1. Run `QuickDrop.exe` on the PC, choose 1. Allow it through Windows Firewall on Private networks.
2. Open the phone app. Both devices must be on the same Wi-Fi.
3. Phone: tap Find devices, pick your PC, Choose files to send. If it can't find the PC, type the PC's IP address and tap Test connection.
4. PC: paste or drag file paths into the window to send to the phone.
5. No luck connecting? Run `QuickDrop.exe`, choose 4 (Browser mode) and open the address it shows in the phone's browser.

---
© 2026 Dominic Sykes. All rights reserved. See LICENSE.
