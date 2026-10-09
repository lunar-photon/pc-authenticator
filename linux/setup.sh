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
        cat <<EOF > "$SERVICE_DIR/pc-connect-tray.service"
[Unit]
Description=PC Connect System Tray & Laser Pointer Overlay
After=network.target pc-authenticator.service

[Service]
Type=simple
ExecStart=/usr/bin/python3 $TARGET_HOME/.local/bin/pc-connect-tray
Restart=always
RestartSec=3
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=default.target
EOF
        chown -R "$TARGET_USER:$TARGET_USER" "$SERVICE_DIR"
        
        # Enable & start user services
        sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user daemon-reload >/dev/null 2>&1 || true
        sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user enable pc-authenticator.service pc-connect-tray.service >/dev/null 2>&1 || true
        sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user restart pc-authenticator.service pc-connect-tray.service >/dev/null 2>&1 || true
        loginctl enable-linger "$TARGET_USER" >/dev/null 2>&1 || true
        
        if sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user is-active --quiet pc-authenticator.service; then
            echo -e "${GREEN}[✓] Background daemon (pc-authenticator.service) is active.${NC}"
        fi
        if sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user is-active --quiet pc-connect-tray.service; then
            echo -e "${GREEN}[✓] System tray service (pc-connect-tray.service) is active.${NC}"
        else
            echo -e "${YELLOW}[!] User services enabled. (Will start on next login if not active in current session).${NC}"
        fi
        
        # Also install global command symlinks ~/.local/bin/pc-auth, pc-connect, pc-connect-send, pc-connect-tray
        mkdir -p "$TARGET_HOME/.local/bin"
        ln -sf "$SCRIPT_DIR/setup.sh" "$TARGET_HOME/.local/bin/pc-auth"
        ln -sf "$SCRIPT_DIR/pc-connect" "$TARGET_HOME/.local/bin/pc-connect"
        ln -sf "$SCRIPT_DIR/pc-connect-send" "$TARGET_HOME/.local/bin/pc-connect-send"
        ln -sf "$SCRIPT_DIR/pc-connect-tray.py" "$TARGET_HOME/.local/bin/pc-connect-tray"
        ln -sf "$SCRIPT_DIR/kdeconnect-handler" "$TARGET_HOME/.local/bin/kdeconnect-handler"
        chown -h "$TARGET_USER:$TARGET_USER" "$TARGET_HOME/.local/bin/pc-auth" "$TARGET_HOME/.local/bin/pc-connect" "$TARGET_HOME/.local/bin/pc-connect-send" "$TARGET_HOME/.local/bin/pc-connect-tray" "$TARGET_HOME/.local/bin/kdeconnect-handler" 2>/dev/null || true

        # Install Dolphin Context Menu
        mkdir -p "$TARGET_HOME/.local/share/kio/servicemenus"
        if [ -f "$SCRIPT_DIR/pc_connect.desktop" ]; then
            cp -f "$SCRIPT_DIR/pc_connect.desktop" "$TARGET_HOME/.local/share/kio/servicemenus/pc_connect.desktop"
            chown "$TARGET_USER:$TARGET_USER" "$TARGET_HOME/.local/share/kio/servicemenus/pc_connect.desktop" 2>/dev/null || true
            chmod 755 "$TARGET_HOME/.local/share/kio/servicemenus/pc_connect.desktop"
        fi

        # Install Autostart and Desktop Application Launcher
        mkdir -p "$TARGET_HOME/.config/autostart" "$TARGET_HOME/.local/share/applications"
        if [ -f "$SCRIPT_DIR/pc-connect-tray.desktop" ]; then
            cp -f "$SCRIPT_DIR/pc-connect-tray.desktop" "$TARGET_HOME/.config/autostart/pc-connect-tray.desktop"
            chown "$TARGET_USER:$TARGET_USER" "$TARGET_HOME/.config/autostart/pc-connect-tray.desktop" 2>/dev/null || true
            chmod 755 "$TARGET_HOME/.config/autostart/pc-connect-tray.desktop"
        fi
        if [ -f "$SCRIPT_DIR/pc-connect.desktop" ]; then
            cp -f "$SCRIPT_DIR/pc-connect.desktop" "$TARGET_HOME/.local/share/applications/pc-connect.desktop"
            chown "$TARGET_USER:$TARGET_USER" "$TARGET_HOME/.local/share/applications/pc-connect.desktop" 2>/dev/null || true
            chmod 755 "$TARGET_HOME/.local/share/applications/pc-connect.desktop"
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

