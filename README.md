# 🔒 PC Connect & Authenticator

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Linux%20%7C%20Android-green.svg)]()
[![Security](https://img.shields.io/badge/Security-HMAC--SHA256%20%2B%20E2E-red.svg)]()
[![Desktop](https://img.shields.io/badge/KDE%20Plasma%206-Wayland%20Native-blue.svg)]()

**PC Connect** is a complete, private, end-to-end encrypted **replacement for KDE Connect** and a **biometric lock-screen authenticator** for Linux (KDE Plasma 6 on Wayland / X11).

It combines bi-directional file sharing, Dolphin context menus, remote phone storage browsing, shared clipboard sync, media playback controls (MPRIS), Find My Phone, and remote PC lock with **passwordless phone fingerprint screen unlock** and **hardware USB cable detection**.

**Zero cloud dependencies. No third-party servers. No telemetry. 100% offline & local.**

---

## ✨ Features

### 📁 1. Seamless File Sharing (Bi-Directional)
- **PC to Phone:**
  - **Dolphin Right-Click Integration:** Right-click any file or folder in Dolphin and select **"Send to Phone (PC Connect)"**.
  - **CLI Transfer:** Run `pc-connect send <files...>` or `pc-connect-send <files...>`.
  - Native desktop notifications with transfer progress and completion alerts.
- **Phone to PC:**
  - **System Share Sheet Integration:** Tap "Share" on any photo, video, PDF, or document in any Android app (Google Photos, Gallery, Files, Chrome, WhatsApp) and select **"PC Connect"** $\rightarrow$ streams directly to `~/Downloads/`.
  - Interactive KDE notification on PC: click **"Open File"** or **"Open Downloads Folder"**.
  - Audible chime plays automatically on PC upon receiving files.

### 📱 2. Browse Phone Files Remotely
- **Native PyQt6 Phone Explorer:**
  - Launch from system tray or run `pc-connect browse`.
  - Quick bookmarks: 📱 Internal Storage, 📷 Photos/DCIM, 📥 Downloads, 📄 Documents, 🎵 Music, 🎬 Movies.
  - Download phone files to PC Downloads with one click or double-click.
  - Drag-and-drop upload from PC desktop straight into phone folders.
  - Create new folders and delete files directly on phone.
- **Modern Web File Explorer:**
  - Access in any web browser at `http://<laptop-ip>:1760/browse` with responsive dark-mode UI.

### 📋 3. Shared Clipboard Synchronization
- Copy text on Linux $\rightarrow$ tap **"Paste from PC"** on Android to receive.
- Copy text on Android $\rightarrow$ tap **"Copy to PC"** or share text to update the native KDE Plasma 6 clipboard (`org.kde.klipper`) instantly.
- One-click clipboard sync from PC tray or terminal: `pc-connect clip`.

### 🎵 4. Remote Media Player Control (MPRIS)
- Control desktop media playback directly from your phone app:
  - Real-time track title, artist, and playback status for Spotify, VLC, Firefox, Zen Browser, Elisa, Chrome, etc.
  - Controls: ⏯️ Play/Pause, ⏮️ Previous, ⏭️ Next, 🔉 Volume Down, 🔊 Volume Up.
  - CLI control: `pc-connect media play|pause|next|prev|volup|voldown`.

### 🔔 5. Find My Phone & Ping PC
- **Find My Phone:** Click **"Find My Phone"** in the PC tray or run `pc-connect ring` $\rightarrow$ phone rings at maximum alarm volume and vibrates with an on-screen "Dismiss Alarm" button.
- **Ping PC:** Tap **"Ring Laptop"** on your phone $\rightarrow$ PC plays loud incoming call chime and displays an urgent desktop alert.

### 🔒 6. Passwordless Mobile Unlock & Biometrics
- **Passwordless Mobile Unlock:** Leave the password field blank and hit `Enter` on your lock screen $\rightarrow$ Phone lights up and scans fingerprint $\rightarrow$ **Laptop unlocks instantly!**
- **Strict 2FA Mode:** Type your Linux password first, followed by phone biometric approval.
- **Hardware USB Token Mode:** Plug your phone in via USB cable to unlock instantly with zero wireless network needed.
- **Remote Lock:** Tap **"Lock PC"** on phone to immediately lock the session via `loginctl lock-session`.

### 🔋 7. Native KDE System Tray & Phone Status
- Sits in your KDE Plasma panel with live connection status.
- Real-time phone battery level and charging state (e.g. `OPPO CPH2505 • 85% ⚡`).
- Quick access to all sharing, browsing, ringing, and locking features.

---

## 🚀 Quick Start (Linux PC Setup)

### 1. Clone & Run the Installer
```bash
git clone https://github.com/lunar-photon/pc-authenticator.git
cd pc-authenticator
bash setup.sh
```

Running `setup.sh` launches the interactive manager:
```
==========================================================
       🔒  PC Authenticator - Laptop Setup & Manager      
==========================================================

Please select an action:
  1) Install Passwordless Mobile Unlock (Recommended)
     Hit Enter on lock screen -> Scan fingerprint on phone -> Unlocks PC
     (Password fallback is automatically enabled if phone is away)

  2) Install Strict 2FA Mode
     Type Linux password first -> Then approve via phone fingerprint

  3) Revert to Standard Password-Only
     Removes mobile authentication hook from lock screen

  4) Enroll USB Phone Token (Plug phone in to unlock instantly via cable)

  5) View System Status & Paired Devices

  6) Exit
```

*Or use direct CLI flags:*
- **Passwordless Mode:** `pc-auth --install`
- **Strict 2FA Mode:** `pc-auth --2fa`
- **Revert to Password-Only:** `pc-auth --revert`
- **Status & Diagnostics:** `pc-connect status`

---

## 📱 Android App Setup

### 1. Install the APK
- Download the prebuilt release: [**`PCAuthenticator.apk`**](PCAuthenticator.apk) (only ~399 KB!)
- Or download it directly over your local network in your phone browser:
  ```
  http://<laptop-ip>:1760/apk
  ```

### 2. Pair Your Phone (One-Time Setup)
1. Open the **PC Authenticator** app on your phone.
2. Tap **"Auto-Scan Wi-Fi"** (or add your PC IP manually).
3. Tap **"Pair 🔑"** next to your discovered computer.
4. A **6-digit PIN** will pop up on your laptop screen as a desktop notification.
5. Enter the PIN in your phone app and tap **"Pair & Authorize"**.
6. Your phone is now cryptographically paired with 256-bit mutual encryption!

---

## 💻 CLI Commands Reference

`pc-connect` is available globally in your terminal:

| Command | Description |
| :--- | :--- |
| `pc-connect status` | Check phone connection state, IP, and battery percentage |
| `pc-connect browse` | Open the native PyQt6 Phone File Explorer window |
| `pc-connect send <files...>` | Send one or more files to your phone |
| `pc-connect clip` | Push current PC clipboard to phone |
| `pc-connect clip "hello"` | Send custom text to phone clipboard |
| `pc-connect ring` | Ring phone loudly at max alarm volume (Find My Phone) |
| `pc-connect ping` | Play alert sound and notification on PC |
| `pc-connect media` | Show currently playing media track and artist |
| `pc-connect media [command]` | Control media: `play`, `pause`, `toggle`, `next`, `prev`, `volup`, `voldown` |
| `pc-connect lock` | Lock PC screen immediately |
| `pc-connect tray` | Launch the KDE Plasma system tray application |

---

## 🔌 Hardware USB Token Mode

Want your laptop to unlock instantly whenever your phone is plugged in via USB cable, even with Wi-Fi turned off?

1. Plug your phone into your laptop with a USB cable.
2. Run:
   ```bash
   pc-auth --enroll-usb
   ```
3. Select your phone from the detected device list.
4. Done! Whenever your phone is plugged in, your lock screen will unlock immediately upon pressing `Enter`.

---

## 🛡️ Security Architecture

| Security Layer | Implementation |
| :--- | :--- |
| **Mutual Pairing** | Out-of-band 6-digit random PIN ceremony with 60-second expiry and max 3 attempts |
| **Bearer Tokens** | High-entropy 256-bit cryptographic tokens generated via `secrets.token_hex(32)` |
| **Request Signing** | Every unlock request requires HMAC-SHA256 signature generated with shared secret key |
| **Replay Defense** | Nonces + 45-second timestamp freshness window strictly enforced |
| **Biometric Auth** | Native Android `BiometricPrompt` supporting Fingerprint, Face, and Device Keyguard |
| **Local-Only** | Binds only to local interfaces (Wi-Fi, hotspot, USB reverse tethering). Zero external cloud requests |

---

## 🛠️ Building from Source

### Android APK (Fast, Standalone Toolchain)
```bash
cd android
./build-apk.sh
```
Builds and signs `PCAuthenticator.apk` in ~4 seconds without needing heavy Gradle dependencies.

---

## 📄 License

MIT License. Free and open source for personal and enterprise use.
