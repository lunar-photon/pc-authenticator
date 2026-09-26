#!/usr/bin/env bash
# ==============================================================================
# PC Authenticator - Master Setup & Revert Tool
# Handles installation, PAM configuration, service management, and uninstallation.
# ==============================================================================

set -eo pipefail

SCRIPT_DIR="$(dirname "$(realpath "${BASH_SOURCE[0]}")")"

# Colors for terminal output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

require_root() {
    if [ "$EUID" -ne 0 ]; then
        REAL_USER="$USER"
        export REAL_USER
        exec sudo -E bash "$0" "$@"
    fi
}

TARGET_USER="${REAL_USER:-${SUDO_USER:-$USER}}"
if [ "$TARGET_USER" == "root" ]; then
    # Fallback to first non-root UID 1000 user if available
    TARGET_USER="$(id -un 1000 2>/dev/null || echo "root")"
fi
TARGET_HOME=$(eval echo "~$TARGET_USER")

print_banner() {
    echo -e "${CYAN}${BOLD}"
    echo "=========================================================="
    echo "       🔒  PC Authenticator - Laptop Setup & Manager      "
    echo "=========================================================="
    echo -e "${NC}"
}

ensure_service() {
    echo -e "${CYAN}[*] Setting up daemon background service...${NC}"
    if [ -n "$TARGET_USER" ] && [ "$TARGET_USER" != "root" ]; then
        TARGET_UID=$(id -u "$TARGET_USER")
        SERVICE_DIR="$TARGET_HOME/.config/systemd/user"
        mkdir -p "$SERVICE_DIR"
        
        # Write dynamic systemd unit file for this machine & user
        cat <<EOF > "$SERVICE_DIR/pc-authenticator.service"
[Unit]
Description=PC Authenticator Daemon (Mobile 2FA & USB Token)
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 $SCRIPT_DIR/daemon.py
Restart=always
RestartSec=3

[Install]
WantedBy=default.target
EOF
        chown -R "$TARGET_USER:$TARGET_USER" "$SERVICE_DIR"
        
        # Enable & start user service
        sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user daemon-reload >/dev/null 2>&1 || true
        sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user enable pc-authenticator.service >/dev/null 2>&1 || true
        sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user restart pc-authenticator.service >/dev/null 2>&1 || true
        
        if sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user is-active --quiet pc-authenticator.service; then
            echo -e "${GREEN}[✓] Background service (pc-authenticator.service) is active.${NC}"
        else
            echo -e "${YELLOW}[!] User service enabled. (Will start on next login if not active in current session).${NC}"
        fi
        
        # Also install global command symlinks ~/.local/bin/pc-auth, pc-connect, pc-connect-send, pc-connect-tray
        mkdir -p "$TARGET_HOME/.local/bin"
        ln -sf "$SCRIPT_DIR/setup.sh" "$TARGET_HOME/.local/bin/pc-auth"
        ln -sf "$SCRIPT_DIR/pc-connect" "$TARGET_HOME/.local/bin/pc-connect"
        ln -sf "$SCRIPT_DIR/pc-connect-send" "$TARGET_HOME/.local/bin/pc-connect-send"
        ln -sf "$SCRIPT_DIR/pc-connect-tray.py" "$TARGET_HOME/.local/bin/pc-connect-tray"
        chown -h "$TARGET_USER:$TARGET_USER" "$TARGET_HOME/.local/bin/pc-auth" "$TARGET_HOME/.local/bin/pc-connect" "$TARGET_HOME/.local/bin/pc-connect-send" "$TARGET_HOME/.local/bin/pc-connect-tray" 2>/dev/null || true

        # Install Dolphin Context Menu
        mkdir -p "$TARGET_HOME/.local/share/kio/servicemenus"
        if [ -f "$SCRIPT_DIR/pc_connect.desktop" ]; then
            cp -f "$SCRIPT_DIR/pc_connect.desktop" "$TARGET_HOME/.local/share/kio/servicemenus/pc_connect.desktop"
            chown "$TARGET_USER:$TARGET_USER" "$TARGET_HOME/.local/share/kio/servicemenus/pc_connect.desktop" 2>/dev/null || true
        fi

        # Install Autostart and Desktop Application Launcher
        mkdir -p "$TARGET_HOME/.config/autostart" "$TARGET_HOME/.local/share/applications"
        if [ -f "$SCRIPT_DIR/pc-connect-tray.desktop" ]; then
            cp -f "$SCRIPT_DIR/pc-connect-tray.desktop" "$TARGET_HOME/.config/autostart/pc-connect-tray.desktop"
            chown "$TARGET_USER:$TARGET_USER" "$TARGET_HOME/.config/autostart/pc-connect-tray.desktop" 2>/dev/null || true
        fi
        if [ -f "$SCRIPT_DIR/pc-connect.desktop" ]; then
            cp -f "$SCRIPT_DIR/pc-connect.desktop" "$TARGET_HOME/.local/share/applications/pc-connect.desktop"
            chown "$TARGET_USER:$TARGET_USER" "$TARGET_HOME/.local/share/applications/pc-connect.desktop" 2>/dev/null || true
            sudo -u "$TARGET_USER" update-desktop-database "$TARGET_HOME/.local/share/applications" 2>/dev/null || true
        fi
    fi
}