download_ngrok() {
    local ngrok_bin=""
    if [ -x "$TARGET_HOME/.local/bin/ngrok" ]; then
        ngrok_bin="$TARGET_HOME/.local/bin/ngrok"
    elif command -v ngrok >/dev/null 2>&1; then
        ngrok_bin="$(command -v ngrok)"
    fi

    if [ -z "$ngrok_bin" ]; then
        echo -e "${CYAN}[*] Downloading official Ngrok binary...${NC}"
        local arch
        arch="$(uname -m)"
        local ngrok_arch="linux-amd64"
        case "$arch" in
            x86_64) ngrok_arch="linux-amd64" ;;
            aarch64|arm64) ngrok_arch="linux-arm64" ;;
            armv7l|armhf) ngrok_arch="linux-arm" ;;
            i386|i686) ngrok_arch="linux-386" ;;
        esac

        mkdir -p "$TARGET_HOME/.local/bin"
        if curl -sSL "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-${ngrok_arch}.tgz" | tar -xz -C "$TARGET_HOME/.local/bin" 2>/dev/null; then
            chmod 755 "$TARGET_HOME/.local/bin/ngrok"
            chown "$TARGET_USER:$TARGET_USER" "$TARGET_HOME/.local/bin/ngrok" 2>/dev/null || true
            ngrok_bin="$TARGET_HOME/.local/bin/ngrok"
            echo -e "${GREEN}[✓] Installed Ngrok to $ngrok_bin${NC}"
        else
            echo -e "${RED}[!] Failed to download Ngrok binary.${NC}"
            return 1
        fi
    fi
    echo "$ngrok_bin"
}

download_cloudflared() {
    local cf_bin=""
    if [ -x "$TARGET_HOME/.local/bin/cloudflared" ]; then
        cf_bin="$TARGET_HOME/.local/bin/cloudflared"
    elif command -v cloudflared >/dev/null 2>&1; then
        cf_bin="$(command -v cloudflared)"
    fi

    if [ -z "$cf_bin" ]; then
        echo -e "${CYAN}[*] Downloading official Cloudflare tunnel (cloudflared) binary...${NC}"
        local arch
        arch="$(uname -m)"
        local cf_arch="amd64"
        case "$arch" in
            x86_64) cf_arch="amd64" ;;
            aarch64|arm64) cf_arch="arm64" ;;
            armv7l|armhf) cf_arch="arm" ;;
            i386|i686) cf_arch="386" ;;
        esac

        mkdir -p "$TARGET_HOME/.local/bin"
        if curl -sSL -o "$TARGET_HOME/.local/bin/cloudflared" "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${cf_arch}" 2>/dev/null; then
            chmod 755 "$TARGET_HOME/.local/bin/cloudflared"
            chown "$TARGET_USER:$TARGET_USER" "$TARGET_HOME/.local/bin/cloudflared" 2>/dev/null || true
            cf_bin="$TARGET_HOME/.local/bin/cloudflared"
            echo -e "${GREEN}[✓] Installed Cloudflared to $cf_bin${NC}"
        else
            echo -e "${RED}[!] Failed to download Cloudflared binary.${NC}"
            return 1
        fi
    fi
    echo "$cf_bin"
}

