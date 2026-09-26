# 🔒 PC Authenticator

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Linux%20%7C%20Android-green.svg)]()
[![Security](https://img.shields.io/badge/Security-HMAC--SHA256%20%2B%20E2E-red.svg)]()

**PC Authenticator** is a fast, completely offline, zero-cloud authentication system for Linux desktops. It allows you to unlock your computer or authorize lock-screen logins using your **Android phone's biometric fingerprint** or a **plugged-in USB cable**, with end-to-end cryptographic mutual authentication.

No cloud brokers. No third-party servers. No KDE Connect dependency. Works seamlessly over local Wi-Fi, Mobile Hotspot, and USB.

---

## ✨ Features

- 👆 **Passwordless Mobile Unlock (Biometric):** Leave the password field blank and press `Enter` on your lock screen $\rightarrow$ Scan your fingerprint on your phone $\rightarrow$ **Laptop unlocks instantly!**
- 🛡️ **Strict 2FA Mode:** Type your standard Linux password first, followed by phone biometric confirmation.
- 🔌 **Hardware USB Token Mode:** Plug your phone in via USB cable to unlock instantly with zero wireless network needed.
- 🔒 **End-to-End Cryptographic Security:** 
  - Every paired client receives a unique 256-bit Bearer Token and a shared 256-bit HMAC secret key during a one-time 6-digit PIN handshake.
  - Approvals require dynamic HMAC-SHA256 signatures and timestamp verification. Unauthorized devices on the same Wi-Fi cannot approve requests.
- 🔄 **Smart Auto-Discovery (Wi-Fi & Hotspot):**
  - Uses immutable hardware Machine IDs (`/etc/machine-id`).
  - Automatically tracks IP changes when switching between Wi-Fi and Mobile Hotspots without ever needing to re-pair.
- 📱 **Multi-PC Support:** Manage and switch between multiple laptops from a single phone app.
- ⏪ **1-Click Safe Revert:** Revert back to standard password-only at any time with a single command.

---

## 🚀 Quick Start (Laptop Setup)

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
- **Status & Diagnostics:** `pc-auth --status`

---

## 📱 Android App Setup

### 1. Install the APK
- Download the prebuilt release: [**`PCAuthenticator.apk`**](PCAuthenticator.apk)
- Or download it directly over your local network in your phone browser:
  ```
  http://<laptop-ip>:1760/apk
  ```

### 2. Pair Your Phone (One-Time Setup)
1. Open the **PC Authenticator** app on your phone.
2. Tap **"Autoscan Network"** (or add your PC manually).
3. Tap **"Pair 🔑"** next to your discovered computer.
4. A **6-digit PIN** will pop up on your laptop screen (desktop notification).
5. Enter the PIN in your phone app and tap **"Pair & Authorize"**.
6. Your phone is now cryptographically paired with 256-bit mutual encryption!

---

## 🔌 USB Hardware Token Mode

Want your laptop to unlock instantly whenever your phone is plugged in via USB cable?

1. Plug your phone into your laptop with a USB cable.
2. Run:
   ```bash
   pc-auth --enroll-usb
   ```
3. Select your phone from the detected device list.
4. Done! Whenever your phone is plugged in, your lock screen will unlock immediately upon pressing `Enter`.

---

## 🔐 How It Works (Architecture)

```
┌─────────────────────┐                                ┌────────────────────────┐
│    Android Phone    │                                │     Linux Laptop       │
└──────────┬──────────┘                                └───────────┬────────────┘
           │                                                       │
           │ 1. One-Time 6-Digit PIN Handshake                     │
           ├──────────────────────────────────────────────────────►│ Generates 256-bit token & secret
           │◄──────────────────────────────────────────────────────┤
           │                                                       │
    [SCREEN LOCKED]                                                │ User hits Enter / wakes screen
           │                                                       │ PAM runs lockscreen-auth-check
           │ 2. Real-Time Push Notification                        │
           │◄──────────────────────────────────────────────────────┤ Challenges daemon (/api/request_auth)
           │                                                       │
  [Biometric Prompt]                                               │
  Touch Fingerprint                                                │
           │                                                       │
           │ 3. Signed Approval Request                            │
           │    - Authorization: Bearer <auth_token>               │
           │    - X-Auth-Timestamp: <timestamp>                    │
           │    - X-Auth-Signature: HMAC-SHA256(secret_key,...)    │
           ├──────────────────────────────────────────────────────►│ Verifies token, clock skew, & HMAC
           │                                                       │ PAM exits 0 -> Screen Unlocks!
```

---

## 🛠️ Repository Structure

```
pc-authenticator/
├── PCAuthenticator.apk          # Pre-built signed Android APK (ready to install)
├── setup.sh                     # Master setup, installer, and revert tool
├── linux/                       # Linux daemon & PAM hooks
│   ├── daemon.py                # Core background server (HMAC auth, beacons, PINs)
│   ├── lockscreen-auth-check    # PAM check script (Passwordless, 2FA, USB)
│   ├── enroll-usb.py            # USB device enrollment utility
│   ├── kde-pam-passwordless     # PAM profile for passwordless biometric unlock
│   ├── kde-pam-2fa              # PAM profile for strict 2FA
│   ├── setup.sh                 # Linux installer script
│   └── static/                  # Offline web dashboard & APK distribution
└── android/                     # Full native Android app source
    ├── AndroidManifest.xml
    ├── build-apk.sh             # Self-contained build script (AAPT2 + D8 + Uber-Signer)
    ├── src/                     # Java source code (MainActivity, AuthService, CryptoUtils)
    └── res/                     # Vector layouts, dark theme styles, adaptive icons
```

---

## 🔨 Building the Android App from Source

The repository includes a standalone build script that compiles, dexes, zipaligns, and signs the APK without needing heavyweight Android Studio:

```bash
cd android
bash build-apk.sh
```

The output will be placed in `dist/` and `../PCAuthenticator.apk`.

---

## ❓ Frequently Asked Questions

#### What happens if my Wi-Fi changes or I switch to mobile hotspot?
No re-pairing is needed. The app matches your laptop using its persistent hardware machine ID (`device_id`). When you switch networks, simply tap **"Autoscan Network"** (or let the background UDP beacon detect it), and it will automatically switch to the new IP within a second.

#### What if my phone battery dies?
In Passwordless Mode, you are never locked out: simply type your standard Linux password and press Enter to unlock normally.

#### How do I completely uninstall / revert?
Run `pc-auth --revert` (or `sudo bash ~/.config/pc-authenticator/revert-to-password.sh`). It cleanly removes the PAM hook and restores standard password authentication.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