configure_firewall() {
    if command -v ufw >/dev/null 2>&1; then
        echo -e "${CYAN}[*] Configuring firewall rules (UFW)...${NC}"
        ufw allow 1760/tcp comment 'PC Authenticator Web/API' >/dev/null 2>&1 || true
        ufw allow 1760/udp comment 'PC Authenticator Discovery' >/dev/null 2>&1 || true
        ufw allow 1762/udp comment 'PC Authenticator Trackpad' >/dev/null 2>&1 || true
        echo -e "${GREEN}[✓] Firewall ports 1760 (TCP/UDP) & 1762 (UDP Trackpad) allowed.${NC}"
    elif command -v firewall-cmd >/dev/null 2>&1; then
        echo -e "${CYAN}[*] Configuring firewalld rules...${NC}"
        firewall-cmd --add-port=1760/tcp --permanent >/dev/null 2>&1 || true
        firewall-cmd --add-port=1760/udp --permanent >/dev/null 2>&1 || true
        firewall-cmd --add-port=1762/udp --permanent >/dev/null 2>&1 || true
        firewall-cmd --reload >/dev/null 2>&1 || true
        echo -e "${GREEN}[✓] Firewalld ports 1760 & 1762 allowed.${NC}"
    fi

    # Ensure uinput permissions for Remote Trackpad / Mouse pointer
    if [ "$EUID" -eq 0 ]; then
        modprobe uinput >/dev/null 2>&1 || true
        mkdir -p /etc/udev/rules.d /etc/modules-load.d
        echo "uinput" > /etc/modules-load.d/uinput.conf 2>/dev/null || true
        cat <<EOF > /etc/udev/rules.d/99-pc-connect-uinput.rules
KERNEL=="uinput", SUBSYSTEM=="misc", TAG+="uaccess", OPTIONS+="static_node=uinput"
EOF
        udevadm control --reload-rules >/dev/null 2>&1 || true
        udevadm trigger --sysname-match=uinput >/dev/null 2>&1 || true
    fi
}

install_core_files() {
    INSTALL_DIR="$TARGET_HOME/.config/pc-authenticator"
    mkdir -p "$INSTALL_DIR"
    if [ "$SCRIPT_DIR" != "$INSTALL_DIR" ]; then
        echo -e "${CYAN}[*] Copying PC Authenticator to $INSTALL_DIR...${NC}"
        cp -rn "$SCRIPT_DIR/"* "$INSTALL_DIR/" 2>/dev/null || true
        chown -R "$TARGET_USER:$TARGET_USER" "$INSTALL_DIR"
        SCRIPT_DIR="$INSTALL_DIR"
    fi

    echo -e "${CYAN}[*] Installing /usr/local/bin/lockscreen-auth-check...${NC}"
    cp "$SCRIPT_DIR/lockscreen-auth-check" /usr/local/bin/lockscreen-auth-check
    chmod 755 /usr/local/bin/lockscreen-auth-check

    # Create default config.json if not present
    if [ ! -f "$SCRIPT_DIR/config.json" ]; then
        cat <<'EOF' > "$SCRIPT_DIR/config.json"
{
  "web_port": 1760,
  "auth_timeout_seconds": 35,
  "failmode": "secure",
  "allowed_usb_serials": [],
  "paired_clients": {}
}
EOF
        if [ -n "$TARGET_USER" ] && [ "$TARGET_USER" != "root" ]; then
            chown "$TARGET_USER:$TARGET_USER" "$SCRIPT_DIR/config.json"
        fi
    fi

    echo -e "${CYAN}[*] Linking configuration to /etc/pc-auth...${NC}"
    mkdir -p /etc/pc-auth
    ln -sf "$SCRIPT_DIR/config.json" /etc/pc-auth/config.json
    chmod 644 "$SCRIPT_DIR/config.json" || true

    ensure_service
    configure_firewall
}