configure_internet_tunnel() {
    echo -e "\n${CYAN}${BOLD}==========================================================${NC}"
    echo -e "${CYAN}${BOLD} 🌐 Configure Remote Internet Access (Ngrok & Cloudflare) ${NC}"
    echo -e "${CYAN}${BOLD}==========================================================${NC}"
    echo -e "Enables biometric unlock & PC connection from anywhere over the Internet"
    echo -e "(e.g., mobile 4G/5G, work or hotel Wi-Fi outside your home network).\n"

    echo -e "${BOLD}Select tunnel provider configuration:${NC}"
    echo -e "  ${CYAN}1)${NC} ${BOLD}Both Ngrok & Cloudflare (Recommended)${NC}"
    echo -e "     • Primary: Ngrok (Permanent static domain, fast & reliable)"
    echo -e "     • Fallback: Cloudflare (Automatic backup if Ngrok is offline)"
    echo -e "  ${CYAN}2)${NC} ${BOLD}Ngrok Only${NC}"
    echo -e "     • Permanent static domain (Requires free account at ngrok.com)"
    echo -e "  ${CYAN}3)${NC} ${BOLD}Cloudflare Quick Tunnel Only${NC}"
    echo -e "     • Zero configuration, no sign-up or token required (Dynamic URL)"
    echo -e "  ${CYAN}4)${NC} ${BOLD}Disable Internet Remote Access${NC} (Local Wi-Fi only)"
    echo -e "  ${CYAN}5)${NC} Keep existing configuration\n"

    read -rp "Enter choice [1-5, default 1]: " t_choice
    t_choice="${t_choice:-1}"

    local config_file="$TARGET_HOME/.config/pc-authenticator/config.json"
    mkdir -p "$(dirname "$config_file")"
    if [ ! -f "$config_file" ]; then
        echo '{"web_port": 1760, "paired_clients": {}}' > "$config_file"
        chown "$TARGET_USER:$TARGET_USER" "$config_file" 2>/dev/null || true
    fi

    case "$t_choice" in
        1|2)
            local ng_bin
            ng_bin=$(download_ngrok | tail -n 1)

            local has_token=false
            local ng_cfg="$TARGET_HOME/.config/ngrok/ngrok.yml"
            if [ -f "$ng_cfg" ] && grep -q 'authtoken:' "$ng_cfg" && ! grep -q 'authtoken: ""' "$ng_cfg"; then
                has_token=true
            fi

            if [ "$has_token" = true ]; then
                echo -e "\n${GREEN}[✓] Existing Ngrok authtoken detected in $ng_cfg.${NC}"
                read -rp "Would you like to change/update your Ngrok token? [y/N]: " change_token
                if [[ "$change_token" =~ ^[Yy]$ ]]; then
                    has_token=false
                fi
            fi

            if [ "$has_token" = false ]; then
                echo -e "\n${BOLD}👉 To get your free Ngrok token:${NC}"
                echo -e "   1. Create/login to your free account: ${CYAN}https://dashboard.ngrok.com/signup${NC}"
                echo -e "   2. Copy your Authtoken:             ${CYAN}https://dashboard.ngrok.com/get-started/your-authtoken${NC}\n"
                read -rp "Paste your Ngrok Authtoken: " user_token
                user_token="$(echo "$user_token" | tr -d '[:space:]')"
                if [ -n "$user_token" ] && [ -n "$ng_bin" ]; then
                    sudo -u "$TARGET_USER" "$ng_bin" config add-authtoken "$user_token"
                    echo -e "${GREEN}[✓] Ngrok authtoken saved successfully.${NC}"
                else
                    echo -e "${YELLOW}[!] No token entered. Skipping token update.${NC}"
                fi
            fi

            echo -e "\n${BOLD}👉 Ngrok Permanent Static Domain (Free Tier):${NC}"
            echo -e "   Every free Ngrok account includes 1 free static domain."
            echo -e "   Claim or view yours at: ${CYAN}https://dashboard.ngrok.com/endpoints${NC}"
            read -rp "Enter your Ngrok static domain (or press Enter to auto-detect): " user_domain
            user_domain="$(echo "$user_domain" | tr -d '[:space:]')"

            local prov_val="both"
            [ "$t_choice" == "2" ] && prov_val="ngrok"

            if [ "$t_choice" == "1" ]; then
                download_cloudflared >/dev/null 2>&1 || true
            fi

            python3 -c "
import json
cfg_path = '$config_file'
try:
    with open(cfg_path, 'r') as f:
        cfg = json.load(f)
except Exception:
    cfg = {}
cfg['enable_internet_tunnel'] = True
cfg['tunnel_provider'] = '$prov_val'
dom = '$user_domain'
if dom:
    if not dom.startswith('http'):
        dom = 'https://' + dom
    cfg['ngrok_url'] = dom.rstrip('/')
with open(cfg_path, 'w') as f:
    json.dump(cfg, f, indent=2)
"
            chown "$TARGET_USER:$TARGET_USER" "$config_file" 2>/dev/null || true
            echo -e "\n${GREEN}${BOLD}[✓] Remote Internet Access configured with provider: $prov_val!${NC}"
            ;;
        3)
            download_cloudflared >/dev/null 2>&1 || true
            python3 -c "
import json
cfg_path = '$config_file'
try:
    with open(cfg_path, 'r') as f:
        cfg = json.load(f)
except Exception:
    cfg = {}
cfg['enable_internet_tunnel'] = True
cfg['tunnel_provider'] = 'cloudflare'
with open(cfg_path, 'w') as f:
    json.dump(cfg, f, indent=2)
"
            chown "$TARGET_USER:$TARGET_USER" "$config_file" 2>/dev/null || true
            echo -e "\n${GREEN}${BOLD}[✓] Remote Internet Access configured with Cloudflare Quick Tunnel!${NC}"
            ;;
        4)
            python3 -c "
import json
cfg_path = '$config_file'
try:
    with open(cfg_path, 'r') as f:
        cfg = json.load(f)
except Exception:
    cfg = {}
cfg['enable_internet_tunnel'] = False
cfg['tunnel_provider'] = 'none'
with open(cfg_path, 'w') as f:
    json.dump(cfg, f, indent=2)
"
            chown "$TARGET_USER:$TARGET_USER" "$config_file" 2>/dev/null || true
            echo -e "\n${YELLOW}[✓] Remote Internet Access disabled. PC will only accept local Wi-Fi connections.${NC}"
            ;;
        5)
            echo -e "Keeping existing tunnel configuration."
            ;;
    esac

    if [ -n "$TARGET_USER" ] && [ "$TARGET_USER" != "root" ]; then
        TARGET_UID=$(id -u "$TARGET_USER")
        if sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user is-active --quiet pc-authenticator.service 2>/dev/null; then
            echo -e "${CYAN}[*] Restarting daemon service to apply tunnel settings...${NC}"
            sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user restart pc-authenticator.service 2>/dev/null || true
        fi
    fi
}

install_core_files() {
    INSTALL_DIR="$TARGET_HOME/.config/pc-authenticator"
    mkdir -p "$INSTALL_DIR"
    if [ "$SCRIPT_DIR" != "$INSTALL_DIR" ]; then
        echo -e "${CYAN}[*] Updating PC Authenticator files in $INSTALL_DIR...${NC}"
        if [ -f "$INSTALL_DIR/config.json" ]; then
            cp -f "$INSTALL_DIR/config.json" "$INSTALL_DIR/config.json.bak"
        fi
        cp -rf "$SCRIPT_DIR/"* "$INSTALL_DIR/" 2>/dev/null || true
        if [ -f "$INSTALL_DIR/config.json.bak" ]; then
            mv -f "$INSTALL_DIR/config.json.bak" "$INSTALL_DIR/config.json"
        fi
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
  "paired_clients": {},
  "enable_internet_tunnel": true,
  "tunnel_provider": "both"
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

    # Interactive tunnel setup check during first install
    if [ -t 0 ]; then
        local ng_cfg="$TARGET_HOME/.config/ngrok/ngrok.yml"
        local has_ngrok=false
        if [ -f "$ng_cfg" ] && grep -q 'authtoken:' "$ng_cfg" && ! grep -q 'authtoken: ""' "$ng_cfg"; then
            has_ngrok=true
        fi
        local has_cf=false
        if [ -x "$TARGET_HOME/.local/bin/cloudflared" ] || command -v cloudflared >/dev/null 2>&1; then
            has_cf=true
        fi

        if [ "$has_ngrok" = false ] && [ "$has_cf" = false ]; then
            configure_internet_tunnel
        fi
    fi
}