install_passwordless() {
    require_root --install
    print_banner
    echo -e "${BOLD}Installing Mode 1: Passwordless Mobile Unlock${NC}"
    echo -e "Press Enter on empty password field to unlock with Phone Fingerprint."
    echo -e "(Password fallback is always active if you type your Linux password)\n"

    install_core_files

    echo -e "${CYAN}[*] Backing up existing /etc/pam.d/kde...${NC}"
    if [ -f /etc/pam.d/kde ]; then
        cp /etc/pam.d/kde "/etc/pam.d/kde.bak.$(date +%s)"
    fi

    echo -e "${CYAN}[*] Writing Passwordless PAM configuration to /etc/pam.d/kde...${NC}"
    cat <<'EOF' > /etc/pam.d/kde
#%PAM-1.0
# PC Authenticator - Passwordless Mobile Unlock with Password Fallback
# 1. If Enter is pressed with empty password -> Phone Biometric Fingerprint approves
# 2. If password is typed -> Standard unix password verifies immediately
auth       [success=done default=ignore] pam_exec.so expose_authtok quiet /usr/local/bin/lockscreen-auth-check --passwordless
auth       include                     system-local-login
account    include                     system-local-login
password   include                     system-local-login
session    include                     system-local-login
EOF
    chmod 644 /etc/pam.d/kde

    echo -e "\n${GREEN}${BOLD}==========================================================${NC}"
    echo -e "${GREEN}${BOLD} [✓] Passwordless Mobile Unlock Successfully Installed!   ${NC}"
    echo -e "${GREEN}${BOLD}==========================================================${NC}"
    echo -e "\n👉 ${BOLD}How to test right now:${NC}"
    echo -e "   1. Lock your screen:  ${CYAN}loginctl lock-session${NC}"
    echo -e "   2. Hit ${BOLD}Enter${NC} on the lock screen (leave password blank)."
    echo -e "   3. Scan your fingerprint on your phone to unlock!"
    echo -e "   4. Or type your Linux password anytime to unlock via password."
    echo -e "\nTo revert at any time, run: ${YELLOW}sudo bash $0 --revert${NC}\n"
}

install_2fa() {
    require_root --2fa
    print_banner
    echo -e "${BOLD}Installing Mode 2: Strict 2FA (Password + Mobile Biometric)${NC}"
    echo -e "Requires typing your Linux password first, then approving on phone.\n"

    install_core_files

    echo -e "${CYAN}[*] Backing up existing /etc/pam.d/kde...${NC}"
    if [ -f /etc/pam.d/kde ]; then
        cp /etc/pam.d/kde "/etc/pam.d/kde.bak.$(date +%s)"
    fi

    echo -e "${CYAN}[*] Writing Strict 2FA PAM configuration to /etc/pam.d/kde...${NC}"
    cat <<'EOF' > /etc/pam.d/kde
#%PAM-1.0
# PC Authenticator - Strict 2FA (Linux Password + Mobile Biometric Approval)
auth       include                     system-local-login
auth       required                    pam_exec.so quiet /usr/local/bin/lockscreen-auth-check
account    include                     system-local-login
password   include                     system-local-login
session    include                     system-local-login
EOF
    chmod 644 /etc/pam.d/kde

    echo -e "\n${GREEN}${BOLD}==========================================================${NC}"
    echo -e "${GREEN}${BOLD} [✓] Strict 2FA Mode Successfully Installed!              ${NC}"
    echo -e "${GREEN}${BOLD}==========================================================${NC}"
    echo -e "\n👉 ${BOLD}How it works on lock screen:${NC}"
    echo -e "   1. Type your standard Linux password and press Enter."
    echo -e "   2. Scan your fingerprint on your phone to complete unlock."
    echo -e "\nTo revert at any time, run: ${YELLOW}sudo bash $0 --revert${NC}\n"
}

revert_to_password() {
    require_root --revert
    print_banner
    echo -e "${YELLOW}[*] Reverting lock screen to standard password-only...${NC}"

    # Remove PAM hook
    if [ -f /etc/pam.d/kde ]; then
        rm -f /etc/pam.d/kde
        echo -e "${GREEN}[✓] Removed custom /etc/pam.d/kde hook.${NC}"
    fi

    # Remove binary
    if [ -f /usr/local/bin/lockscreen-auth-check ]; then
        rm -f /usr/local/bin/lockscreen-auth-check
        echo -e "${GREEN}[✓] Removed /usr/local/bin/lockscreen-auth-check.${NC}"
    fi

    # Clean up /etc/pc-auth symlink
    if [ -L /etc/pc-auth/config.json ]; then
        rm -f /etc/pc-auth/config.json
    fi
    if [ -d /etc/pc-auth ] && [ -z "$(ls -A /etc/pc-auth 2>/dev/null)" ]; then
        rmdir /etc/pc-auth
    fi

    echo -e "\n${GREEN}${BOLD}==========================================================${NC}"
    echo -e "${GREEN}${BOLD} [✓] Lock screen successfully reverted to standard password! ${NC}"
    echo -e "${GREEN}${BOLD}==========================================================${NC}"
    echo -e "KDE Plasma lock screen is now restored to standard password authentication.\n"
}