install_passwordless() {
    require_root --install
    print_banner
    echo -e "${BOLD}Installing Mode 1: Passwordless Mobile Unlock${NC}"
    echo -e "Press Enter on empty password field to unlock with Phone Fingerprint."
    echo -e "(Password fallback is always active if you type your Linux password)\n"

    install_core_files

    echo -e "${CYAN}[*] Writing Passwordless PAM configuration to /etc/pam.d/ (kde, plasmalogin, sddm)...${NC}"
    for p in /etc/pam.d/kde /etc/pam.d/plasmalogin /etc/pam.d/sddm; do
        if [ -f "$p" ]; then
            cp "$p" "$p.bak.$(date +%s)"
        fi
    done

    cat <<'EOF' > /etc/pam.d/kde
#%PAM-1.0
auth       [success=done default=ignore] pam_exec.so expose_authtok quiet /usr/local/bin/lockscreen-auth-check --passwordless
auth       include                     system-local-login
account    include                     system-local-login
password   include                     system-local-login
session    include                     system-local-login
EOF
    chmod 644 /etc/pam.d/kde

    cat <<'EOF' > /etc/pam.d/plasmalogin
#%PAM-1.0
auth       [success=done default=ignore] pam_exec.so expose_authtok quiet /usr/local/bin/lockscreen-auth-check --passwordless
auth        include     system-login
-auth       optional    pam_gnome_keyring.so
-auth       optional    pam_kwallet5.so
account     include     system-login
password    include     system-login
session     optional    pam_keyinit.so          force revoke
session     include     system-login
-session    optional    pam_gnome_keyring.so    auto_start
-session    optional    pam_kwallet5.so         auto_start
EOF
    chmod 644 /etc/pam.d/plasmalogin

    cat <<'EOF' > /etc/pam.d/sddm
#%PAM-1.0
auth       [success=done default=ignore] pam_exec.so expose_authtok quiet /usr/local/bin/lockscreen-auth-check --passwordless
auth        include     system-login
-auth       optional    pam_gnome_keyring.so
-auth       optional    pam_kwallet5.so
account     include     system-login
password    include     system-login
session     optional    pam_keyinit.so          force revoke
session     include     system-login
-session    optional    pam_gnome_keyring.so    auto_start
-session    optional    pam_kwallet5.so         auto_start
EOF
    chmod 644 /etc/pam.d/sddm

    echo -e "${CYAN}[*] Configuring display manager startup ordering for cold boot...${NC}"
    mkdir -p /etc/systemd/system/plasmalogin.service.d
    cat <<'EOF' > /etc/systemd/system/plasmalogin.service.d/override.conf
[Unit]
Wants=user@1000.service
After=user@1000.service
EOF
    systemctl daemon-reload >/dev/null 2>&1 || true
    echo -e "${GREEN}[✓] Plasma Login Manager ordered after background authenticator service.${NC}"

    echo -e "\n${GREEN}${BOLD}==========================================================${NC}"
    echo -e "${GREEN}${BOLD} [✓] Passwordless Mobile Unlock Successfully Installed!   ${NC}"
    echo -e "${GREEN}${BOLD}==========================================================${NC}"
    echo -e "\n👉 ${BOLD}How to test right now:${NC}"
    echo -e "   1. Lock screen or restart laptop: ${CYAN}loginctl lock-session${NC}"
    echo -e "   2. Hit ${BOLD}Enter${NC} on empty password field to approve on phone."
    echo -e "   3. Or type your Linux password anytime to unlock via password."
    echo -e "\nTo revert at any time, run: ${YELLOW}sudo bash $0 --revert${NC}\n"
}

install_2fa() {
    require_root --2fa
    print_banner
    echo -e "${BOLD}Installing Mode 2: Strict 2FA (Password + Mobile Biometric)${NC}"
    echo -e "Requires typing your Linux password first, then approving on phone.\n"

    install_core_files

    echo -e "${CYAN}[*] Writing Strict 2FA PAM configuration to /etc/pam.d/ (kde, plasmalogin, sddm)...${NC}"
    for p in /etc/pam.d/kde /etc/pam.d/plasmalogin /etc/pam.d/sddm; do
        if [ -f "$p" ]; then
            cp "$p" "$p.bak.$(date +%s)"
        fi
    done

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

    cat <<'EOF' > /etc/pam.d/plasmalogin
#%PAM-1.0
# PC Authenticator - Strict 2FA for Plasma Login Manager on Boot
auth        include     system-login
auth        required    pam_exec.so quiet /usr/local/bin/lockscreen-auth-check
-auth       optional    pam_gnome_keyring.so
-auth       optional    pam_kwallet5.so
account     include     system-login
password    include     system-login
session     optional    pam_keyinit.so          force revoke
session     include     system-login
-session    optional    pam_gnome_keyring.so    auto_start
-session    optional    pam_kwallet5.so         auto_start
EOF
    chmod 644 /etc/pam.d/plasmalogin

    cat <<'EOF' > /etc/pam.d/sddm
#%PAM-1.0
# PC Authenticator - Strict 2FA for SDDM on Boot
auth        include     system-login
auth        required    pam_exec.so quiet /usr/local/bin/lockscreen-auth-check
-auth       optional    pam_gnome_keyring.so
-auth       optional    pam_kwallet5.so
account     include     system-login
password    include     system-login
session     optional    pam_keyinit.so          force revoke
session     include     system-login
-session    optional    pam_gnome_keyring.so    auto_start
-session    optional    pam_kwallet5.so         auto_start
EOF
    chmod 644 /etc/pam.d/sddm

    echo -e "${CYAN}[*] Configuring display manager startup ordering for cold boot...${NC}"
    mkdir -p /etc/systemd/system/plasmalogin.service.d
    cat <<'EOF' > /etc/systemd/system/plasmalogin.service.d/override.conf
[Unit]
Wants=user@1000.service
After=user@1000.service
EOF
    systemctl daemon-reload >/dev/null 2>&1 || true
    echo -e "${GREEN}[✓] Plasma Login Manager ordered after background authenticator service.${NC}"

    echo -e "\n${GREEN}${BOLD}==========================================================${NC}"
    echo -e "${GREEN}${BOLD} [✓] Strict 2FA Mode Successfully Installed!              ${NC}"
    echo -e "${GREEN}${BOLD}==========================================================${NC}"
    echo -e "\n👉 ${BOLD}How it works on boot & lock screen:${NC}"
    echo -e "   1. Power on laptop after shutdown or lock screen."
    echo -e "   2. Type your standard Linux password and press Enter."
    echo -e "   3. Scan your fingerprint on your phone to complete unlock."
    echo -e "\nTo revert at any time, run: ${YELLOW}sudo bash $0 --revert${NC}\n"
}