show_status() {
    print_banner
    echo -e "${BOLD}System & Authentication Status:${NC}\n"

    # 1. Daemon service
    if [ -n "$TARGET_USER" ] && [ "$TARGET_USER" != "root" ]; then
        TARGET_UID=$(id -u "$TARGET_USER")
        if sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user is-active --quiet pc-authenticator.service; then
            echo -e " • Daemon Service:     ${GREEN}● Active (Running)${NC}"
        else
            echo -e " • Daemon Service:     ${RED}● Inactive (Stopped)${NC}"
        fi
    fi

    # 2. Daemon API Check
    API_STATUS=$(curl -s -m 2 http://127.0.0.1:1760/api/info 2>/dev/null || echo "")
    if [ -n "$API_STATUS" ]; then
        DEVICE_ID=$(python3 -c "import json; print(json.loads('''$API_STATUS''').get('device_id','unknown'))" 2>/dev/null || echo "unknown")
        HOSTNAME=$(python3 -c "import json; print(json.loads('''$API_STATUS''').get('hostname','unknown'))" 2>/dev/null || echo "unknown")
        echo -e " • Daemon API:         ${GREEN}Healthy (Port 1760)${NC}"
        echo -e " • Machine ID:         ${CYAN}$DEVICE_ID${NC}"
        echo -e " • Hostname:           ${CYAN}$HOSTNAME${NC}"
    else
        echo -e " • Daemon API:         ${RED}Unreachable on port 1760${NC}"
    fi

    # 3. Paired Devices
    if [ -f "$SCRIPT_DIR/config.json" ]; then
        PAIRED_COUNT=$(python3 -c "import json; cfg=json.load(open('$SCRIPT_DIR/config.json')); print(len(cfg.get('paired_clients', {})))" 2>/dev/null || echo "0")
        echo -e " • Paired Clients:     ${CYAN}$PAIRED_COUNT phone(s) paired${NC}"
        python3 -c "
import json
cfg = json.load(open('$SCRIPT_DIR/config.json'))
clients = cfg.get('paired_clients', {})
for k, v in clients.items():
    name = v.get('client_name', 'Unknown')
    ip = v.get('ip', 'N/A')
    print(f'   └─ 📱 {name} ({ip}) [Key: {k[:8]}...]')
" 2>/dev/null || true
    fi

    # 4. Enrolled USB Devices
    if [ -f "$SCRIPT_DIR/config.json" ]; then
        USB_COUNT=$(python3 -c "import json; cfg=json.load(open('$SCRIPT_DIR/config.json')); print(len(cfg.get('allowed_usb_serials', [])))" 2>/dev/null || echo "0")
        echo -e " • USB Tokens:         ${CYAN}$USB_COUNT enrolled${NC}"
        python3 -c "
import json
cfg = json.load(open('$SCRIPT_DIR/config.json'))
serials = cfg.get('allowed_usb_serials', [])
for s in serials:
    print(f'   └─ 🔌 Serial: {s}')
" 2>/dev/null || true
    fi

    # 5. Lock Screen PAM Status
    if [ -f /etc/pam.d/kde ]; then
        if grep -q "passwordless" /etc/pam.d/kde; then
            echo -e " • Lock Screen PAM:    ${GREEN}Enabled (Mode 1: Passwordless Mobile Fingerprint)${NC}"
        elif grep -q "lockscreen-auth-check" /etc/pam.d/kde; then
            echo -e " • Lock Screen PAM:    ${GREEN}Enabled (Mode 2: Strict 2FA)${NC}"
        else
            echo -e " • Lock Screen PAM:    ${YELLOW}Custom / Unknown PAM config${NC}"
        fi
    else
        echo -e " • Lock Screen PAM:    ${YELLOW}Disabled (Standard Password-Only)${NC}"
    fi
    echo ""
}

show_interactive_menu() {
    print_banner
    echo -e "${BOLD}Please select an action:${NC}"
    echo -e "  ${CYAN}1)${NC} ${BOLD}Install Passwordless Mobile Unlock${NC} (Recommended)"
    echo -e "     Hit Enter on lock screen $\rightarrow$ Scan fingerprint on phone $\rightarrow$ Unlocks PC"
    echo -e "     (Password fallback is automatically enabled if phone is away)"
    echo ""
    echo -e "  ${CYAN}2)${NC} ${BOLD}Install Strict 2FA Mode${NC}"
    echo -e "     Type Linux password first $\rightarrow$ Then approve via phone fingerprint"
    echo ""
    echo -e "  ${CYAN}3)${NC} ${BOLD}Revert to Standard Password-Only${NC}"
    echo -e "     Removes mobile authentication hook from lock screen"
    echo ""
    echo -e "  ${CYAN}4)${NC} ${BOLD}Enroll USB Phone Token${NC} (Plug phone in to unlock instantly via cable)"
    echo ""
    echo -e "  ${CYAN}5)${NC} ${BOLD}View System Status & Paired Devices${NC}"
    echo ""
    echo -e "  ${CYAN}6)${NC} Exit"
    echo ""
    read -rp "Enter choice [1-6]: " choice
    case "$choice" in
        1) install_passwordless ;;
        2) install_2fa ;;
        3) revert_to_password ;;
        4) python3 "$SCRIPT_DIR/enroll-usb.py" ;;
        5) show_status ;;
        6|q|Q) exit 0 ;;
        *) echo -e "${RED}Invalid option.${NC}"; exit 1 ;;
    esac
}

# Command-line argument dispatch
case "$1" in
    --install|--passwordless|-1)
        install_passwordless
        ;;
    --2fa|-2)
        install_2fa
        ;;
    --revert|--uninstall|--remove|-r)
        revert_to_password
        ;;
    --enroll-usb|--usb)
        python3 "$SCRIPT_DIR/enroll-usb.py"
        ;;
    --status|-s)
        show_status
        ;;
    --help|-h)
        echo "Usage: $0 [OPTION]"
        echo "Options:"
        echo "  --install, --passwordless   Install Passwordless Mobile Unlock (default)"
        echo "  --2fa                       Install Strict 2FA (Password + Mobile Biometric)"
        echo "  --revert, --uninstall       Revert lock screen to standard password-only"
        echo "  --enroll-usb                Enroll or manage USB hardware tokens"
        echo "  --status                    Display current status and paired devices"
        echo "  (no arguments)              Launch interactive menu"
        ;;
    *)
        show_interactive_menu
        ;;
esac