revert_to_password() {
    require_root --revert
    print_banner
    echo -e "${YELLOW}[*] Reverting login & lock screen to standard password-only...${NC}"

    # Remove PAM hooks
    for pam_file in /etc/pam.d/kde /etc/pam.d/plasmalogin /etc/pam.d/sddm; do
        if [ -f "$pam_file" ]; then
            rm -f "$pam_file"
            echo -e "${GREEN}[✓] Removed custom $pam_file hook.${NC}"
        fi
    done

    # Remove display manager systemd override
    if [ -f /etc/systemd/system/plasmalogin.service.d/override.conf ]; then
        rm -f /etc/systemd/system/plasmalogin.service.d/override.conf
        rmdir /etc/systemd/system/plasmalogin.service.d 2>/dev/null || true
        systemctl daemon-reload >/dev/null 2>&1 || true
        echo -e "${GREEN}[✓] Removed display manager systemd override.${NC}"
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
    echo -e "${GREEN}${BOLD} [✓] Successfully reverted to standard password!          ${NC}"
    echo -e "${GREEN}${BOLD}==========================================================${NC}"
    echo -e "Login manager & lock screen are now restored to standard password authentication.\n"
}

show_status() {
    print_banner
    echo -e "${BOLD}System & Authentication Status:${NC}\n"

    # 1. Daemon & Tray services
    if [ -n "$TARGET_USER" ] && [ "$TARGET_USER" != "root" ]; then
        TARGET_UID=$(id -u "$TARGET_USER")
        if sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user is-active --quiet pc-authenticator.service; then
            echo -e " • Daemon Service:     ${GREEN}● Active (Running)${NC}"
        else
            echo -e " • Daemon Service:     ${RED}● Inactive (Stopped)${NC}"
        fi
        if sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$TARGET_UID" systemctl --user is-active --quiet pc-connect-tray.service; then
            echo -e " • Tray & Overlay:     ${GREEN}● Active (Running in background)${NC}"
        else
            echo -e " • Tray & Overlay:     ${RED}● Inactive (Stopped)${NC}"
        fi
    fi

    # 2. Daemon API Check
    API_STATUS=$(curl -s -m 2 http://127.0.0.1:1760/api/info 2>/dev/null || echo "")
    if [ -n "$API_STATUS" ]; then
        DEVICE_ID=$(python3 -c "import json; print(json.loads('''$API_STATUS''').get('device_id','unknown'))" 2>/dev/null || echo "unknown")
        HOSTNAME=$(python3 -c "import json; print(json.loads('''$API_STATUS''').get('hostname','unknown'))" 2>/dev/null || echo "unknown")
        TUNNEL_URL=$(python3 -c "import json; print(json.loads('''$API_STATUS''').get('internet_url') or '')" 2>/dev/null || echo "")
        TUNNEL_PROV=$(python3 -c "import json; print(json.loads('''$API_STATUS''').get('tunnel_provider') or '')" 2>/dev/null || echo "")
        echo -e " • Daemon API:         ${GREEN}Healthy (Port 1760)${NC}"
        echo -e " • Machine ID:         ${CYAN}$DEVICE_ID${NC}"
        echo -e " • Hostname:           ${CYAN}$HOSTNAME${NC}"
        if [ -n "$TUNNEL_URL" ]; then
            echo -e " • Internet Tunnel:    ${GREEN}● Online${NC} [Provider: ${BOLD}$TUNNEL_PROV${NC}]"
            echo -e "   └─ 👉 ${CYAN}$TUNNEL_URL${NC}"
        else
            echo -e " • Internet Tunnel:    ${YELLOW}● Connecting / Offline${NC}"
        fi
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

    # 5. Lock Screen & Boot Login PAM Status
    if [ -f /etc/pam.d/kde ] || [ -f /etc/pam.d/plasmalogin ] || [ -f /etc/pam.d/sddm ]; then
        MODE_NAME="Custom"
        if grep -q "passwordless" /etc/pam.d/kde 2>/dev/null || grep -q "passwordless" /etc/pam.d/plasmalogin 2>/dev/null; then
            MODE_NAME="Mode 1: Passwordless Mobile Fingerprint"
        elif grep -q "lockscreen-auth-check" /etc/pam.d/kde 2>/dev/null || grep -q "lockscreen-auth-check" /etc/pam.d/plasmalogin 2>/dev/null; then
            MODE_NAME="Mode 2: Strict 2FA (Password + Phone)"
        fi
        echo -e " • Lock Screen PAM:    ${GREEN}Enabled ($MODE_NAME)${NC}"
        if [ -f /etc/pam.d/plasmalogin ] || [ -f /etc/pam.d/sddm ]; then
            echo -e " • Boot Login PAM:     ${GREEN}Enabled (Active on boot after shutdown)${NC}"
        else
            echo -e " • Boot Login PAM:     ${YELLOW}Not installed (Run: sudo pc-auth --2fa)${NC}"
        fi
    else
        echo -e " • Lock & Boot PAM:    ${YELLOW}Disabled (Standard Password-Only)${NC}"
    fi
    echo ""
}

show_interactive_menu() {
    print_banner
    echo -e "${BOLD}Please select an action:${NC}"
    echo -e "  ${CYAN}1)${NC} ${BOLD}Install Passwordless Mobile Unlock${NC} (Recommended)"
    echo -e "     Hit Enter on lock screen ➔ Scan fingerprint on phone ➔ Unlocks PC"
    echo -e "     (Password fallback is automatically enabled if phone is away)"
    echo ""
    echo -e "  ${CYAN}2)${NC} ${BOLD}Install Strict 2FA Mode${NC}"
    echo -e "     Type Linux password first ➔ Then approve via phone fingerprint"
    echo ""
    echo -e "  ${CYAN}3)${NC} ${BOLD}Configure Internet Remote Access${NC} (Ngrok & Cloudflare)"
    echo -e "     Setup or update Ngrok authtoken, static domain, or Cloudflare tunnel"
    echo ""
    echo -e "  ${CYAN}4)${NC} ${BOLD}Enroll USB Phone Token${NC} (Plug phone in to unlock instantly via cable)"
    echo ""
    echo -e "  ${CYAN}5)${NC} ${BOLD}View System Status & Paired Devices${NC}"
    echo ""
    echo -e "  ${CYAN}6)${NC} ${BOLD}Revert to Standard Password-Only${NC}"
    echo -e "     Removes mobile authentication hook from lock screen"
    echo ""
    echo -e "  ${CYAN}7)${NC} Exit"
    echo ""
    read -rp "Enter choice [1-7]: " choice
    case "$choice" in
        1) install_passwordless ;;
        2) install_2fa ;;
        3) configure_internet_tunnel ;;
        4) python3 "$SCRIPT_DIR/enroll-usb.py" ;;
        5) show_status ;;
        6) revert_to_password ;;
        7|q|Q) exit 0 ;;
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
    --tunnel|--configure-tunnel|--ngrok|--cloudflare)
        configure_internet_tunnel
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
        echo "  --tunnel, --ngrok           Configure Internet Remote Access (Ngrok & Cloudflare)"
        echo "  --enroll-usb                Enroll or manage USB hardware tokens"
        echo "  --status                    Display current status and paired devices"
        echo "  (no arguments)              Launch interactive menu"
        ;;
    *)
        show_interactive_menu
        ;;
esac
