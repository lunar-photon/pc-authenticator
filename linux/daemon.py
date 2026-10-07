#!/usr/bin/env python3
import os
import sys
import json
import time
import uuid
import socket
import subprocess
import threading
import queue
import secrets
import hmac
import hashlib
import random
import shutil
import mimetypes
import struct
import fcntl
import re
from urllib.parse import urlparse, parse_qs, unquote, quote
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(BASE_DIR, 'config.json')
STATIC_DIR = os.path.join(BASE_DIR, 'static')
APK_FILE = os.path.join(STATIC_DIR, 'PCAuthenticator.apk')
if not os.path.exists(APK_FILE):
    _alt = os.path.join(STATIC_DIR, 'authenticator.apk')
    if os.path.exists(_alt):
        APK_FILE = _alt
STAGING_DIR = os.path.join(BASE_DIR, 'staging')
os.makedirs(STAGING_DIR, exist_ok=True)

DEFAULT_CONFIG = {
    "web_port": 1760,
    "auth_timeout_seconds": 35,
    "failmode": "secure",
    "allowed_usb_serials": [],
    "paired_clients": {}
}

active_phone_state = {
    "ip": None,
    "port": 1761,
    "client_name": "Android Phone",
    "battery_level": None,
    "is_charging": False,
    "last_seen": 0,
    "auth_token": None,
    "model": None,
    "manufacturer": None,
    "android_version": None,
    "storage_free": None,
    "storage_total": None
}

staged_files = {}  # token -> {"filepath": ..., "filename": ..., "size": ..., "created_at": ...}

def load_config():
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, 'r') as f:
                cfg = json.load(f)
                return {**DEFAULT_CONFIG, **cfg}
        except Exception as e:
            sys.stderr.write(f"Error loading config: {e}\n")
    return DEFAULT_CONFIG.copy()

def save_config(cfg):
    try:
        with open(CONFIG_FILE, 'w') as f:
            json.dump(cfg, f, indent=2)
        try:
            os.chmod(CONFIG_FILE, 0o600)
        except Exception:
            pass
    except Exception as e:
        sys.stderr.write(f"Error saving config: {e}\n")

def get_laptop_id():
    cfg = load_config()
    if 'device_id' not in cfg:
        cfg['device_id'] = str(uuid.uuid4())
        save_config(cfg)
    return cfg['device_id']

def get_auth_token_from_request(handler):
    auth_header = handler.headers.get('Authorization', '')
    if auth_header.startswith('Bearer '):
        return auth_header[7:].strip()
    parsed = urlparse(handler.path)
    qs = parse_qs(parsed.query)
    if 'token' in qs and qs['token']:
        return qs['token'][0].strip()
    return None

def is_tunnel_request(handler):
    headers = handler.headers
    return bool(
        headers.get('CF-Ray') or
        headers.get('CF-Connecting-IP') or
        headers.get('CDN-Loop') or
        headers.get('ngrok-trace-id') or
        headers.get('ngrok-agent-ips') or
        (headers.get('X-Forwarded-Proto') and headers.get('X-Forwarded-For'))
    )

def get_effective_client_ip(handler):
    if is_tunnel_request(handler):
        for header_name in ('CF-Connecting-IP', 'ngrok-agent-ips', 'X-Forwarded-For', 'X-Real-IP'):
            val = handler.headers.get(header_name)
            if val:
                return val.split(',')[0].strip()
    return handler.client_address[0]

def authenticate_client(handler, cfg):
    client_ip = get_effective_client_ip(handler)
    # Local requests (e.g. from desktop tray or pc-connect CLI) are always authorized,
    # UNLESS they arrived through an external reverse proxy / Cloudflare tunnel!
    if handler.client_address[0] in ('127.0.0.1', '::1', 'localhost') and not is_tunnel_request(handler):
        return {"client_name": "Local PC", "secret_key": "", "local": True}, "local"

    paired = cfg.get('paired_clients', {})
    token = get_auth_token_from_request(handler)
    if not token or token not in paired:
        return None, token

    client_info = paired[token]
    client_info['ip'] = client_ip
    client_info['last_seen'] = time.time()

    active_phone_state['ip'] = client_ip
    active_phone_state['client_name'] = client_info.get('client_name', 'Android Phone')
    active_phone_state['last_seen'] = time.time()
    active_phone_state['auth_token'] = token

    return client_info, token

def get_local_ip():
    try:
        out = subprocess.check_output(['ip', '-4', 'addr', 'show'], stderr=subprocess.DEVNULL).decode()
        import re
        m = re.search(r'(?:wlan\d|wlp\w+|eth\d|enp\w+):.*?\sinet\s+([0-9.]+)/', out, re.DOTALL)
        if m:
            return m.group(1)
    except Exception:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('1.1.1.1', 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return '127.0.0.1'

# ==========================================
# Dolphin Dynamic Service Menu Helpers
# ==========================================

def update_dolphin_servicemenu():
    try:
        cfg = load_config()
        paired = cfg.get('paired_clients', {})
        
        valid_clients = []
        for token, client in paired.items():
            cid = client.get('client_id') or token
            if cid == "phone-auto-test" and len(paired) > 1:
                continue
            name = client.get('client_name') or 'Android Phone'
            valid_clients.append({"token": token, "client_id": cid, "name": name})

        desktop_path = os.path.expanduser('~/.local/share/kio/servicemenus/pc_connect.desktop')
        repo_path = os.path.join(BASE_DIR, 'pc_connect.desktop')
        os.makedirs(os.path.dirname(desktop_path), exist_ok=True)

        if len(valid_clients) == 1:
            dev_name = valid_clients[0]['name']
            content = f"""[Desktop Entry]
Type=Service
ServiceTypes=KonqPopupMenu/Plugin
X-KDE-ServiceTypes=KonqPopupMenu/Plugin
MimeType=all/all;all/allfiles;inode/directory;application/octet-stream;
Actions=sendToDevice;
X-KDE-Priority=TopLevel
X-KDE-Submenu=PC Connect

[Desktop Action sendToDevice]
Name=Send to {dev_name}
Icon=smartphone
Exec=/home/lunarphoton/.local/bin/pc-connect-send %U
"""
        elif len(valid_clients) > 1:
            actions_list = ";".join([f"sendDevice{i}" for i in range(len(valid_clients))]) + ";"
            actions_blocks = []
            for i, c in enumerate(valid_clients):
                actions_blocks.append(f"""[Desktop Action sendDevice{i}]
Name=Send to {c['name']}
Icon=smartphone
Exec=/home/lunarphoton/.local/bin/pc-connect-send --device "{c['client_id']}" %U
""")
            content = f"""[Desktop Entry]
Type=Service
ServiceTypes=KonqPopupMenu/Plugin
X-KDE-ServiceTypes=KonqPopupMenu/Plugin
MimeType=all/all;all/allfiles;inode/directory;application/octet-stream;
Actions={actions_list}
X-KDE-Priority=TopLevel
X-KDE-Submenu=PC Connect

""" + "\n".join(actions_blocks)
        else:
            content = """[Desktop Entry]
Type=Service
ServiceTypes=KonqPopupMenu/Plugin
X-KDE-ServiceTypes=KonqPopupMenu/Plugin
MimeType=all/all;all/allfiles;inode/directory;application/octet-stream;
Actions=sendViaPCConnect;
X-KDE-Priority=TopLevel
X-KDE-Submenu=PC Connect

[Desktop Action sendViaPCConnect]
Name=Send via PC Connect
Icon=smartphone
Exec=/home/lunarphoton/.local/bin/pc-connect-send %U
"""
        with open(desktop_path, 'w', encoding='utf-8') as f:
            f.write(content)
        try:
            with open(repo_path, 'w', encoding='utf-8') as f:
                f.write(content)
        except Exception:
            pass
        try:
            subprocess.run(['kbuildsycoca6'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
        except Exception:
            pass
    except Exception as e:
        sys.stderr.write(f"update_dolphin_servicemenu error: {e}\n")

def unpair_device(target_identifier):
    cfg = load_config()
    paired = cfg.get('paired_clients', {})
    found_tokens = []
    device_name = "Android Phone"
    for tok, client in list(paired.items()):
        cid = client.get('client_id')
        if tok == target_identifier or cid == target_identifier or client.get('ip') == target_identifier:
            found_tokens.append(tok)
            device_name = client.get('client_name', device_name)

    if not found_tokens:
        return False, "Device not found"

    for tok in found_tokens:
        if tok in paired:
            del paired[tok]
    cfg['paired_clients'] = paired
    save_config(cfg)

    auth_mgr.broadcast_ndjson(json.dumps({"event": "unpaired", "target": target_identifier, "time": int(time.time())}) + "\n")

    if active_phone_state.get('auth_token') in found_tokens or active_phone_state.get('ip') == target_identifier:
        active_phone_state['ip'] = None
        active_phone_state['auth_token'] = None
        active_phone_state['client_name'] = "Android Phone"
        active_phone_state['battery_level'] = None
        active_phone_state['last_seen'] = 0

    update_dolphin_servicemenu()
    try:
        subprocess.Popen(['notify-send', '-i', 'dialog-warning', '-a', 'PC Connect', 'Device Unpaired', f'{device_name} has been unpaired from this PC.'])
    except Exception:
        pass
    return True, device_name

# ==========================================
# Laptop Status, Weather & System Helpers
# ==========================================

_last_cpu_sample = None

def get_cpu_usage_pct():
    global _last_cpu_sample
    try:
        with open('/proc/stat', 'r') as f:
            line = f.readline()
        parts = [float(x) for x in line.split()[1:8]]
        idle = parts[3] + parts[4]
        total = sum(parts)
        if _last_cpu_sample:
            prev_idle, prev_total = _last_cpu_sample
            idle_delta = idle - prev_idle
            total_delta = total - prev_total
            _last_cpu_sample = (idle, total)
            if total_delta > 0:
                return max(0.0, min(100.0, round((1.0 - idle_delta / total_delta) * 100.0, 1)))
        _last_cpu_sample = (idle, total)
    except Exception:
        pass
    return 0.0

def is_pc_locked():
    try:
        out = subprocess.check_output(
            ['qdbus6', 'org.freedesktop.ScreenSaver', '/ScreenSaver', 'org.freedesktop.ScreenSaver.GetActive'],
            stderr=subprocess.DEVNULL, timeout=2
        ).decode().strip()
        if out.lower() == 'true':
            return True
        if out.lower() == 'false':
            return False
    except Exception:
        pass
    try:
        out = subprocess.check_output(
            ['loginctl', 'show-session', 'auto', '-p', 'LockedHint'],
            stderr=subprocess.DEVNULL, timeout=2
        ).decode().strip()
        if 'yes' in out.lower():
            return True
        if 'no' in out.lower():
            return False
    except Exception:
        pass
    return False

weather_cache = {"data": None, "timestamp": 0}

def get_weather_cached():
    global weather_cache
    now = time.time()
    if weather_cache["data"] and (now - weather_cache["timestamp"] < 900):
        return weather_cache["data"]
    try:
        req = urllib.request.Request('https://wttr.in/?format=j1', headers={'User-Agent': 'curl/8.0'})
        with urllib.request.urlopen(req, timeout=4) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            cur = data.get('current_condition', [{}])[0]
            area = data.get('nearest_area', [{}])[0]
            city = area.get('areaName', [{}])[0].get('value', 'Local Area')
            temp_c = cur.get('temp_C', '--')
            desc = cur.get('weatherDesc', [{}])[0].get('value', 'Clear')
            humidity = cur.get('humidity', '--')
            feels_like = cur.get('FeelsLikeC', temp_c)
            desc_clean = desc.strip()
            feels_str = f" (Feels {feels_like}°C)" if feels_like and str(feels_like) != str(temp_c) else ""
            formatted = f"{city} • {temp_c}°C{feels_str}, {desc_clean}"
            parsed = {
                "city": city,
                "temp_c": temp_c,
                "desc": desc_clean,
                "humidity": humidity,
                "feels_like_c": feels_like,
                "formatted": formatted,
                "cached_at": int(now)
            }
            weather_cache["data"] = parsed
            weather_cache["timestamp"] = now
            return parsed
    except Exception:
        if weather_cache["data"]:
            return weather_cache["data"]
        return None

def get_pc_system_status():
    locked = is_pc_locked()

    import glob
    bat_info = {"capacity": None, "percent": None, "status": "Unknown", "charging": False, "ac_online": False}
    for p in glob.glob('/sys/class/power_supply/BAT*'):
        try:
            with open(os.path.join(p, 'capacity')) as f:
                c = int(f.read().strip())
                bat_info['capacity'] = c
                bat_info['percent'] = c
            with open(os.path.join(p, 'status')) as f:
                st = f.read().strip()
                bat_info['status'] = st
                bat_info['charging'] = (st.lower() == 'charging')
            break
        except Exception:
            pass

    for p in glob.glob('/sys/class/power_supply/AC*') + glob.glob('/sys/class/power_supply/ADP*'):
        try:
            with open(os.path.join(p, 'online')) as f:
                bat_info['ac_online'] = (f.read().strip() == '1')
            break
        except Exception:
            pass

    cpu_pct = get_cpu_usage_pct()

    mem_info = {"total_gb": 0, "used_gb": 0, "used_pct": 0}
    try:
        mem = {}
        with open('/proc/meminfo') as f:
            for line in f:
                parts = line.split(':')
                if len(parts) == 2:
                    mem[parts[0].strip()] = int(parts[1].strip().split()[0])
        tot_kb = mem.get('MemTotal', 0)
        avail_kb = mem.get('MemAvailable', 0)
        used_kb = tot_kb - avail_kb
        mem_info = {
            "total_gb": round(tot_kb / 1048576.0, 1),
            "used_gb": round(used_kb / 1048576.0, 1),
            "used_pct": round((used_kb / tot_kb) * 100.0, 1) if tot_kb else 0
        }
    except Exception:
        pass

    uptime_info = {"seconds": 0, "formatted": "--"}
    try:
        with open('/proc/uptime') as f:
            up_sec = float(f.read().split()[0])
        h = int(up_sec // 3600)
        m = int((up_sec % 3600) // 60)
        uptime_info = {"seconds": int(up_sec), "formatted": f"{h}h {m}m"}
    except Exception:
        pass

    storage_info = {"total_gb": 0, "used_gb": 0, "free_gb": 0, "used_pct": 0, "formatted": "--"}
    try:
        du = shutil.disk_usage(os.path.expanduser('~'))
        storage_info = {
            "total_gb": round(du.total / (1024**3), 1),
            "used_gb": round(du.used / (1024**3), 1),
            "free_gb": round(du.free / (1024**3), 1),
            "used_pct": round((du.used / du.total) * 100.0, 1),
            "formatted": f"{round(du.free / (1024**3), 1)} GB free / {round(du.total / (1024**3), 1)} GB ({round((du.used / du.total) * 100.0)}%)"
        }
    except Exception:
        pass

    weather = get_weather_cached()
    weather_formatted = weather.get("formatted", "Weather Unavailable") if weather else "Weather Unavailable"

    cfg = load_config()
    port = cfg.get('web_port', 1760)
    local_ip = get_local_ip()

    return {
        "status": "ok",
        "hostname": socket.gethostname(),
        "locked": locked,
        "battery": bat_info,
        "cpu": {"usage_pct": cpu_pct},
        "memory": mem_info,
        "uptime": uptime_info,
        "storage": storage_info,
        "weather": weather,
        "weather_formatted": weather_formatted,
        "local_url": f"http://{local_ip}:{port}",
        "internet_url": tunnel_mgr.get_url() if 'tunnel_mgr' in globals() else None,
        "captive_portal_supported": os.path.exists(os.path.expanduser('~/bin/iiser-login.sh'))
    }

def trigger_pc_captive_login(body=None):
    body = body or {}
    script_path = os.path.expanduser('~/bin/iiser-login.sh')
    if os.path.exists(script_path) and not body.get('username'):
        try:
            res = subprocess.run([script_path], capture_output=True, text=True, timeout=8)
            out = res.stdout.strip()
            if '{username}' in out:
                out = out.replace('{username}', 'chandra26')
            status = 'LIVE' if 'LIVE' in out or 'signed in' in out else 'OK'
            return {"status": "ok", "login_status": status, "output": out}
        except Exception as e:
            return {"status": "error", "message": str(e)}

    username = body.get('username')
    password = body.get('password')
    gateway_url = body.get('gateway_url', 'https://gateway.iisertvm.ac.in:8090/login.xml')
    if (not username or not password) and os.path.exists(script_path):
        try:
            with open(script_path, 'r') as f:
                content = f.read()
                m_user = re.search(r'USERNAME=["\'](.*?)["\']', content)
                m_pass = re.search(r'PASSWORD=["\'](.*?)["\']', content)
                if not username and m_user: username = m_user.group(1)
                if not password and m_pass: password = m_pass.group(1)
        except Exception:
            pass

    if not username or not password:
        return {"status": "error", "message": "Credentials not configured on PC"}

    try:
        import urllib.request, urllib.parse, ssl
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        data = urllib.parse.urlencode({
            'mode': '191',
            'username': username,
            'password': password,
            'a': str(int(time.time() * 1000)),
            'producttype': '0'
        }).encode('utf-8')
        req = urllib.request.Request(gateway_url, data=data, headers={
            'Origin': 'https://gateway.iisertvm.ac.in:8090',
            'Referer': 'https://gateway.iisertvm.ac.in:8090/httpclient.html',
            'Content-Type': 'application/x-www-form-urlencoded'
        })
        with urllib.request.urlopen(req, context=ctx, timeout=8) as r:
            out = r.read().decode('utf-8', errors='ignore')
            if '{username}' in out and username:
                out = out.replace('{username}', username)
            status = 'LIVE' if 'LIVE' in out or 'signed in' in out else 'OK'
            return {"status": "ok", "login_status": status, "output": out}
    except Exception as e:
        return {"status": "error", "message": str(e)}

def get_display_env():
    env = os.environ.copy()
    runtime_dir = env.get('XDG_RUNTIME_DIR') or f"/run/user/{os.getuid()}"
    env['XDG_RUNTIME_DIR'] = runtime_dir
    if not env.get('WAYLAND_DISPLAY'):
        try:
            for entry in os.listdir(runtime_dir):
                if entry.startswith('wayland-') and not entry.endswith('.lock'):
                    env['WAYLAND_DISPLAY'] = entry
                    break
        except Exception:
            pass
        if not env.get('WAYLAND_DISPLAY') and os.path.exists(os.path.join(runtime_dir, 'wayland-0')):
            env['WAYLAND_DISPLAY'] = 'wayland-0'
    if not env.get('DISPLAY'):
        env['DISPLAY'] = ':0'
    if env.get('WAYLAND_DISPLAY'):
        env['QT_QPA_PLATFORM'] = 'wayland'
    env.setdefault('XDG_CURRENT_DESKTOP', 'KDE')
    if not env.get('DBUS_SESSION_BUS_ADDRESS'):
        dbus_path = os.path.join(runtime_dir, 'bus')
        if os.path.exists(dbus_path):
            env['DBUS_SESSION_BUS_ADDRESS'] = f'unix:path={dbus_path}'
    return env

def capture_pc_screenshot():
    tmp_out = f"/tmp/pc_screen_{secrets.token_hex(4)}.jpg"
    try:
        env = get_display_env()
        res = subprocess.run(
            ['spectacle', '-b', '-n', '-o', tmp_out],
            env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5
        )
        if res.returncode == 0 and os.path.exists(tmp_out):
            with open(tmp_out, 'rb') as f:
                data = f.read()
            try:
                os.remove(tmp_out)
            except Exception:
                pass
            return data
    except Exception as e:
        sys.stderr.write(f"capture_pc_screenshot error: {e}\n")
    if os.path.exists(tmp_out):
        try: os.remove(tmp_out)
        except Exception: pass
    return None

def format_file_size(size_bytes):
    for unit in ['B', 'KB', 'MB', 'GB']:
        if size_bytes < 1024.0:
            return f"{size_bytes:.1f} {unit}"
        size_bytes /= 1024.0
    return f"{size_bytes:.1f} TB"

def search_laptop_files(query, max_results=50):
    query = (query or '').strip()
    if not query:
        return []

    # 1. Try ultra-fast indexed search via fsearch-cli
    fsearch_bin = shutil.which('fsearch-cli') or os.path.expanduser('~/.local/bin/fsearch-cli')
    if os.path.exists(fsearch_bin) and os.access(fsearch_bin, os.X_OK):
        try:
            res = subprocess.run([fsearch_bin, '--json', query, str(max_results)],
                                 capture_output=True, text=True, timeout=5)
            if res.returncode == 0 and res.stdout.strip():
                parsed = json.loads(res.stdout)
                if isinstance(parsed, list):
                    return parsed
        except Exception:
            pass

    # 2. Fallback to manual walk if fsearch-cli is unavailable
    query_lower = query.lower()
    base_dirs = [
        os.path.expanduser('~/Downloads'),
        os.path.expanduser('~/Documents'),
        os.path.expanduser('~/Pictures'),
        os.path.expanduser('~/Videos'),
        os.path.expanduser('~/Desktop'),
        os.path.expanduser('~')
    ]
    user = os.environ.get('USER', 'lunarphoton')
    for mdir in [f'/run/media/{user}', f'/media/{user}', '/media', '/mnt']:
        if os.path.exists(mdir):
            try:
                for entry in os.scandir(mdir):
                    if entry.is_dir():
                        base_dirs.append(entry.path)
            except Exception:
                pass
    skip_dirs = {'.git', '.cache', 'node_modules', '.venv', '__pycache__', '.local', '.cargo', '.npm', '.rustup', '.gradle'}
    seen_paths = set()
    results = []

    include_hidden = query.startswith('.')

    for bdir in base_dirs:
        if not os.path.exists(bdir):
            continue
        for root, dirs, files in os.walk(bdir):
            dirs[:] = [d for d in dirs if (include_hidden or not d.startswith('.')) and d not in skip_dirs]
            for fn in files:
                if not include_hidden and fn.startswith('.'):
                    continue
                if query_lower in fn.lower():
                    full_p = os.path.join(root, fn)
                    if full_p in seen_paths:
                        continue
                    seen_paths.add(full_p)
                    try:
                        st = os.stat(full_p)
                        results.append({
                            "name": fn,
                            "path": full_p,
                            "is_dir": False,
                            "size": st.st_size,
                            "size_formatted": format_file_size(st.st_size),
                            "mtime": int(st.st_mtime),
                            "extension": os.path.splitext(fn)[1].lower()
                        })
                    except Exception:
                        pass
                    if len(results) >= max_results:
                        return results
    return results

# ==========================================
# KDE Plasma 6 & MPRIS Helpers
# ==========================================

def get_mpris_players():
    try:
        out = subprocess.check_output(['qdbus6'], stderr=subprocess.DEVNULL).decode('utf-8')
        return [l.strip() for l in out.splitlines() if l.strip().startswith('org.mpris.MediaPlayer2.')]
    except Exception:
        return []

def adjust_system_volume(direction):
    """Adjust system master volume via PipeWire (wpctl), PulseAudio (pactl), or ALSA (amixer)"""
    if direction in ("up", "VolumeUp"):
        if shutil.which('wpctl'):
            try:
                res = subprocess.run(['wpctl', 'set-volume', '-l', '1.0', '@DEFAULT_AUDIO_SINK@', '5%+'], capture_output=True)
                if res.returncode == 0:
                    return True
            except Exception:
                pass
        if shutil.which('pactl'):
            try:
                res = subprocess.run(['pactl', 'set-sink-volume', '@DEFAULT_SINK@', '+5%'], capture_output=True)
                if res.returncode == 0:
                    return True
            except Exception:
                pass
        if shutil.which('amixer'):
            try:
                res = subprocess.run(['amixer', '-D', 'pulse', 'sset', 'Master', '5%+'], capture_output=True)
                if res.returncode == 0:
                    return True
            except Exception:
                pass
    elif direction in ("down", "VolumeDown"):
        if shutil.which('wpctl'):
            try:
                res = subprocess.run(['wpctl', 'set-volume', '@DEFAULT_AUDIO_SINK@', '5%-'], capture_output=True)
                if res.returncode == 0:
                    return True
            except Exception:
                pass
        if shutil.which('pactl'):
            try:
                res = subprocess.run(['pactl', 'set-sink-volume', '@DEFAULT_SINK@', '-5%'], capture_output=True)
                if res.returncode == 0:
                    return True
            except Exception:
                pass
        if shutil.which('amixer'):
            try:
                res = subprocess.run(['amixer', '-D', 'pulse', 'sset', 'Master', '5%-'], capture_output=True)
                if res.returncode == 0:
                    return True
            except Exception:
                pass
    elif direction in ("mute", "toggle_mute", "VolumeMute"):
        if shutil.which('wpctl'):
            try:
                res = subprocess.run(['wpctl', 'set-mute', '@DEFAULT_AUDIO_SINK@', 'toggle'], capture_output=True)
                if res.returncode == 0:
                    return True
            except Exception:
                pass
        if shutil.which('pactl'):
            try:
                res = subprocess.run(['pactl', 'set-sink-mute', '@DEFAULT_SINK@', 'toggle'], capture_output=True)
                if res.returncode == 0:
                    return True
            except Exception:
                pass
    return False

def get_system_volume():
    try:
        if shutil.which('wpctl'):
            out = subprocess.check_output(['wpctl', 'get-volume', '@DEFAULT_AUDIO_SINK@'], stderr=subprocess.DEVNULL).decode().strip()
            parts = out.split()
            if len(parts) >= 2 and parts[0] == 'Volume:':
                vol = int(round(float(parts[1]) * 100))
                is_muted = '[MUTED]' in out
                return vol, is_muted
        if shutil.which('pactl'):
            out = subprocess.check_output(['pactl', 'get-sink-volume', '@DEFAULT_SINK@'], stderr=subprocess.DEVNULL).decode()
            import re
            m = re.search(r'(\d+)%', out)
            if m:
                return int(m.group(1)), False
    except Exception:
        pass
    return 50, False

def get_mpris_status():
    vol, is_muted = get_system_volume()
    players = get_mpris_players()
    if not players:
        return {
            "has_player": False,
            "volume": vol,
            "is_muted": is_muted,
            "title": f"System Volume: {vol}%"
        }
    chosen = players[0]
    chosen_status = "Stopped"
    for p in players:
        try:
            st = subprocess.check_output(['qdbus6', p, '/org/mpris/MediaPlayer2', 'org.mpris.MediaPlayer2.Player.PlaybackStatus'], stderr=subprocess.DEVNULL).decode().strip()
            if st == "Playing":
                chosen = p
                chosen_status = st
                break
            elif st == "Paused":
                chosen = p
                chosen_status = st
        except Exception:
            pass

    title, artist, album, art_url = "", "", "", ""
    try:
        lines = subprocess.check_output(['qdbus6', chosen, '/org/mpris/MediaPlayer2', 'org.mpris.MediaPlayer2.Player.Metadata'], stderr=subprocess.DEVNULL).decode().splitlines()
        for line in lines:
            if line.startswith('xesam:title:'):
                title = line[len('xesam:title:'):].strip()
            elif line.startswith('xesam:artist:'):
                artist = line[len('xesam:artist:'):].strip()
            elif line.startswith('xesam:album:'):
                album = line[len('xesam:album:'):].strip()
            elif line.startswith('mpris:artUrl:'):
                art_url = line[len('mpris:artUrl:'):].strip()
    except Exception:
        pass

    return {
        "has_player": True,
        "player": chosen.replace('org.mpris.MediaPlayer2.', ''),
        "status": chosen_status,
        "title": title or "Unknown Title",
        "artist": artist or "Unknown Artist",
        "album": album,
        "art_url": art_url,
        "volume": vol,
        "is_muted": is_muted
    }

def send_mpris_command(command):
    # 1. Volume commands always control master system audio output
    if command in ("VolumeUp", "volup", "volume_up"):
        return adjust_system_volume("up")
    elif command in ("VolumeDown", "voldown", "volume_down"):
        return adjust_system_volume("down")
    elif command in ("VolumeMute", "mute", "toggle_mute"):
        return adjust_system_volume("mute")

    # 2. Playback commands target MPRIS players
    players = get_mpris_players()
    if not players:
        return False
    target = players[0]
    for p in players:
        try:
            st = subprocess.check_output(['qdbus6', p, '/org/mpris/MediaPlayer2', 'org.mpris.MediaPlayer2.Player.PlaybackStatus'], stderr=subprocess.DEVNULL).decode().strip()
            if st == "Playing":
                target = p
                break
        except Exception:
            pass

    if command in ("PlayPause", "Play", "Pause", "Next", "Previous", "Stop"):
        subprocess.run(['qdbus6', target, '/org/mpris/MediaPlayer2', f'org.mpris.MediaPlayer2.Player.{command}'], stderr=subprocess.DEVNULL)
        return True
    return False

_last_pc_clipboard = ""
_last_phone_clipboard = ""
_clipboard_lock = threading.Lock()

def get_kde_clipboard():
    try:
        return subprocess.check_output(['qdbus6', 'org.kde.klipper', '/klipper', 'org.kde.klipper.klipper.getClipboardContents'], stderr=subprocess.DEVNULL).decode('utf-8')
    except Exception:
        return ""

def set_kde_clipboard(text):
    try:
        subprocess.run(['qdbus6', 'org.kde.klipper', '/klipper', 'org.kde.klipper.klipper.setClipboardContents', text], stderr=subprocess.DEVNULL)
        try:
            subprocess.Popen(['notify-send', '-i', 'edit-paste', '-a', 'PC Connect', 'Clipboard Synced', 'Received clipboard from phone'], stderr=subprocess.DEVNULL)
        except Exception:
            pass
        return True
    except Exception:
        return False

# --- Terminal Management Functions ---
def get_open_terminals():
    terminals = []
    seen_ttys = set()
    try:
        q_res = subprocess.run(['qdbus6'], capture_output=True, text=True, timeout=2)
        konsole_svcs = [l.strip() for l in q_res.stdout.splitlines() if 'org.kde.konsole' in l]
        for svc in konsole_svcs:
            try:
                s_res = subprocess.run(['qdbus6', svc, '/Windows/1', 'org.kde.konsole.Window.sessionList'], capture_output=True, text=True, timeout=1)
                session_ids = [s.strip() for s in s_res.stdout.splitlines() if s.strip().isdigit()]
                if not session_ids:
                    intro = subprocess.run(['qdbus6', svc, '/Sessions', 'org.freedesktop.DBus.Introspectable.Introspect'], capture_output=True, text=True, timeout=1)
                    session_ids = re.findall(r'<node name="(\d+)"', intro.stdout)
                
                for sid in session_ids:
                    spath = f'/Sessions/{sid}'
                    
                    # Verify session is actively alive
                    sh_res = subprocess.run(['qdbus6', svc, spath, 'org.kde.konsole.Session.processId'], capture_output=True, text=True, timeout=1)
                    sh_pid = sh_res.stdout.strip()
                    if not sh_pid or not os.path.exists(f'/proc/{sh_pid}'):
                        continue

                    t_res = subprocess.run(['qdbus6', svc, spath, 'org.kde.konsole.Session.title', '1'], capture_output=True, text=True, timeout=1)
                    title = t_res.stdout.strip()
                    
                    fg_res = subprocess.run(['qdbus6', svc, spath, 'org.kde.konsole.Session.foregroundProcessId'], capture_output=True, text=True, timeout=1)
                    fg_pid = fg_res.stdout.strip()
                    
                    cwd = ''
                    tty = ''
                    if sh_pid:
                        try:
                            cwd = os.readlink(f'/proc/{sh_pid}/cwd')
                        except Exception:
                            pass
                        try:
                            fd0 = os.readlink(f'/proc/{sh_pid}/fd/0')
                            if 'pts' in fd0:
                                tty = os.path.basename(fd0)
                                seen_ttys.add(tty)
                                seen_ttys.add(f'pts/{tty}')
                        except Exception:
                            pass
                            
                    fg_comm = ''
                    if fg_pid and os.path.exists(f'/proc/{fg_pid}/comm'):
                        try:
                            with open(f'/proc/{fg_pid}/comm') as f:
                                fg_comm = f.read().strip()
                        except Exception:
                            pass
                    if not fg_comm and sh_pid and os.path.exists(f'/proc/{sh_pid}/comm'):
                        try:
                            with open(f'/proc/{sh_pid}/comm') as f:
                                fg_comm = f.read().strip()
                        except Exception:
                            pass
                            
                    txt_res = subprocess.run(['qdbus6', svc, spath, 'org.kde.konsole.Session.getDisplayedText', '0', '4'], capture_output=True, text=True, timeout=1)
                    preview = txt_res.stdout.strip()
                    
                    terminals.append({
                        'id': f'{svc}:{sid}',
                        'type': 'konsole',
                        'service': svc,
                        'session_id': sid,
                        'title': title if title else f'Konsole Session {sid}',
                        'command': fg_comm if fg_comm else 'shell',
                        'pid': int(fg_pid) if fg_pid.isdigit() else (int(sh_pid) if sh_pid.isdigit() else 0),
                        'cwd': cwd,
                        'tty': tty,
                        'preview': preview
                    })
            except Exception:
                pass
    except Exception:
        pass

    # When live GUI terminals (Konsole) exist, only return those! Avoid underlying pts duplicates.
    if terminals:
        return terminals

    # Fallback to standalone TTY/PTS processes only if no GUI terminal is open
    try:
        ps_out = subprocess.run(['ps', '-eo', 'pid,tty,comm,args'], capture_output=True, text=True, timeout=2)
        for line in ps_out.stdout.splitlines()[1:]:
            parts = line.strip().split(None, 3)
            if len(parts) >= 3:
                pid, tty, comm = parts[0], parts[1], parts[2]
                args = parts[3] if len(parts) > 3 else comm
                if tty.startswith('pts/') and tty not in seen_ttys and comm in ('bash', 'zsh', 'fish', 'sh', 'tmux', 'kitty', 'alacritty'):
                    seen_ttys.add(tty)
                    cwd = ''
                    try:
                        cwd = os.readlink(f'/proc/{pid}/cwd')
                    except Exception:
                        pass
                    terminals.append({
                        'id': f'pty:{tty}',
                        'type': 'pty',
                        'service': '',
                        'session_id': tty,
                        'title': f'{comm} on {tty}',
                        'command': comm,
                        'pid': int(pid) if pid.isdigit() else 0,
                        'cwd': cwd,
                        'tty': tty,
                        'preview': args
                    })
    except Exception:
        pass

    return terminals

def read_terminal(term_id, max_lines=35):
    if ':' in term_id and 'org.kde.konsole' in term_id:
        svc, sid = term_id.split(':', 1)
        spath = f'/Sessions/{sid}'
        t_res = subprocess.run(['qdbus6', svc, spath, 'org.kde.konsole.Session.title', '1'], capture_output=True, text=True, timeout=1)
        title = t_res.stdout.strip()
        txt_res = subprocess.run(['qdbus6', svc, spath, 'org.kde.konsole.Session.getAllDisplayedText'], capture_output=True, text=True, timeout=2)
        all_lines = txt_res.stdout.splitlines()
        total_count = len(all_lines)
        if len(all_lines) > max_lines:
            lines = all_lines[-max_lines:]
        else:
            lines = all_lines
        return {
            'status': 'ok',
            'id': term_id,
            'title': title,
            'text': '\n'.join(lines),
            'total_lines': total_count,
            'returned_lines': len(lines),
            'has_more': total_count > len(lines)
        }
    return {'status': 'error', 'message': 'Terminal not found or inaccessible'}

def write_terminal(term_id, text):
    if ':' in term_id and 'org.kde.konsole' in term_id:
        svc, sid = term_id.split(':', 1)
        spath = f'/Sessions/{sid}'
        subprocess.run(['qdbus6', svc, spath, 'org.kde.konsole.Session.sendText', text], capture_output=True, text=True, timeout=2)
        return {'status': 'ok'}
    return {'status': 'error', 'message': 'Terminal not found or inaccessible'}

def send_terminal_key(term_id, key_name):
    KEY_MAP = {
        'ctrl_c': '\x03',
        'ctrl_d': '\x04',
        'ctrl_z': '\x1a',
        'tab': '\t',
        'up': '\x1b[A',
        'down': '\x1b[B',
        'enter': '\n',
        'escape': '\x1b'
    }
    char = KEY_MAP.get(key_name.lower())
    if char:
        return write_terminal(term_id, char)
    return {'status': 'error', 'message': f'Unsupported key {key_name}'}

def spawn_new_terminal(workdir=''):
    try:
        q_res = subprocess.run(['qdbus6'], capture_output=True, text=True, timeout=1)
        konsole_svcs = [l.strip() for l in q_res.stdout.splitlines() if 'org.kde.konsole' in l]
        if konsole_svcs:
            svc = konsole_svcs[0]
            if workdir:
                subprocess.run(['qdbus6', svc, '/Windows/1', 'org.kde.konsole.Window.newSession', '', workdir], capture_output=True, text=True, timeout=2)
            else:
                subprocess.run(['qdbus6', svc, '/Windows/1', 'org.kde.konsole.Window.newSession'], capture_output=True, text=True, timeout=2)
            return {'status': 'ok', 'action': 'new_tab'}
        else:
            args = ['konsole']
            if workdir:
                args += ['--workdir', workdir]
            subprocess.Popen(args)
            return {'status': 'ok', 'action': 'new_window'}
    except Exception as e:
        return {'status': 'error', 'message': str(e)}


def start_clipboard_monitor(auth_mgr):
    def _monitor():
        global _last_pc_clipboard
        try:
            _last_pc_clipboard = get_kde_clipboard()
        except Exception:
            _last_pc_clipboard = ""

        while True:
            try:
                time.sleep(1.0)
                curr = get_kde_clipboard()
                if curr:
                    with _clipboard_lock:
                        if curr != _last_pc_clipboard and curr != _last_phone_clipboard:
                            _last_pc_clipboard = curr
                            # Broadcast clipboard content to connected phone(s)
                            auth_mgr.broadcast_ndjson(json.dumps({"event": "clipboard", "text": curr}) + "\n")
            except Exception:
                pass

    t = threading.Thread(target=_monitor, daemon=True)
    t.start()

def ping_pc():
    def _play_and_notify():
        try:
            for s in ['/usr/share/sounds/freedesktop/stereo/phone-incoming-call.oga', '/usr/share/sounds/freedesktop/stereo/alarm-clock-elapsed.oga']:
                if os.path.exists(s):
                    subprocess.run(['paplay', s], stderr=subprocess.DEVNULL)
                    break
        except Exception:
            pass
    threading.Thread(target=_play_and_notify, daemon=True).start()
    try:
        subprocess.Popen(['notify-send', '-i', 'phone', '-u', 'critical', '-a', 'PC Connect', '🔔 Phone Calling', 'Your paired phone is pinging your PC!'], stderr=subprocess.DEVNULL)
    except Exception:
        pass

# ==========================================
# Telephony / Call Event Management
# ==========================================

telephony_state = {
    "call_active": False,
    "paused_players": [],
    "last_state": "idle"
}
telephony_lock = threading.Lock()

def handle_telephony_call_state(state):
    global telephony_state
    state = (state or '').strip().lower()
    with telephony_lock:
        if state in ("ringing", "talking"):
            if not telephony_state["call_active"]:
                telephony_state["call_active"] = True
                players = get_mpris_players()
                playing = []
                for p in players:
                    try:
                        st = subprocess.check_output(['qdbus6', p, '/org/mpris/MediaPlayer2', 'org.mpris.MediaPlayer2.Player.PlaybackStatus'], stderr=subprocess.DEVNULL).decode().strip()
                        if st == "Playing":
                            playing.append(p)
                            subprocess.run(['qdbus6', p, '/org/mpris/MediaPlayer2', 'org.mpris.MediaPlayer2.Player.Pause'], stderr=subprocess.DEVNULL)
                    except Exception:
                        pass
                telephony_state["paused_players"] = playing
                if playing:
                    try:
                        subprocess.Popen(['notify-send', '-i', 'call-start', '-a', 'PC Connect', '📞 Incoming Call', f'Paused playback on {len(playing)} media player(s)'], stderr=subprocess.DEVNULL)
                    except Exception:
                        pass
                elif state == "ringing":
                    try:
                        subprocess.Popen(['notify-send', '-i', 'call-start', '-a', 'PC Connect', '📞 Incoming Call', 'Phone is ringing'], stderr=subprocess.DEVNULL)
                    except Exception:
                        pass
        elif state == "idle":
            if telephony_state["call_active"]:
                resumed = 0
                for p in telephony_state["paused_players"]:
                    try:
                        subprocess.run(['qdbus6', p, '/org/mpris/MediaPlayer2', 'org.mpris.MediaPlayer2.Player.Play'], stderr=subprocess.DEVNULL)
                        resumed += 1
                    except Exception:
                        pass
                if resumed > 0:
                    try:
                        subprocess.Popen(['notify-send', '-i', 'audio-volume-medium', '-a', 'PC Connect', '📞 Call Ended', 'Resumed media playback'], stderr=subprocess.DEVNULL)
                    except Exception:
                        pass
                telephony_state["call_active"] = False
                telephony_state["paused_players"] = []
        telephony_state["last_state"] = state

def get_phone_target():
    ip = active_phone_state.get('ip')
    port = active_phone_state.get('port', 1761)
    token = active_phone_state.get('auth_token')
    cfg = load_config()
    if not ip or ip in ('127.0.0.1', 'localhost', '::1') or not token:
        for t, client in cfg.get('paired_clients', {}).items():
            client_ip = client.get('ip')
            if client_ip and client_ip not in ('127.0.0.1', 'localhost', '::1'):
                ip = client_ip
                token = t
                active_phone_state['ip'] = ip
                active_phone_state['auth_token'] = token
                active_phone_state['client_name'] = client.get('client_name', 'Android Phone')
                break
    return ip, port, token

def ring_phone(phone_ip=None, port=1761):
    # 1. Broadcast via NDJSON stream
    auth_mgr.broadcast_ndjson(json.dumps({"event": "ring", "title": "Find My Phone", "time": int(time.time())}) + "\n")
    # 2. Direct HTTP to phone if IP is known
    target_ip, target_port, target_token = get_phone_target()
    target_ip = phone_ip or target_ip
    target_port = port or target_port
    if target_ip:
        def _direct_ring():
            try:
                headers = {'Content-Type': 'application/json'}
                if target_token:
                    headers['Authorization'] = f'Bearer {target_token}'
                req = urllib.request.Request(f"http://{target_ip}:{target_port}/api/ring", data=b'{}', headers=headers, method='POST')
                urllib.request.urlopen(req, timeout=3)
            except Exception:
                pass
        threading.Thread(target=_direct_ring, daemon=True).start()

def unring_phone(phone_ip=None, port=1761):
    # 1. Broadcast unring via NDJSON stream
    auth_mgr.broadcast_ndjson(json.dumps({"event": "unring", "title": "Stop Alarm", "time": int(time.time())}) + "\n")
    # 2. Direct HTTP to phone to silence
    target_ip, target_port, target_token = get_phone_target()
    target_ip = phone_ip or target_ip
    target_port = port or target_port
    if target_ip:
        def _direct_unring():
            try:
                headers = {'Content-Type': 'application/json'}
                if target_token:
                    headers['Authorization'] = f'Bearer {target_token}'
                req = urllib.request.Request(f"http://{target_ip}:{target_port}/api/unring", data=b'{}', headers=headers, method='POST')
                urllib.request.urlopen(req, timeout=3)
            except Exception:
                pass
        threading.Thread(target=_direct_unring, daemon=True).start()

def notify_file_received(filename, filepath):
    try:
        for sound in ['/usr/share/sounds/freedesktop/stereo/complete.oga', '/usr/share/sounds/freedesktop/stereo/message.oga']:
            if os.path.exists(sound):
                subprocess.Popen(['paplay', sound], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                break
    except Exception:
        pass

    downloads_dir = os.path.expanduser('~/Downloads')
    cmd = [
        'notify-send',
        '-i', 'document-save',
        '-a', 'PC Connect',
        '📁 File Received from Phone',
        f'{filename}\nSaved in ~/Downloads',
        '-A', 'open=Open File',
        '-A', 'folder=Open Downloads'
    ]
    try:
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True)
        out, _ = p.communicate(timeout=45)
        chosen = out.strip()
        if chosen == 'open':
            subprocess.Popen(['xdg-open', filepath])
        elif chosen == 'folder':
            subprocess.Popen(['xdg-open', downloads_dir])
    except Exception:
        pass

# ==========================================
# Virtual Mouse & Input Controller (/dev/uinput)
# ==========================================

UI_SET_EVBIT = 0x40045564
UI_SET_KEYBIT = 0x40045565
UI_SET_RELBIT = 0x40045566
UI_DEV_CREATE = 0x5501
UI_DEV_DESTROY = 0x5502

EV_SYN = 0x00
EV_KEY = 0x01
EV_REL = 0x02

REL_X = 0x00
REL_Y = 0x01
REL_WHEEL = 0x08
REL_HWHEEL = 0x06

BTN_LEFT = 0x110
BTN_RIGHT = 0x111
BTN_MIDDLE = 0x112

KEY_MAP = {
    'esc': 1, 'escape': 1,
    '1': 2, '2': 3, '3': 4, '4': 5, '5': 6, '6': 7, '7': 8, '8': 9, '9': 10, '0': 11,
    '-': 12, '=': 13, 'backspace': 14, 'bksp': 14,
    'tab': 15,
    'q': 16, 'w': 17, 'e': 18, 'r': 19, 't': 20, 'y': 21, 'u': 22, 'i': 23, 'o': 24, 'p': 25,
    '[': 26, ']': 27, 'enter': 28, 'return': 28,
    'ctrl': 29, 'leftctrl': 29,
    'a': 30, 's': 31, 'd': 32, 'f': 33, 'g': 34, 'h': 35, 'j': 36, 'k': 37, 'l': 38,
    ';': 39, "'": 40, '`': 41,
    'shift': 42, 'leftshift': 42,
    '\\': 43,
    'z': 44, 'x': 45, 'c': 46, 'v': 47, 'b': 48, 'n': 49, 'm': 50,
    ',': 51, '.': 52, '/': 53,
    'rightshift': 54,
    'alt': 56, 'leftalt': 56,
    'space': 57, ' ': 57,
    'capslock': 58,
    'f1': 59, 'f2': 60, 'f3': 61, 'f4': 62, 'f5': 63, 'f6': 64,
    'f7': 65, 'f8': 66, 'f9': 67, 'f10': 68, 'f11': 87, 'f12': 88,
    'home': 102, 'up': 103, 'pageup': 104, 'prev': 104,
    'left': 105, 'right': 106,
    'end': 107, 'down': 108, 'pagedown': 109, 'next': 109,
    'insert': 110, 'delete': 111, 'del': 111,
    'super': 125, 'win': 125, 'meta': 125,
}

SHIFT_MAP = {
    '!': (2, True), '@': (3, True), '#': (4, True), '$': (5, True), '%': (6, True),
    '^': (7, True), '&': (8, True), '*': (9, True), '(': (10, True), ')': (11, True),
    '_': (12, True), '+': (13, True), '{': (26, True), '}': (27, True), '|': (43, True),
    ':': (39, True), '"': (40, True), '~': (41, True), '<': (51, True), '>': (52, True),
    '?': (53, True)
}

class VirtualMouse:
    def __init__(self):
        self.lock = threading.Lock()
        self.fd = None
        try:
            self.fd = os.open('/dev/uinput', os.O_WRONLY | os.O_NONBLOCK)
            fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_KEY)
            for btn in (BTN_LEFT, BTN_RIGHT, BTN_MIDDLE):
                fcntl.ioctl(self.fd, UI_SET_KEYBIT, btn)
            # Register full keyboard keycodes 1..248
            for keycode in range(1, 249):
                try:
                    fcntl.ioctl(self.fd, UI_SET_KEYBIT, keycode)
                except Exception:
                    pass

            fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_REL)
            for rel in (REL_X, REL_Y, REL_WHEEL, REL_HWHEEL):
                fcntl.ioctl(self.fd, UI_SET_RELBIT, rel)

            name = b"PC Connect Virtual Input".ljust(80, b'\x00')
            input_id = struct.pack('HHHH', 0x03, 0x1234, 0x5678, 1)
            user_dev = name + input_id + struct.pack('I', 0) + b'\x00' * (64 * 4 * 4)
            os.write(self.fd, user_dev)
            fcntl.ioctl(self.fd, UI_DEV_CREATE)
            sys.stderr.write("  Virtual Input Keyboard & Mouse (/dev/uinput) initialized successfully\n")
            sys.stderr.flush()
        except Exception as e:
            sys.stderr.write(f"Warning: Cannot initialize /dev/uinput: {e}\n")
            self.fd = None

    def emit(self, ev_type, ev_code, val):
        if not self.fd:
            return
        now = time.time()
        sec = int(now)
        usec = int((now - sec) * 1000000)
        data = struct.pack('qqHHi', sec, usec, ev_type, ev_code, int(val))
        os.write(self.fd, data)

    def move(self, dx, dy):
        if not self.fd:
            return
        with self.lock:
            try:
                idx = int(round(float(dx)))
                idy = int(round(float(dy)))
            except Exception:
                return
            if idx != 0:
                self.emit(EV_REL, REL_X, idx)
            if idy != 0:
                self.emit(EV_REL, REL_Y, idy)
            if idx != 0 or idy != 0:
                self.emit(EV_SYN, 0, 0)

    def click(self, button="left"):
        if not self.fd:
            return
        with self.lock:
            code = BTN_RIGHT if button == "right" else (BTN_MIDDLE if button == "middle" else BTN_LEFT)
            self.emit(EV_KEY, code, 1)
            self.emit(EV_SYN, 0, 0)
            self.emit(EV_KEY, code, 0)
            self.emit(EV_SYN, 0, 0)

    def mouse_down(self, button="left"):
        if not self.fd:
            return
        with self.lock:
            code = BTN_RIGHT if button == "right" else (BTN_MIDDLE if button == "middle" else BTN_LEFT)
            self.emit(EV_KEY, code, 1)
            self.emit(EV_SYN, 0, 0)

    def mouse_up(self, button="left"):
        if not self.fd:
            return
        with self.lock:
            code = BTN_RIGHT if button == "right" else (BTN_MIDDLE if button == "middle" else BTN_LEFT)
            self.emit(EV_KEY, code, 0)
            self.emit(EV_SYN, 0, 0)

    def scroll(self, dy, dx=0):
        if not self.fd:
            return
        with self.lock:
            if dy != 0:
                self.emit(EV_REL, REL_WHEEL, dy)
            if dx != 0:
                self.emit(EV_REL, REL_HWHEEL, dx)
            self.emit(EV_SYN, 0, 0)

    def press_key(self, key_name):
        if not self.fd:
            return
        key_str = str(key_name).strip().lower()
        if not key_str:
            return

        with self.lock:
            if '+' in key_str:
                parts = [p.strip() for p in key_str.split('+')]
                mod_codes = []
                for p in parts[:-1]:
                    if p in ('ctrl', 'control'): mod_codes.append(29)
                    elif p in ('alt',): mod_codes.append(56)
                    elif p in ('shift',): mod_codes.append(42)
                    elif p in ('super', 'win', 'meta'): mod_codes.append(125)
                last_p = parts[-1]
                main_code = KEY_MAP.get(last_p)
                if main_code:
                    for m in mod_codes:
                        self.emit(EV_KEY, m, 1)
                    self.emit(EV_SYN, 0, 0)
                    self.emit(EV_KEY, main_code, 1)
                    self.emit(EV_SYN, 0, 0)
                    self.emit(EV_KEY, main_code, 0)
                    self.emit(EV_SYN, 0, 0)
                    for m in reversed(mod_codes):
                        self.emit(EV_KEY, m, 0)
                    self.emit(EV_SYN, 0, 0)
                    return

            code = KEY_MAP.get(key_str)
            if code:
                self.emit(EV_KEY, code, 1)
                self.emit(EV_SYN, 0, 0)
                self.emit(EV_KEY, code, 0)
                self.emit(EV_SYN, 0, 0)

    def type_text(self, text):
        if not self.fd or not text:
            return
        with self.lock:
            for ch in str(text):
                if ch == '\n':
                    self.emit(EV_KEY, 28, 1)
                    self.emit(EV_SYN, 0, 0)
                    self.emit(EV_KEY, 28, 0)
                    self.emit(EV_SYN, 0, 0)
                elif ch == '\t':
                    self.emit(EV_KEY, 15, 1)
                    self.emit(EV_SYN, 0, 0)
                    self.emit(EV_KEY, 15, 0)
                    self.emit(EV_SYN, 0, 0)
                elif ch == '\b':
                    self.emit(EV_KEY, 14, 1)
                    self.emit(EV_SYN, 0, 0)
                    self.emit(EV_KEY, 14, 0)
                    self.emit(EV_SYN, 0, 0)
                elif ch in SHIFT_MAP:
                    code, _ = SHIFT_MAP[ch]
                    self.emit(EV_KEY, 42, 1)
                    self.emit(EV_SYN, 0, 0)
                    self.emit(EV_KEY, code, 1)
                    self.emit(EV_SYN, 0, 0)
                    self.emit(EV_KEY, code, 0)
                    self.emit(EV_SYN, 0, 0)
                    self.emit(EV_KEY, 42, 0)
                    self.emit(EV_SYN, 0, 0)
                elif ch.isupper():
                    lower_code = KEY_MAP.get(ch.lower())
                    if lower_code:
                        self.emit(EV_KEY, 42, 1)
                        self.emit(EV_SYN, 0, 0)
                        self.emit(EV_KEY, lower_code, 1)
                        self.emit(EV_SYN, 0, 0)
                        self.emit(EV_KEY, lower_code, 0)
                        self.emit(EV_SYN, 0, 0)
                        self.emit(EV_KEY, 42, 0)
                        self.emit(EV_SYN, 0, 0)
                elif ch.lower() in KEY_MAP:
                    code = KEY_MAP[ch.lower()]
                    self.emit(EV_KEY, code, 1)
                    self.emit(EV_SYN, 0, 0)
                    self.emit(EV_KEY, code, 0)
                    self.emit(EV_SYN, 0, 0)
                time.sleep(0.005)

    def close(self):
        if self.fd:
            try:
                fcntl.ioctl(self.fd, UI_DEV_DESTROY)
                os.close(self.fd)
            except Exception:
                pass
            self.fd = None

virtual_mouse = VirtualMouse()

def trigger_laser_overlay(data):
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        if isinstance(data, dict):
            msg = json.dumps(data).encode('utf-8')
        else:
            msg = json.dumps({"laser": bool(data)}).encode('utf-8')
        sock.sendto(msg, ('127.0.0.1', 1763))
        sock.close()
    except Exception:
        pass

def start_udp_mouse_listener(port=1762):
    last_cfg_check = [0]
    cached_tokens = [set()]

    def is_valid_token(tok):
        if not tok or not isinstance(tok, str):
            return False
        now = time.time()
        if now - last_cfg_check[0] > 3:
            try:
                cfg = load_config()
                cached_tokens[0] = set(cfg.get('paired_clients', {}).keys())
                last_cfg_check[0] = now
            except Exception:
                pass
        return tok in cached_tokens[0]

    def mouse_loop():
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(('0.0.0.0', port))
            sys.stderr.write(f"  UDP Mouse Trackpad Listener active on port {port}\n")
            sys.stderr.flush()
            while True:
                data, addr = sock.recvfrom(2048)
                try:
                    payload = json.loads(data.decode('utf-8'))
                    tok = payload.get('token')
                    if not is_valid_token(tok):
                        continue
                    mtype = payload.get('type', '')
                    if mtype == 'move':
                        virtual_mouse.move(payload.get('dx', 0), payload.get('dy', 0))
                    elif mtype == 'pointer':
                        trigger_laser_overlay(payload)
                    elif mtype == 'laser_state':
                        trigger_laser_overlay(payload)
                    elif mtype == 'key':
                        virtual_mouse.press_key(payload.get('key', ''))
                    elif mtype == 'text':
                        virtual_mouse.type_text(payload.get('text', ''))
                    elif mtype == 'click':
                        virtual_mouse.click(payload.get('button', 'left'))
                    elif mtype == 'down':
                        virtual_mouse.mouse_down(payload.get('button', 'left'))
                    elif mtype == 'up':
                        virtual_mouse.mouse_up(payload.get('button', 'left'))
                    elif mtype == 'scroll':
                        virtual_mouse.scroll(payload.get('dy', 0), payload.get('dx', 0))
                except Exception:
                    pass
        except Exception as e:
            sys.stderr.write(f"UDP mouse listener error: {e}\n")

    t = threading.Thread(target=mouse_loop, daemon=True)
    t.start()

# ==========================================
# Camera Streaming Helpers
# ==========================================

def get_camera_devices():
    devices = []
    v4l_dir = '/sys/class/video4linux'
    if os.path.exists(v4l_dir):
        for entry in sorted(os.listdir(v4l_dir)):
            dev_path = f"/dev/{entry}"
            if not os.path.exists(dev_path):
                continue
            name_file = os.path.join(v4l_dir, entry, 'name')
            name = entry
            if os.path.exists(name_file):
                try:
                    with open(name_file, 'r', encoding='utf-8') as f:
                        name = f.read().strip()
                except Exception:
                    pass
            index_file = os.path.join(v4l_dir, entry, 'index')
            idx = 0
            if os.path.exists(index_file):
                try:
                    with open(index_file, 'r') as f:
                        idx = int(f.read().strip())
                except Exception:
                    pass
            if idx == 0:
                devices.append({"device": dev_path, "name": name})
    if not devices and os.path.exists('/dev/video0'):
        devices.append({"device": "/dev/video0", "name": "Default Camera"})
    return devices

def get_best_camera(preferred=''):
    if preferred and os.path.exists(preferred):
        return preferred
    devices = get_camera_devices()
    for d in devices:
        if 'hd webcam' in d['name'].lower() or 'webcam' in d['name'].lower() or 'camera' in d['name'].lower():
            if 'iriun' not in d['name'].lower():
                return d['device']
    if devices:
        return devices[0]['device']
    return '/dev/video1' if os.path.exists('/dev/video1') else '/dev/video0'

# ==========================================
# Auth Manager
# ==========================================

class AuthManager:
    def __init__(self):
        self.lock = threading.RLock()
        self.current_session = None
        self.sse_clients = []
        self.ndjson_clients = []
        self.pending_pairings = {}

    def add_sse_client(self, client_queue):
        with self.lock:
            self.sse_clients.append(client_queue)

    def remove_sse_client(self, client_queue):
        with self.lock:
            if client_queue in self.sse_clients:
                self.sse_clients.remove(client_queue)

    def add_ndjson_client(self, client_queue):
        with self.lock:
            self.ndjson_clients.append(client_queue)

    def remove_ndjson_client(self, client_queue):
        with self.lock:
            if client_queue in self.ndjson_clients:
                self.ndjson_clients.remove(client_queue)

    def broadcast_sse(self, event_name, data):
        with self.lock:
            dead_clients = []
            msg = f"event: {event_name}\ndata: {json.dumps(data)}\n\n"
            for q in self.sse_clients:
                try:
                    q.put(msg)
                except Exception:
                    dead_clients.append(q)
            for d in dead_clients:
                if d in self.sse_clients:
                    self.sse_clients.remove(d)

    def broadcast_ndjson(self, msg_str):
        with self.lock:
            dead_clients = []
            for q in self.ndjson_clients:
                try:
                    q.put(msg_str)
                except Exception:
                    dead_clients.append(q)
            for d in dead_clients:
                if d in self.ndjson_clients:
                    self.ndjson_clients.remove(d)

    def build_app_notification(self, session, topic="login"):
        local_ip = get_local_ip()
        cfg = load_config()
        port = cfg.get('web_port', 1760)
        sid = session["session_id"]
        user = session["user"]
        hostname = session["hostname"]
        
        return {
            "id": sid,
            "time": int(session.get("created_at", time.time())),
            "event": "message",
            "topic": topic,
            "title": "🔒 PC Unlock Request",
            "message": f"Login requested for {user} on {hostname}. Tap Approve to unlock.",
            "priority": 5,
            "tags": ["warning", "lock"],
            "actions": [
                {
                    "id": f"act_approve_{sid[:8]}",
                    "action": "http",
                    "label": "Approve",
                    "url": f"http://{local_ip}:{port}/api/approve_current",
                    "method": "POST",
                    "clear": True
                },
                {
                    "id": f"act_deny_{sid[:8]}",
                    "action": "http",
                    "label": "Deny",
                    "url": f"http://{local_ip}:{port}/api/deny_current",
                    "method": "POST",
                    "clear": True
                }
            ]
        }

    def create_request(self, user, duration=35):
        now = time.time()
        with self.lock:
            if self.current_session and self.current_session.get("status") == "pending":
                if now < self.current_session.get("expires_at", 0) - 2:
                    # Reuse active valid session so rapid PAM invocations attach to the same challenge
                    return self.current_session

            session_id = str(uuid.uuid4())
            hostname = socket.gethostname()
            cfg = load_config()

            self.current_session = {
                "session_id": session_id,
                "user": user,
                "hostname": hostname,
                "created_at": now,
                "duration": duration,
                "expires_at": now + duration,
                "status": "pending"
            }
        
        self.broadcast_sse("auth_request", self.current_session)
        app_notification = self.build_app_notification(self.current_session, topic="login")
        self.broadcast_ndjson(json.dumps(app_notification) + "\n")
        return self.current_session

    def approve(self, session_id):
        with self.lock:
            if self.current_session and self.current_session["session_id"] == session_id:
                if time.time() <= self.current_session["expires_at"]:
                    self.current_session["status"] = "approved"
                    self.broadcast_sse("auth_cancelled", {"reason": "approved"})
                    self.broadcast_ndjson(json.dumps({"event": "cancelled", "reason": "approved", "id": session_id}) + "\n")
                    return True
        return False

    def approve_current(self):
        with self.lock:
            if self.current_session and self.current_session["status"] == "pending":
                if time.time() <= self.current_session["expires_at"]:
                    self.current_session["status"] = "approved"
                    sid = self.current_session.get("session_id", "")
                    self.broadcast_sse("auth_cancelled", {"reason": "approved"})
                    self.broadcast_ndjson(json.dumps({"event": "cancelled", "reason": "approved", "id": sid}) + "\n")
                    return True
        return False

    def deny(self, session_id):
        with self.lock:
            if self.current_session and self.current_session["session_id"] == session_id:
                self.current_session["status"] = "denied"
                self.broadcast_sse("auth_cancelled", {"reason": "denied"})
                self.broadcast_ndjson(json.dumps({"event": "cancelled", "reason": "denied", "id": session_id}) + "\n")
                return True
        return False

    def deny_current(self):
        with self.lock:
            if self.current_session and self.current_session["status"] == "pending":
                self.current_session["status"] = "denied"
                sid = self.current_session.get("session_id", "")
                self.broadcast_sse("auth_cancelled", {"reason": "denied"})
                self.broadcast_ndjson(json.dumps({"event": "cancelled", "reason": "denied", "id": sid}) + "\n")
                return True
        return False

    def wait_for_result(self, session_id, timeout=35):
        start = time.time()
        while time.time() - start < timeout:
            with self.lock:
                if not self.current_session or self.current_session["session_id"] != session_id:
                    return "expired"
                if self.current_session["status"] != "pending":
                    return self.current_session["status"]
            time.sleep(0.2)
        with self.lock:
            if self.current_session and self.current_session["session_id"] == session_id:
                self.current_session["status"] = "timeout"
        self.broadcast_sse("auth_cancelled", {"reason": "timeout"})
        self.broadcast_ndjson(json.dumps({"event": "cancelled", "reason": "timeout", "id": session_id}) + "\n")
        return "timeout"

auth_mgr = AuthManager()

# ==========================================
# High-Speed Internet Tunnel Manager (Ngrok & Cloudflare)
# ==========================================

class TunnelManager:
    def __init__(self, port=1760):
        self.port = port
        self.tunnel_url = None
        self.process = None
        self.lock = threading.Lock()
        self.running = False
        self._thread = None
        self.provider = "none"

    def _find_ngrok(self):
        p = shutil.which('ngrok')
        if p and os.path.isfile(p):
            return p
        cand = os.path.expanduser('~/.local/bin/ngrok')
        if os.path.isfile(cand) and os.access(cand, os.X_OK):
            return cand
        return None

    def _has_ngrok_token(self):
        cfg_path = os.path.expanduser('~/.config/ngrok/ngrok.yml')
        if os.path.exists(cfg_path):
            try:
                with open(cfg_path, 'r') as f:
                    content = f.read()
                    if 'authtoken:' in content and 'authtoken: ""' not in content:
                        return True
            except Exception:
                pass
        return False

    def _find_cloudflared(self):
        p = shutil.which('cloudflared')
        if p and os.path.isfile(p):
            return p
        candidates = [
            os.path.expanduser('~/.local/bin/cloudflared'),
            '/usr/local/bin/cloudflared',
            '/usr/bin/cloudflared',
            os.path.join(BASE_DIR, 'cloudflared')
        ]
        for c in candidates:
            if os.path.isfile(c) and os.access(c, os.X_OK):
                return c
        return None

    def get_provider(self):
        with self.lock:
            return self.provider

    def start(self):
        cfg = load_config()
        if not cfg.get("enable_internet_tunnel", True):
            sys.stderr.write("🌐 Internet tunnel is disabled in config.json\n")
            return

        if self.running and self._thread and self._thread.is_alive():
            return
        self.running = True
        self._thread = threading.Thread(target=self._run_supervisor_loop, daemon=True)
        self._thread.start()

    def _run_supervisor_loop(self):
        while self.running:
            cfg = load_config()
            pref = cfg.get("tunnel_provider", "both").lower()
            if pref == "none":
                time.sleep(5)
                continue

            ngrok_bin = self._find_ngrok()
            has_ngrok = bool(ngrok_bin and self._has_ngrok_token())
            cf_bin = self._find_cloudflared()

            if pref in ("both", "ngrok") and has_ngrok:
                with self.lock:
                    self.provider = "ngrok"
                success = self._run_ngrok_loop(ngrok_bin)
                if not success and pref == "both" and cf_bin and self.running:
                    sys.stderr.write("⚠️ Ngrok failed or exited quickly; falling back to Cloudflare...\n")
                    with self.lock:
                        self.provider = "cloudflare"
                    self._run_cloudflare_loop(cf_bin)
            elif pref in ("both", "cloudflare") and cf_bin:
                with self.lock:
                    self.provider = "cloudflare"
                self._run_cloudflare_loop(cf_bin)
            else:
                sys.stderr.write("⚠️ No tunnel provider found (Ngrok or Cloudflare); retrying in 10s...\n")
                time.sleep(10)

    def _run_ngrok_loop(self, bin_path):
        import re
        cfg = load_config()
        static_url = cfg.get("ngrok_url")
        start_time = time.time()
        had_connection = False
        while self.running:
            sys.stderr.write(f"🌐 Starting Ngrok High-Speed Tunnel on port {self.port}...\n")
            cmd = [bin_path, 'http', str(self.port), '--log=stdout']
            if static_url:
                cmd.extend(['--url', static_url])
            try:
                self.process = subprocess.Popen(
                    cmd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    bufsize=1
                )
                for line in iter(self.process.stdout.readline, ''):
                    if not self.running:
                        break
                    line_clean = line.strip()
                    m = re.search(r'url=(https://[^\s]+)', line_clean)
                    if m:
                        had_connection = True
                        new_url = m.group(1).rstrip('/')
                        with self.lock:
                            changed = (self.tunnel_url != new_url)
                            self.tunnel_url = new_url
                        sys.stderr.write(f"\n==================================================\n")
                        sys.stderr.write(f"🌐 Ngrok Internet Tunnel ONLINE (Provider: ngrok):\n")
                        sys.stderr.write(f"   👉 {new_url}\n")
                        sys.stderr.write(f"==================================================\n\n")
                        sys.stderr.flush()
                        if changed:
                            try:
                                auth_mgr.broadcast_ndjson(json.dumps({
                                    "event": "tunnel_update",
                                    "internet_url": new_url,
                                    "provider": "ngrok"
                                }) + "\n")
                            except Exception:
                                pass

                self.process.wait()
            except Exception as e:
                sys.stderr.write(f"Ngrok process error: {e}\n")

            if not self.running:
                break
            with self.lock:
                self.tunnel_url = None
            if not had_connection and (time.time() - start_time) < 15:
                # Exited quickly without connection -> allow supervisor fallback
                return False
            sys.stderr.write("🌐 Ngrok tunnel disconnected, restarting in 5s...\n")
            time.sleep(5)
        return had_connection

    def _run_cloudflare_loop(self, bin_path):
        import re
        url_regex = re.compile(r'(https://[a-zA-Z0-9-]+\.trycloudflare\.com)')
        while self.running:
            sys.stderr.write(f"🌐 Starting Cloudflare Quick Tunnel on port {self.port}...\n")
            cmd = [bin_path, 'tunnel', '--url', f'http://127.0.0.1:{self.port}']
            try:
                self.process = subprocess.Popen(
                    cmd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    bufsize=1
                )
                for line in iter(self.process.stdout.readline, ''):
                    if not self.running:
                        break
                    line_clean = line.strip()
                    m = url_regex.search(line_clean)
                    if m:
                        new_url = m.group(1)
                        with self.lock:
                            changed = (self.tunnel_url != new_url)
                            self.tunnel_url = new_url
                        sys.stderr.write(f"\n==================================================\n")
                        sys.stderr.write(f"🌐 Cloudflare Internet Tunnel ONLINE (Provider: cloudflare):\n")
                        sys.stderr.write(f"   👉 {new_url}\n")
                        sys.stderr.write(f"==================================================\n\n")
                        sys.stderr.flush()
                        if changed:
                            try:
                                auth_mgr.broadcast_ndjson(json.dumps({
                                    "event": "tunnel_update",
                                    "internet_url": new_url,
                                    "provider": "cloudflare"
                                }) + "\n")
                            except Exception:
                                pass

                self.process.wait()
            except Exception as e:
                sys.stderr.write(f"Tunnel process error: {e}\n")

            if not self.running:
                break
            with self.lock:
                self.tunnel_url = None
            sys.stderr.write("🌐 Cloudflare tunnel disconnected, restarting in 5s...\n")
            time.sleep(5)

    def get_url(self):
        with self.lock:
            return self.tunnel_url

    def stop(self):
        self.running = False
        with self.lock:
            self.tunnel_url = None
        if self.process:
            try:
                self.process.terminate()
                self.process.wait(timeout=3)
            except Exception:
                try:
                    self.process.kill()
                except Exception:
                    pass
            self.process = None

tunnel_mgr = TunnelManager(port=1760)

class AuthenticatorHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        sys.stderr.write(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {self.client_address[0]} {format % args}\n")
        sys.stderr.flush()

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS, HEAD')
        self.send_header('Access-Control-Allow-Headers', '*')
        self.end_headers()

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

        # 1. Health & Config
        if path in ('/v1/health', '/health'):
            self.send_json({"healthy": True, "schema": 1})
            return
        elif path in ('/config', '/v1/config'):
            cfg = load_config()
            port = cfg.get('web_port', 1760)
            local_ip = get_local_ip()
            self.send_json({
                "base_url": f"http://{local_ip}:{port}",
                "local_url": f"http://{local_ip}:{port}",
                "internet_url": tunnel_mgr.get_url(),
                "app_root": "/"
            })
            return
        elif path.endswith('/auth'):
            topic_name = path.strip('/').split('/')[0] if '/' in path.strip('/') else "login"
            self.send_json({"topic": topic_name, "read": True, "write": True})
            return

        # 2. APK Download
        elif path in ('/apk', '/PCAuthenticator.apk', '/authenticator.apk', '/download'):
            self.serve_apk()
            return

        # 3. Static Files
        elif path in ('/', '/index.html'):
            self.serve_file(os.path.join(STATIC_DIR, 'index.html'), 'text/html; charset=utf-8')
            return
        elif path == '/browse':
            browse_file = os.path.join(STATIC_DIR, 'browse.html')
            if os.path.exists(browse_file):
                self.serve_file(browse_file, 'text/html; charset=utf-8')
            else:
                self.send_error(404, "Browse File Not Found")
            return
        elif path == '/manifest.json':
            self.serve_file(os.path.join(STATIC_DIR, 'manifest.json'), 'application/manifest+json')
            return
        elif path == '/sw.js':
            self.serve_file(os.path.join(STATIC_DIR, 'sw.js'), 'application/javascript')
            return
        elif path in ('/icon-192.png', '/icon-512.png'):
            fn = path.strip('/')
            self.serve_file(os.path.join(STATIC_DIR, fn), 'image/png')
            return

        # 4. Core APIs
        elif path == '/api/info':
            cfg = load_config()
            port = cfg.get('web_port', 1760)
            local_ip = get_local_ip()
            self.send_json({
                "hostname": socket.gethostname(),
                "device_id": get_laptop_id(),
                "status": "ready",
                "smart_dual_mode": True,
                "offline_apk_ready": True,
                "paired_clients_count": len(cfg.get('paired_clients', {})),
                "local_url": f"http://{local_ip}:{port}",
                "internet_url": tunnel_mgr.get_url(),
                "tunnel_provider": tunnel_mgr.get_provider()
            })
            return
        elif path == '/api/events':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            self.handle_sse()
            return
        elif path == '/api/wait_auth':
            is_local = (self.client_address[0] in ('127.0.0.1', '::1', 'localhost')) and not is_tunnel_request(self)
            if not is_local:
                cfg = load_config()
                client_info, token = authenticate_client(self, cfg)
                if not client_info:
                    self.send_json({"error": "unauthorized"}, status=401)
                    return
            session_id = qs.get('session_id', [None])[0]
            if not session_id:
                self.send_json({"error": "missing session_id"}, status=400)
                return
            result = auth_mgr.wait_for_result(session_id)
            self.send_json({"status": result})
            return
        elif path == '/api/current_challenge':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized", "message": "Pairing required"}, status=401)
                return
            with auth_mgr.lock:
                if auth_mgr.current_session and auth_mgr.current_session["status"] == "pending":
                    now = time.time()
                    if now <= auth_mgr.current_session["expires_at"]:
                        remaining = int(auth_mgr.current_session["expires_at"] - now)
                        session_copy = dict(auth_mgr.current_session)
                        session_copy["id"] = session_copy.get("session_id")
                        session_copy["remaining_seconds"] = max(1, remaining)
                        self.send_json({"has_challenge": True, "challenge": session_copy})
                        return
            self.send_json({"has_challenge": False})
            return

        # 5. KDE Connect APIs
        elif path == '/api/phone/status':
            cfg = load_config()
            is_connected = (time.time() - active_phone_state["last_seen"] < 90) or bool(auth_mgr.ndjson_clients)
            # Fallback to config for phone IP if not seen recently
            if not active_phone_state['ip']:
                for client in cfg.get('paired_clients', {}).values():
                    if client.get('ip') and client.get('ip') != '127.0.0.1':
                        active_phone_state['ip'] = client['ip']
                        active_phone_state['client_name'] = client.get('client_name', 'Android Phone')
                        break

            # Deduplicate paired devices by unique client_id - NEVER expose auth token!
            devices_by_id = {}
            for tok, client in cfg.get('paired_clients', {}).items():
                cid = client.get('client_id') or ("id_" + hashlib.sha256(tok.encode()).hexdigest()[:12])
                if cid == "phone-auto-test" and len(cfg.get('paired_clients', {})) > 1:
                    continue
                is_active = (active_phone_state.get('auth_token') == tok) or (active_phone_state.get('ip') == client.get('ip'))
                devices_by_id[cid] = {
                    "client_id": cid,
                    "client_name": client.get('client_name', 'Android Phone'),
                    "ip": client.get('ip'),
                    "connected": bool(is_active and is_connected)
                }
            device_list = list(devices_by_id.values())

            self.send_json({
                "connected": is_connected,
                "phone": {
                    "client_name": active_phone_state.get("client_name", "Android Phone"),
                    "ip": active_phone_state.get("ip"),
                    "battery_level": active_phone_state.get("battery_level"),
                    "last_seen": active_phone_state.get("last_seen", 0)
                },
                "clients_count": len(device_list),
                "devices": device_list
            })
            return

        elif path == '/api/devices':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            is_connected = (time.time() - active_phone_state["last_seen"] < 90) or bool(auth_mgr.ndjson_clients)
            devices_by_id = {}
            for tok, client in cfg.get('paired_clients', {}).items():
                cid = client.get('client_id') or ("id_" + hashlib.sha256(tok.encode()).hexdigest()[:12])
                if cid == "phone-auto-test" and len(cfg.get('paired_clients', {})) > 1:
                    continue
                is_active = (active_phone_state.get('auth_token') == tok) or (active_phone_state.get('ip') == client.get('ip'))
                devices_by_id[cid] = {
                    "client_id": cid,
                    "client_name": client.get('client_name', 'Android Phone'),
                    "ip": client.get('ip'),
                    "connected": bool(is_active and is_connected)
                }
            self.send_json({"devices": list(devices_by_id.values())})
            return

        elif path == '/api/clipboard':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            self.send_json({"status": "ok", "text": get_kde_clipboard()})
            return

        elif path == '/api/media/status':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            self.send_json(get_mpris_status())
            return

        elif path == '/api/pc/status':
            is_local = (self.client_address[0] in ('127.0.0.1', '::1', 'localhost')) and not is_tunnel_request(self)
            if not is_local:
                cfg = load_config()
                client_info, token = authenticate_client(self, cfg)
                if not client_info:
                    self.send_json({"error": "unauthorized"}, status=401)
                    return
            self.send_json(get_pc_system_status())
            return

        elif path == '/api/terminals/list':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            self.send_json({"status": "ok", "terminals": get_open_terminals()})
            return

        elif path == '/api/terminals/read':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            term_id = qs.get('id', [''])[0]
            lines = qs.get('lines', ['120'])[0]
            try:
                max_lines = int(lines)
            except Exception:
                max_lines = 120
            res = read_terminal(term_id, max_lines)
            self.send_json(res)
            return

        elif path == '/api/screen/screenshot':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            data = capture_pc_screenshot()
            if data:
                self.send_response(200)
                self.send_header('Content-Type', 'image/jpeg')
                self.send_header('Content-Length', str(len(data)))
                self.send_header('Content-Disposition', 'inline; filename="pc_screenshot.jpg"')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.send_header('Cache-Control', 'no-cache, no-store')
                self.end_headers()
                self.wfile.write(data)
            else:
                self.send_error(500, "Screenshot capture failed")
            return

        elif path == '/api/weather':
            is_local = (self.client_address[0] in ('127.0.0.1', '::1', 'localhost')) and not is_tunnel_request(self)
            if not is_local:
                cfg = load_config()
                client_info, token = authenticate_client(self, cfg)
                if not client_info:
                    self.send_json({"error": "unauthorized"}, status=401)
                    return
            w = get_weather_cached()
            self.send_json({"status": "ok", "weather": w} if w else {"status": "error", "message": "Weather unavailable"})
            return

        elif path == '/api/files/search':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            q = qs.get('q', [''])[0]
            results = search_laptop_files(q)
            self.send_json({"status": "ok", "query": q, "count": len(results), "results": results})
            return

        elif path == '/api/laptop/files/list':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            req_path = unquote(qs.get('path', ['shortcuts'])[0])
            home = os.path.realpath(os.path.expanduser('~'))
            user = os.environ.get('USER', 'lunarphoton')

            # Discover mounted drives
            mounted_drives = []
            seen_mounts = set()
            for mdir in [f'/run/media/{user}', f'/media/{user}', '/media', '/mnt']:
                if os.path.exists(mdir):
                    try:
                        for entry in os.scandir(mdir):
                            if entry.is_dir():
                                rp = os.path.realpath(entry.path)
                                if rp not in seen_mounts:
                                    seen_mounts.add(rp)
                                    drive_size = "Mounted Drive"
                                    try:
                                        du = shutil.disk_usage(rp)
                                        drive_size = f"{round(du.free / (1024**3), 1)} GB free / {round(du.total / (1024**3), 1)} GB"
                                    except Exception:
                                        pass
                                    mounted_drives.append({
                                        "name": f"💾 {entry.name}",
                                        "path": entry.path,
                                        "is_dir": True,
                                        "icon": "💾",
                                        "size_formatted": drive_size
                                    })
                    except Exception:
                        pass

            try:
                du_root = shutil.disk_usage('/')
                root_size = f"{round(du_root.free / (1024**3), 1)} GB free / {round(du_root.total / (1024**3), 1)} GB"
            except Exception:
                root_size = "Root Filesystem"
            mounted_drives.append({
                "name": "💽 System Root (/)",
                "path": "/",
                "is_dir": True,
                "icon": "💽",
                "size_formatted": root_size
            })

            storage_info = {"total_gb": 0, "used_gb": 0, "free_gb": 0, "used_pct": 0, "formatted": "--"}

            if req_path in ('', 'shortcuts'):
                try:
                    du = shutil.disk_usage(home)
                    storage_info = {
                        "total_gb": round(du.total / (1024**3), 1),
                        "used_gb": round(du.used / (1024**3), 1),
                        "free_gb": round(du.free / (1024**3), 1),
                        "used_pct": round((du.used / du.total) * 100.0, 1),
                        "formatted": f"{round(du.free / (1024**3), 1)} GB free / {round(du.total / (1024**3), 1)} GB ({round((du.used / du.total) * 100.0)}%)"
                    }
                except Exception:
                    pass

                shortcuts = [
                    {"name": "🏠 Home", "path": home, "is_dir": True, "icon": "🏠", "size_formatted": "Folder"},
                    {"name": "📥 Downloads", "path": os.path.join(home, "Downloads"), "is_dir": True, "icon": "📥", "size_formatted": "Folder"},
                    {"name": "📄 Documents", "path": os.path.join(home, "Documents"), "is_dir": True, "icon": "📄", "size_formatted": "Folder"},
                    {"name": "🖼️ Pictures", "path": os.path.join(home, "Pictures"), "is_dir": True, "icon": "🖼️", "size_formatted": "Folder"},
                    {"name": "🎬 Videos", "path": os.path.join(home, "Videos"), "is_dir": True, "icon": "🎬", "size_formatted": "Folder"},
                    {"name": "💻 Desktop", "path": os.path.join(home, "Desktop"), "is_dir": True, "icon": "💻", "size_formatted": "Folder"},
                ]
                existing_shortcuts = [s for s in shortcuts if os.path.exists(s["path"])]
                all_items = existing_shortcuts + mounted_drives

                self.send_json({
                    "status": "ok",
                    "current_path": "shortcuts",
                    "parent_path": None,
                    "storage": storage_info,
                    "items": all_items
                })
                return

            req_path = os.path.realpath(os.path.expanduser(req_path))
            if not os.path.exists(req_path) or not os.path.isdir(req_path):
                self.send_json({"status": "error", "message": "Directory not found"}, status=404)
                return

            try:
                du = shutil.disk_usage(req_path)
                storage_info = {
                    "total_gb": round(du.total / (1024**3), 1),
                    "used_gb": round(du.used / (1024**3), 1),
                    "free_gb": round(du.free / (1024**3), 1),
                    "used_pct": round((du.used / du.total) * 100.0, 1),
                    "formatted": f"{round(du.free / (1024**3), 1)} GB free / {round(du.total / (1024**3), 1)} GB ({round((du.used / du.total) * 100.0)}%)"
                }
            except Exception:
                pass

            mount_roots = {os.path.realpath(m['path']) for m in mounted_drives}
            if req_path in ('/', home) or req_path in mount_roots:
                parent_path = "shortcuts"
            else:
                parent_path = os.path.dirname(req_path)

            show_hidden = qs.get('hidden', ['0'])[0] in ('1', 'true', 'True')
            items = []
            try:
                with os.scandir(req_path) as it:
                    for entry in it:
                        if not show_hidden and entry.name.startswith('.'):
                            continue
                        try:
                            is_dir = entry.is_dir(follow_symlinks=False)
                            st = entry.stat(follow_symlinks=False)
                            size_bytes = st.st_size if not is_dir else 0
                            size_str = format_file_size(size_bytes) if not is_dir else "Folder"
                            items.append({
                                "name": entry.name,
                                "path": entry.path,
                                "is_dir": is_dir,
                                "is_hidden": entry.name.startswith('.'),
                                "size": size_bytes,
                                "size_formatted": size_str,
                                "mtime": int(st.st_mtime)
                            })
                        except Exception:
                            continue
            except Exception as e:
                self.send_json({"status": "error", "message": str(e)}, status=500)
                return

            items.sort(key=lambda x: (not x["is_dir"], x["name"].lower()))

            self.send_json({
                "status": "ok",
                "current_path": req_path,
                "parent_path": parent_path,
                "storage": storage_info,
                "items": items
            })
            return

        elif path in ('/api/files/download_pc', '/api/laptop/files/stream'):
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            req_path = unquote(qs.get('path', [''])[0])
            req_path = os.path.realpath(os.path.expanduser(req_path))
            if not os.path.exists(req_path) or os.path.isdir(req_path):
                self.send_error(404, "File not found")
                return
            try:
                fn = os.path.basename(req_path)
                total_size = os.path.getsize(req_path)
                ctype, _ = mimetypes.guess_type(fn)
                ctype = ctype or 'application/octet-stream'

                range_header = self.headers.get('Range')
                start = 0
                end = total_size - 1

                if range_header and range_header.startswith('bytes='):
                    ranges = range_header.replace('bytes=', '').split('-')
                    if ranges[0]:
                        start = int(ranges[0])
                    if len(ranges) > 1 and ranges[1]:
                        end = int(ranges[1])
                    if end >= total_size:
                        end = total_size - 1

                    if start > end or start >= total_size:
                        self.send_response(416, "Requested Range Not Satisfiable")
                        self.send_header('Content-Range', f'bytes */{total_size}')
                        self.end_headers()
                        return

                    length = end - start + 1
                    self.send_response(206, "Partial Content")
                    self.send_header('Content-Range', f'bytes {start}-{end}/{total_size}')
                    self.send_header('Content-Length', str(length))
                else:
                    self.send_response(200, "OK")
                    self.send_header('Content-Length', str(total_size))

                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('Content-Type', ctype)
                self.send_header('Content-Disposition', f'inline; filename="{quote(fn)}"')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.send_header('Cache-Control', 'public, max-age=3600')
                self.end_headers()

                with open(req_path, 'rb') as f:
                    f.seek(start)
                    remaining = end - start + 1
                    while remaining > 0:
                        chunk_size = min(remaining, 65536)
                        chunk = f.read(chunk_size)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        remaining -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception as e:
                try: self.send_error(500, str(e))
                except Exception: pass
            return

        elif path == '/api/files/stage/status':
            token = qs.get('token', [''])[0]
            staged = staged_files.get(token)
            if not staged:
                self.send_json({"error": "not_found", "status": "not_found"}, status=404)
                return
            self.send_json({
                "status": "ok",
                "token": token,
                "filename": staged.get("filename"),
                "downloaded": staged.get("downloaded", False),
                "downloaded_at": staged.get("downloaded_at"),
                "created_at": staged.get("created_at"),
                "phone_connected": bool(auth_mgr.ndjson_clients)
            })
            return

        # 6. File Staging Download (PC to Phone pull)
        elif path.startswith('/api/files/staging/'):
            token = path.replace('/api/files/staging/', '').strip('/')
            staged = staged_files.get(token)
            if not staged or not os.path.exists(staged['filepath']):
                self.send_error(404, "Staged file not found or expired")
                return
            cfg = load_config()
            client_info, client_token = authenticate_client(self, cfg)
            if not client_info:
                self.send_error(401, "Unauthorized: Pairing required to download staged file")
                return
            try:
                size = os.path.getsize(staged['filepath'])
                fn = quote(staged['filename'])
                self.send_response(200)
                self.send_header('Content-Type', 'application/octet-stream')
                self.send_header('Content-Length', str(size))
                self.send_header('Content-Disposition', f'attachment; filename="{fn}"')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                with open(staged['filepath'], 'rb') as f:
                    while chunk := f.read(65536):
                        self.wfile.write(chunk)
                self.wfile.flush()
                staged['downloaded'] = True
                staged['downloaded_at'] = time.time()
                sys.stderr.write(f"📥 Staged file '{staged['filename']}' successfully downloaded by phone\n")
            except Exception as e:
                try: self.send_error(500, str(e))
                except Exception: pass
            return

        # 7. Phone File Browser Proxies (Linux Desktop -> Phone)
        elif path == '/api/phone/files/list':
            cfg = load_config()
            client_info, client_token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            phone_ip, phone_port, phone_token = get_phone_target()
            if not phone_ip:
                self.send_json({"error": "phone_not_connected", "message": "Phone is not connected or IP unknown"}, status=503)
                return
            req_path = qs.get('path', ['/storage/emulated/0'])[0]
            try:
                target_url = f"http://{phone_ip}:{phone_port}/api/files/list?path={quote(req_path)}"
                headers = {}
                if phone_token:
                    headers['Authorization'] = f'Bearer {phone_token}'
                req = urllib.request.Request(target_url, headers=headers)
                with urllib.request.urlopen(req, timeout=8) as resp:
                    data = resp.read()
                    self.send_response(resp.status)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Access-Control-Allow-Origin', '*')
                    self.end_headers()
                    self.wfile.write(data)
            except Exception as e:
                self.send_json({"error": "phone_unreachable", "message": str(e)}, status=502)
            return

        elif path in ('/api/phone/files/download', '/api/phone/files/preview'):
            cfg = load_config()
            client_info, client_token = authenticate_client(self, cfg)
            if not client_info:
                self.send_error(401, "Unauthorized")
                return
            phone_ip, phone_port, phone_token = get_phone_target()
            if not phone_ip:
                self.send_error(503, "Phone is not connected")
                return
            req_path = qs.get('path', [''])[0]
            is_preview = (path == '/api/phone/files/preview') or (qs.get('preview', ['0'])[0] in ('1', 'true'))
            try:
                target_url = f"http://{phone_ip}:{phone_port}/api/files/download?path={quote(req_path)}"
                headers = {}
                if phone_token:
                    headers['Authorization'] = f'Bearer {phone_token}'
                req = urllib.request.Request(target_url, headers=headers)
                with urllib.request.urlopen(req, timeout=30) as resp:
                    self.send_response(200)
                    fn = os.path.basename(req_path)
                    content_length = resp.headers.get('Content-Length')
                    
                    if is_preview:
                        ctype, _ = mimetypes.guess_type(fn)
                        ctype = ctype or 'application/octet-stream'
                        self.send_header('Content-Type', ctype)
                        self.send_header('Content-Disposition', f'inline; filename="{fn}"')
                    else:
                        self.send_header('Content-Type', 'application/octet-stream')
                        self.send_header('Content-Disposition', f'attachment; filename="{fn}"')

                    if content_length:
                        self.send_header('Content-Length', content_length)
                    self.send_header('Access-Control-Allow-Origin', '*')
                    self.end_headers()
                    while chunk := resp.read(65536):
                        self.wfile.write(chunk)
            except Exception as e:
                try: self.send_error(502, f"Download error: {e}")
                except Exception: pass
            return

        # 7b. Camera Endpoints
        elif path == '/api/camera/devices':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            devices = get_camera_devices()
            self.send_json({"devices": devices})
            return

        elif path == '/api/camera/snapshot':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            device = qs.get('device', [''])[0]
            self.handle_camera_snapshot(device)
            return

        elif path == '/api/camera/stream':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            device = qs.get('device', [''])[0]
            quality = qs.get('quality', ['smooth'])[0]
            self.handle_camera_stream(device, quality)
            return

        elif path == '/camera':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            self.handle_camera_web_page()
            return

        # 8. Topic Streaming / Polling (ntfy Android protocol)
        is_poll = qs.get('poll', ['0'])[0] in ('1', 'true')
        parts = [p for p in path.strip('/').split('/') if p]
        if parts:
            topic = parts[0]
            if is_poll:
                self.handle_poll(topic)
                return
            elif len(parts) == 1 or parts[1] == 'json':
                self.handle_ndjson_stream(topic)
                return

        self.send_error(404, "Not Found")

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

        # 1. File Upload (Phone -> PC)
        if path == '/api/files/upload':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            raw_fn = self.headers.get('X-Filename', '')
            if raw_fn:
                filename = os.path.basename(unquote(raw_fn))
            else:
                filename = f"received_file_{int(time.time())}"

            downloads_dir = os.path.expanduser('~/Downloads')
            os.makedirs(downloads_dir, exist_ok=True)

            target_path = os.path.join(downloads_dir, filename)
            base, ext = os.path.splitext(filename)
            counter = 1
            while os.path.exists(target_path):
                target_path = os.path.join(downloads_dir, f"{base} ({counter}){ext}")
                counter += 1

            try:
                length = int(self.headers.get('Content-Length', 0))
                remaining = length
                with open(target_path, 'wb') as f:
                    while remaining > 0:
                        chunk_size = min(65536, remaining)
                        chunk = self.rfile.read(chunk_size)
                        if not chunk:
                            break
                        f.write(chunk)
                        remaining -= len(chunk)

                final_fn = os.path.basename(target_path)
                threading.Thread(target=notify_file_received, args=(final_fn, target_path), daemon=True).start()
                self.send_json({"status": "ok", "filename": final_fn, "path": target_path})
            except Exception as e:
                self.send_json({"error": "upload_failed", "message": str(e)}, status=500)
            return

        # 2. File Proxy Upload (PC -> Phone)
        elif path == '/api/phone/files/upload':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            phone_ip, phone_port, phone_token = get_phone_target()
            if not phone_ip:
                self.send_json({"error": "phone_not_connected"}, status=503)
                return
            target_path = qs.get('path', ['/storage/emulated/0/Download'])[0]
            raw_fn = self.headers.get('X-Filename', '')
            target_url = f"http://{phone_ip}:{phone_port}/api/files/upload?path={quote(target_path)}"
            try:
                length = int(self.headers.get('Content-Length', 0))
                data = self.rfile.read(length)
                headers = {
                    'Content-Type': 'application/octet-stream',
                    'X-Filename': raw_fn
                }
                if phone_token:
                    headers['Authorization'] = f'Bearer {phone_token}'
                req = urllib.request.Request(
                    target_url,
                    data=data,
                    headers=headers,
                    method='POST'
                )
                with urllib.request.urlopen(req, timeout=30) as resp:
                    res_body = resp.read()
                    self.send_response(resp.status)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Access-Control-Allow-Origin', '*')
                    self.end_headers()
                    self.wfile.write(res_body)
            except Exception as e:
                self.send_json({"error": "upload_proxy_failed", "message": str(e)}, status=502)
            return

        # Read JSON body for standard API calls
        body = self.read_json()

        # 3. Core PC Authenticator APIs (Local only)
        if path == '/api/request_auth':
            is_local = (self.client_address[0] in ('127.0.0.1', '::1', 'localhost')) and not is_tunnel_request(self)
            if not is_local:
                self.send_json({"error": "forbidden", "message": "Auth requests can only be initiated locally."}, status=403)
                return
            user = body.get('user', os.environ.get('USER', 'lunarphoton'))
            cfg = load_config()
            timeout = cfg.get('auth_timeout_seconds', 35)
            session = auth_mgr.create_request(user, duration=timeout)
            self.send_json(session)

        elif path == '/api/pair/request':
            if is_tunnel_request(self):
                self.send_json({"error": "forbidden", "message": "Pairing is only allowed over local Wi-Fi / LAN."}, status=403)
                return

            now = time.time()
            with auth_mgr.lock:
                if not hasattr(auth_mgr, 'pair_rate_limits'):
                    auth_mgr.pair_rate_limits = []
                auth_mgr.pair_rate_limits = [t for t in auth_mgr.pair_rate_limits if now - t < 60]
                if len(auth_mgr.pair_rate_limits) >= 5:
                    self.send_json({"error": "rate_limited", "message": "Too many pairing attempts. Please wait 1 minute."}, status=429)
                    return
                auth_mgr.pair_rate_limits.append(now)

            client_id = body.get('client_id', '')
            client_name = body.get('client_name', 'Android Phone')
            if not client_id:
                client_id = str(uuid.uuid4())
            pin = f"{secrets.randbelow(900000) + 100000}"
            with auth_mgr.lock:
                auth_mgr.pending_pairings[client_id] = {
                    "pin": pin,
                    "name": client_name,
                    "expires_at": time.time() + 60,
                    "attempts": 0
                }
            try:
                formatted_pin = f"{pin[:3]} {pin[3:]}"
                msg = f"Pairing PIN for {client_name}:\n\n👉  {formatted_pin}  👈\n\nEnter this PIN in your phone app to authorize."
                subprocess.Popen(['notify-send', 'PC Connect - Pairing PIN', msg, '-u', 'critical', '-i', 'dialog-password'])
            except Exception as e:
                sys.stderr.write(f"notify-send error: {e}\n")
            sys.stderr.write(f"\n==================================================\n")
            sys.stderr.write(f"  PAIRING REQUEST from {client_name}\n")
            sys.stderr.write(f"  👉 PIN: {pin[:3]} {pin[3:]} 👈 (valid 60s)\n")
            sys.stderr.write(f"==================================================\n")
            sys.stderr.flush()
            self.send_json({
                "status": "pin_generated",
                "expires_in": 60,
                "client_id": client_id,
                "device_id": get_laptop_id(),
                "hostname": socket.gethostname()
            })

        elif path == '/api/pair/confirm':
            if is_tunnel_request(self):
                self.send_json({"error": "forbidden", "message": "Pairing confirmation is only allowed over local Wi-Fi / LAN."}, status=403)
                return
            client_id = body.get('client_id', '')
            pin = str(body.get('pin', '')).replace(' ', '').strip()
            client_name = body.get('client_name', 'Android Phone')
            with auth_mgr.lock:
                pending = auth_mgr.pending_pairings.get(client_id)
                if not pending:
                    self.send_json({"status": "error", "message": "No pairing request pending or expired"}, status=400)
                    return
                if time.time() > pending["expires_at"]:
                    del auth_mgr.pending_pairings[client_id]
                    self.send_json({"status": "error", "message": "PIN expired"}, status=400)
                    return
                pending["attempts"] += 1
                if pending["attempts"] > 3:
                    del auth_mgr.pending_pairings[client_id]
                    self.send_json({"status": "error", "message": "Too many failed attempts"}, status=403)
                    return
                if pending["pin"] != pin:
                    self.send_json({"status": "error", "message": "Incorrect PIN", "attempts_left": 3 - pending["attempts"]}, status=403)
                    return

                del auth_mgr.pending_pairings[client_id]
                auth_token = secrets.token_hex(32)
                secret_key = secrets.token_hex(32)
                cfg = load_config()
                if "paired_clients" not in cfg:
                    cfg["paired_clients"] = {}
                # Deduplicate by client_id so same device doesn't create duplicate tokens
                cfg["paired_clients"] = {
                    tok: c for tok, c in cfg["paired_clients"].items()
                    if c.get("client_id") != client_id
                }
                cfg["paired_clients"][auth_token] = {
                    "client_id": client_id,
                    "client_name": client_name,
                    "secret_key": secret_key,
                    "paired_at": time.time(),
                    "ip": get_effective_client_ip(self)
                }
                save_config(cfg)
                update_dolphin_servicemenu()
                active_phone_state['ip'] = get_effective_client_ip(self)
                active_phone_state['client_name'] = client_name
                active_phone_state['auth_token'] = auth_token
                try:
                    subprocess.Popen(['notify-send', 'PC Connect', f'✅ Successfully paired with {client_name}!', '-u', 'normal'])
                except Exception:
                    pass
                sys.stderr.write(f"✅ Successfully paired with {client_name} ({auth_token[:8]}...)\n")
                sys.stderr.flush()
                self.send_json({
                    "status": "ok",
                    "auth_token": auth_token,
                    "secret_key": secret_key,
                    "device_id": get_laptop_id(),
                    "hostname": socket.gethostname(),
                    "local_url": f"http://{get_local_ip()}:{cfg.get('web_port', 1760)}",
                    "internet_url": tunnel_mgr.get_url()
                })

        elif path in ('/api/approve_current', '/api/approve'):
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"status": "unauthorized", "message": "Device not paired"}, status=401)
                return

            if not client_info.get('local'):
                sig = self.headers.get('X-Auth-Signature', '')
                ts_str = self.headers.get('X-Auth-Timestamp', '')
                try:
                    ts = int(ts_str)
                    if abs(int(time.time()) - ts) > 45:
                        self.send_json({"status": "forbidden", "message": "Timestamp expired"}, status=403)
                        return
                except Exception:
                    self.send_json({"status": "forbidden", "message": "Invalid timestamp"}, status=403)
                    return

                with auth_mgr.lock:
                    current_sid = auth_mgr.current_session.get("session_id", "") if auth_mgr.current_session else ""
                secret_key = client_info.get('secret_key', '')
                req_sid = self.headers.get('X-Session-ID', '')
                if not req_sid and isinstance(body, dict):
                    req_sid = body.get('session_id', '')

                candidates = [f"{current_sid}:{ts}:approve", f":{ts}:approve"]
                if req_sid:
                    candidates.append(f"{req_sid}:{ts}:approve")
                valid = False
                for cand in candidates:
                    expected_sig = hmac.new(secret_key.encode('utf-8'), cand.encode('utf-8'), hashlib.sha256).hexdigest()
                    if hmac.compare_digest(sig.lower(), expected_sig.lower()):
                        valid = True
                        break
                if not valid:
                    self.send_json({"status": "forbidden", "message": "Cryptographic signature mismatch"}, status=403)
                    return

            if path == '/api/approve_current':
                ok = auth_mgr.approve_current()
            else:
                session_id = body.get('session_id') if isinstance(body, dict) else ""
                ok = auth_mgr.approve(session_id)
            self.send_json({"status": "ok" if ok else "error"})

        elif path in ('/api/deny_current', '/api/deny'):
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"status": "unauthorized", "message": "Device not paired"}, status=401)
                return

            if not client_info.get('local'):
                sig = self.headers.get('X-Auth-Signature', '')
                ts_str = self.headers.get('X-Auth-Timestamp', '')
                try:
                    ts = int(ts_str)
                    if abs(int(time.time()) - ts) > 45:
                        self.send_json({"status": "forbidden", "message": "Timestamp expired"}, status=403)
                        return
                except Exception:
                    self.send_json({"status": "forbidden", "message": "Invalid timestamp"}, status=403)
                    return

                with auth_mgr.lock:
                    current_sid = auth_mgr.current_session.get("session_id", "") if auth_mgr.current_session else ""
                secret_key = client_info.get('secret_key', '')
                req_sid = self.headers.get('X-Session-ID', '')
                if not req_sid and isinstance(body, dict):
                    req_sid = body.get('session_id', '')
                candidates = [f"{current_sid}:{ts}:deny", f":{ts}:deny"]
                if req_sid:
                    candidates.append(f"{req_sid}:{ts}:deny")
                valid = False
                for cand in candidates:
                    expected_sig = hmac.new(secret_key.encode('utf-8'), cand.encode('utf-8'), hashlib.sha256).hexdigest()
                    if hmac.compare_digest(sig.lower(), expected_sig.lower()):
                        valid = True
                        break
                if not valid:
                    self.send_json({"status": "forbidden", "message": "Cryptographic signature mismatch"}, status=403)
                    return

            if path == '/api/deny_current':
                ok = auth_mgr.deny_current()
            else:
                session_id = body.get('session_id') if isinstance(body, dict) else ""
                ok = auth_mgr.deny(session_id)
            self.send_json({"status": "ok" if ok else "error"})

        # 4. KDE Connect Phone Status Updates
        elif path == '/api/phone/status':
            if 'battery' in body:
                active_phone_state['battery_level'] = body.get('battery')
            if 'charging' in body:
                active_phone_state['is_charging'] = bool(body.get('charging'))
            if 'port' in body:
                active_phone_state['port'] = int(body.get('port'))
            if 'device_name' in body:
                active_phone_state['client_name'] = body.get('device_name')
            if 'model' in body:
                active_phone_state['model'] = body.get('model')
            if 'manufacturer' in body:
                active_phone_state['manufacturer'] = body.get('manufacturer')
            if 'android_version' in body:
                active_phone_state['android_version'] = body.get('android_version')
            if 'storage_free' in body:
                active_phone_state['storage_free'] = body.get('storage_free')
            if 'storage_total' in body:
                active_phone_state['storage_total'] = body.get('storage_total')
            active_phone_state['ip'] = get_effective_client_ip(self)
            active_phone_state['last_seen'] = time.time()
            self.send_json({"status": "ok"})

        # 5. Clipboard Sync
        elif path == '/api/clipboard':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            global _last_phone_clipboard, _last_pc_clipboard
            text = body.get('text', '')
            is_local = client_info.get('local', False)
            if not text and is_local:
                text = get_kde_clipboard()
                if text:
                    with _clipboard_lock:
                        _last_pc_clipboard = text
                    auth_mgr.broadcast_ndjson(json.dumps({"event": "clipboard", "text": text}) + "\n")
            elif text:
                with _clipboard_lock:
                    _last_phone_clipboard = text
                    _last_pc_clipboard = text
                set_kde_clipboard(text)
                # If triggered from local PC, broadcast to phone
                if is_local:
                    auth_mgr.broadcast_ndjson(json.dumps({"event": "clipboard", "text": text}) + "\n")
            self.send_json({"status": "ok"})

        # 6. Media Control
        elif path == '/api/media/command':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            cmd = body.get('command', '')
            success = send_mpris_command(cmd)
            self.send_json({"status": "ok" if success else "failed"})

        # Terminal Endpoints
        elif path == '/api/terminals/write':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            term_id = body.get('id', '')
            text = body.get('text', '')
            res = write_terminal(term_id, text)
            self.send_json(res)
            return

        elif path == '/api/terminals/key':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            term_id = body.get('id', '')
            key_name = body.get('key', '')
            res = send_terminal_key(term_id, key_name)
            self.send_json(res)
            return

        elif path == '/api/terminals/new':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            workdir = body.get('cwd', '')
            res = spawn_new_terminal(workdir)
            self.send_json(res)
            return

        # 7. Ping (Ring PC)
        elif path == '/api/ping':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            ping_pc()
            self.send_json({"status": "ok"})

        # 8. Ring (Find My Phone)
        elif path == '/api/ring':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            phone_ip = body.get('phone_ip') or active_phone_state.get('ip')
            ring_phone(phone_ip=phone_ip)
            self.send_json({"status": "ok"})

        # 8b. Stop Ringing (Find My Phone)
        elif path == '/api/unring':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            phone_ip = body.get('phone_ip') or active_phone_state.get('ip')
            unring_phone(phone_ip=phone_ip)
            self.send_json({"status": "ok"})

        # 9. Remote Action (Lock, Suspend, Screen off)
        elif path == '/api/action':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            act = body.get('action', '')
            if act == 'lock':
                subprocess.Popen(['loginctl', 'lock-session'])
                try:
                    subprocess.run(['qdbus6', 'org.freedesktop.ScreenSaver', '/ScreenSaver', 'org.freedesktop.ScreenSaver.Lock'], stderr=subprocess.DEVNULL)
                except Exception:
                    pass
                self.send_json({"status": "ok", "action": "lock"})
            elif act == 'unlock':
                pwd = body.get('password', '')
                user = os.environ.get('USER', 'lunarphoton')
                # If password was provided, verify it with unix_chkpwd
                if pwd:
                    try:
                        p = subprocess.Popen(['unix_chkpwd', user, 'nullok'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                        out, err = p.communicate(pwd.encode('utf-8') + b'\x00', timeout=3)
                        if p.returncode != 0:
                            self.send_json({"status": "error", "message": "Incorrect password"}, status=401)
                            return
                    except Exception as e:
                        self.send_json({"status": "error", "message": f"Verification error: {str(e)}"}, status=500)
                        return

                # Also approve any pending 2FA challenge if active
                with auth_mgr.lock:
                    if auth_mgr.current_session and auth_mgr.current_session["status"] == "pending":
                        auth_mgr.approve_current()

                # Unlock session via loginctl and ScreenSaver D-Bus
                subprocess.Popen(['loginctl', 'unlock-session'])
                try:
                    subprocess.run(['qdbus6', 'org.freedesktop.ScreenSaver', '/ScreenSaver', 'org.freedesktop.ScreenSaver.SetActive', 'false'], stderr=subprocess.DEVNULL)
                except Exception:
                    pass
                try:
                    subprocess.Popen(['notify-send', '-i', 'system-lock-screen', '-a', 'PC Connect', 'PC Unlocked', 'Screen unlocked remotely from phone'], stderr=subprocess.DEVNULL)
                except Exception:
                    pass
                self.send_json({"status": "ok", "action": "unlock"})
            elif act == 'suspend':
                subprocess.Popen(['systemctl', 'suspend'])
                self.send_json({"status": "ok", "action": "suspend"})
            elif act == 'screen_off':
                subprocess.Popen(['kscreen-doctor', '--dpms', 'off'])
                self.send_json({"status": "ok", "action": "screen_off"})
            else:
                self.send_json({"status": "unknown_action"}, status=400)

        # 9b. Telephony / Call Event (Auto-pause media on call)
        elif path == '/api/telephony/call':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            state = body.get('state', '').lower()
            handle_telephony_call_state(state)
            self.send_json({"status": "ok", "state": state})

        # 10. File Staging for PC-to-Phone send (Local PC Only)
        elif path == '/api/files/stage':
            is_local = (self.client_address[0] in ('127.0.0.1', '::1', 'localhost')) and not is_tunnel_request(self)
            if not is_local:
                self.send_json({"error": "forbidden", "message": "File staging is only allowed from local PC."}, status=403)
                return
            filepath = body.get('filepath', '')
            if not filepath or not os.path.exists(filepath):
                self.send_json({"error": "file_not_found"}, status=404)
                return

            now = time.time()
            # Prune expired staged files older than 1 hour
            for tok, item in list(staged_files.items()):
                if now - item.get('created_at', 0) > 3600:
                    staged_files.pop(tok, None)

            token = secrets.token_urlsafe(16)
            fn = os.path.basename(filepath)
            size = os.path.getsize(filepath)
            staged_files[token] = {
                "token": token,
                "filepath": filepath,
                "filename": fn,
                "size": size,
                "created_at": now,
                "downloaded": False,
                "downloaded_at": None
            }
            local_ip = get_local_ip()
            cfg = load_config()
            port = cfg.get('web_port', 1760)
            dl_url = f"http://{local_ip}:{port}/api/files/staging/{token}"
            tunnel_url = tunnel_mgr.get_url()
            internet_dl_url = f"{tunnel_url}/api/files/staging/{token}" if tunnel_url else None
            auth_mgr.broadcast_ndjson(json.dumps({
                "event": "incoming_file",
                "filename": fn,
                "download_url": dl_url,
                "internet_download_url": internet_dl_url,
                "staging_path": f"/api/files/staging/{token}",
                "size": size
            }) + "\n")
            has_listeners = bool(auth_mgr.ndjson_clients)
            self.send_json({
                "status": "ok",
                "token": token,
                "download_url": dl_url,
                "internet_download_url": internet_dl_url,
                "staging_path": f"/api/files/staging/{token}",
                "phone_connected": has_listeners
            })

        # 11. Phone Filesystem Mutations (Proxy)
        elif path == '/api/phone/files/delete':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            phone_ip, phone_port, phone_token = get_phone_target()
            if not phone_ip:
                self.send_json({"error": "phone_not_connected"}, status=503)
                return
            target_path = body.get('path', '')
            try:
                target_url = f"http://{phone_ip}:{phone_port}/api/files/delete?path={quote(target_path)}"
                headers = {'Content-Type': 'application/json'}
                if phone_token:
                    headers['Authorization'] = f'Bearer {phone_token}'
                req = urllib.request.Request(target_url, data=b'{}', headers=headers, method='POST')
                with urllib.request.urlopen(req, timeout=5) as resp:
                    self.send_json({"status": "ok"})
            except Exception as e:
                self.send_json({"error": "delete_failed", "message": str(e)}, status=502)

        elif path == '/api/phone/files/mkdir':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            phone_ip, phone_port, phone_token = get_phone_target()
            if not phone_ip:
                self.send_json({"error": "phone_not_connected"}, status=503)
                return
            parent_path = body.get('path', '')
            name = body.get('name', '')
            try:
                target_url = f"http://{phone_ip}:{phone_port}/api/files/mkdir?path={quote(parent_path)}&name={quote(name)}"
                headers = {'Content-Type': 'application/json'}
                if phone_token:
                    headers['Authorization'] = f'Bearer {phone_token}'
                req = urllib.request.Request(target_url, data=b'{}', headers=headers, method='POST')
                with urllib.request.urlopen(req, timeout=5) as resp:
                    self.send_json({"status": "ok"})
            except Exception as e:
                self.send_json({"error": "mkdir_failed", "message": str(e)}, status=502)

        # 12. Open Webpage / URL in default browser
        elif path == '/api/open_url':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            url = body.get('url', '').strip()
            if url and (url.startswith('http://') or url.startswith('https://')):
                try:
                    subprocess.Popen(['xdg-open', url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    try:
                        subprocess.Popen(['notify-send', '-i', 'applications-internet', '-a', 'PC Connect', '🌐 Webpage Received', url], stderr=subprocess.DEVNULL)
                    except Exception:
                        pass
                    self.send_json({"status": "ok", "url": url})
                except Exception as e:
                    self.send_json({"error": str(e)}, status=500)
            else:
                self.send_json({"error": "invalid_url", "message": "URL must start with http:// or https://"}, status=400)

        # 13. Virtual Mouse & Keyboard / Trackpad (HTTP fallback)
        elif path == '/api/mouse':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            mtype = body.get('type', '')
            if mtype == 'move':
                virtual_mouse.move(body.get('dx', 0), body.get('dy', 0))
            elif mtype == 'pointer':
                trigger_laser_overlay(body)
            elif mtype == 'laser_state':
                trigger_laser_overlay(body)
            elif mtype == 'key':
                virtual_mouse.press_key(body.get('key', ''))
            elif mtype == 'text':
                virtual_mouse.type_text(body.get('text', ''))
            elif mtype == 'click':
                virtual_mouse.click(body.get('button', 'left'))
            elif mtype == 'down':
                virtual_mouse.mouse_down(body.get('button', 'left'))
            elif mtype == 'up':
                virtual_mouse.mouse_up(body.get('button', 'left'))
            elif mtype == 'scroll':
                virtual_mouse.scroll(body.get('dy', 0), body.get('dx', 0))
            self.send_json({"status": "ok"})

        elif path == '/api/keyboard/text':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            text = body.get('text', '')
            if text:
                virtual_mouse.type_text(text)
            self.send_json({"status": "ok"})

        elif path == '/api/keyboard/key':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            key = body.get('key', '')
            if key:
                virtual_mouse.press_key(key)
            self.send_json({"status": "ok"})

        # 14. Unpair Device API (Local PC or Phone)
        elif path in ('/api/devices/unpair', '/api/unpair'):
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            is_local = client_info.get('local', False)
            if is_local:
                ident = body.get('token') or body.get('client_id') or body.get('id') or body.get('ip')
            else:
                ident = token
            if not ident:
                self.send_json({"error": "missing_device_id"}, status=400)
                return
            ok, msg = unpair_device(ident)
            self.send_json({"status": "ok" if ok else "error", "message": msg})

        # 16. Captive Portal / Campus Auto-Login
        elif path == '/api/network/captive_login':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            res = trigger_pc_captive_login(body)
            self.send_json(res)
            return

        else:
            self.send_error(404, "Not Found")

    def handle_poll(self, topic):
        cfg = load_config()
        client_info, token = authenticate_client(self, cfg)
        if not client_info:
            self.send_response(401)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps({"error": "unauthorized", "message": "Pairing required"}).encode('utf-8'))
            return

        self.send_response(200)
        self.send_header('Content-Type', 'application/x-ndjson; charset=utf-8')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()

        with auth_mgr.lock:
            if auth_mgr.current_session and auth_mgr.current_session["status"] == "pending":
                if time.time() <= auth_mgr.current_session["expires_at"]:
                    msg = auth_mgr.build_app_notification(auth_mgr.current_session, topic=topic)
                    try:
                        self.wfile.write((json.dumps(msg) + "\n").encode('utf-8'))
                        self.wfile.flush()
                    except Exception:
                        pass

    def handle_ndjson_stream(self, topic):
        cfg = load_config()
        client_info, token = authenticate_client(self, cfg)
        if not client_info:
            self.send_response(401)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps({"error": "unauthorized", "message": "Pairing required"}).encode('utf-8'))
            return

        self.send_response(200)
        self.send_header('Content-Type', 'application/x-ndjson; charset=utf-8')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'keep-alive')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()

        q = queue.Queue()
        auth_mgr.add_ndjson_client(q)

        # 1. Handshake conforming to ntfy spec
        open_event = {
            "id": str(uuid.uuid4())[:10],
            "time": int(time.time()),
            "event": "open",
            "topic": topic
        }
        try:
            self.wfile.write((json.dumps(open_event) + "\n").encode('utf-8'))
            self.wfile.flush()
        except Exception:
            auth_mgr.remove_ndjson_client(q)
            return

        # 2. Push active challenge if pending
        with auth_mgr.lock:
            if auth_mgr.current_session and auth_mgr.current_session["status"] == "pending":
                if time.time() <= auth_mgr.current_session["expires_at"]:
                    pending_msg = auth_mgr.build_app_notification(auth_mgr.current_session, topic=topic)
                    try:
                        self.wfile.write((json.dumps(pending_msg) + "\n").encode('utf-8'))
                        self.wfile.flush()
                    except Exception:
                        pass

        # 3. Push pending un-downloaded staged files from the last 15 minutes
        now = time.time()
        local_ip = get_local_ip()
        cfg = load_config()
        port = cfg.get('web_port', 1760)
        tunnel_url = tunnel_mgr.get_url()
        for s_token, s_info in list(staged_files.items()):
            if not s_info.get("downloaded") and (now - s_info.get("created_at", 0) < 900):
                if os.path.exists(s_info.get("filepath", "")):
                    staged_event = {
                        "event": "incoming_file",
                        "filename": s_info["filename"],
                        "download_url": f"http://{local_ip}:{port}/api/files/staging/{s_token}",
                        "internet_download_url": f"{tunnel_url}/api/files/staging/{s_token}" if tunnel_url else None,
                        "staging_path": f"/api/files/staging/{s_token}",
                        "size": s_info["size"]
                    }
                    try:
                        self.wfile.write((json.dumps(staged_event) + "\n").encode('utf-8'))
                        self.wfile.flush()
                        sys.stderr.write(f"🔄 Replayed pending staged file '{s_info['filename']}' to reconnected phone\n")
                    except Exception:
                        pass

        # 4. Stream loop with battery-conserving keepalive (45s)
        try:
            while True:
                try:
                    msg = q.get(timeout=45)
                    self.wfile.write(msg.encode('utf-8'))
                    self.wfile.flush()
                except queue.Empty:
                    keepalive = {
                        "id": str(uuid.uuid4())[:10],
                        "time": int(time.time()),
                        "event": "keepalive",
                        "topic": topic
                    }
                    self.wfile.write((json.dumps(keepalive) + "\n").encode('utf-8'))
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            pass
        finally:
            auth_mgr.remove_ndjson_client(q)

    def handle_sse(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'keep-alive')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()

        q = queue.Queue()
        auth_mgr.add_sse_client(q)

        init_data = {"hostname": socket.gethostname()}
        try:
            self.wfile.write(f"event: info\ndata: {json.dumps(init_data)}\n\n".encode('utf-8'))
            self.wfile.flush()
        except Exception:
            auth_mgr.remove_sse_client(q)
            return

        with auth_mgr.lock:
            if auth_mgr.current_session and auth_mgr.current_session["status"] == "pending":
                try:
                    self.wfile.write(f"event: auth_request\ndata: {json.dumps(auth_mgr.current_session)}\n\n".encode('utf-8'))
                    self.wfile.flush()
                except Exception:
                    pass

        try:
            while True:
                try:
                    msg = q.get(timeout=15)
                    self.wfile.write(msg.encode('utf-8'))
                    self.wfile.flush()
                except queue.Empty:
                    self.wfile.write(b": heartbeat\n\n")
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            pass
        finally:
            auth_mgr.remove_sse_client(q)

    def serve_apk(self):
        if not os.path.exists(APK_FILE):
            self.send_error(404, "APK Not Found")
            return
        try:
            size = os.path.getsize(APK_FILE)
            self.send_response(200)
            self.send_header('Content-Type', 'application/vnd.android.package-archive')
            self.send_header('Content-Length', str(size))
            self.send_header('Content-Disposition', 'attachment; filename="PCAuthenticator.apk"')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            with open(APK_FILE, 'rb') as f:
                while chunk := f.read(65536):
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            try: self.send_error(500, str(e))
            except Exception: pass

    def serve_file(self, filepath, content_type):
        if not os.path.exists(filepath):
            self.send_error(404, "File Not Found")
            return
        try:
            with open(filepath, 'rb') as f:
                content = f.read()
            self.send_response(200)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(content)))
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(content)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            try: self.send_error(500, str(e))
            except Exception: pass

    def handle_camera_snapshot(self, device=''):
        cam = get_best_camera(device)
        cmd = [
            'ffmpeg', '-nostdin', '-loglevel', 'error', '-y',
            '-f', 'v4l2', '-input_format', 'mjpeg',
            '-video_size', '1280x720',
            '-i', cam,
            '-vframes', '1',
            '-f', 'image2', 'pipe:1'
        ]
        try:
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=4)
            if res.returncode == 0 and res.stdout:
                self.send_response(200)
                self.send_header('Content-Type', 'image/jpeg')
                self.send_header('Content-Length', str(len(res.stdout)))
                self.send_header('Access-Control-Allow-Origin', '*')
                self.send_header('Cache-Control', 'no-cache, no-store')
                self.end_headers()
                self.wfile.write(res.stdout)
                return
        except Exception:
            pass
        self.send_error(500, "Failed to capture camera snapshot")

    def handle_camera_stream(self, device='', quality='smooth'):
        cam = get_best_camera(device)
        size = '1280x720' if quality == 'hd' else '640x480'
        fps = '20' if quality == 'hd' else '30'

        cmd = [
            'ffmpeg', '-nostdin', '-loglevel', 'error',
            '-f', 'v4l2', '-input_format', 'mjpeg',
            '-video_size', size, '-framerate', fps,
            '-i', cam,
            '-c:v', 'copy',
            '-f', 'mjpeg', 'pipe:1'
        ]
        try:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        except Exception as e:
            self.send_error(500, f"Cannot access camera: {e}")
            return

        self.send_response(200)
        self.send_header('Content-Type', 'multipart/x-mixed-replace; boundary=--frame')
        self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()

        buffer = b''
        try:
            while True:
                chunk = proc.stdout.read(8192)
                if not chunk:
                    break
                buffer += chunk
                while True:
                    start = buffer.find(b'\xff\xd8')
                    if start == -1:
                        buffer = buffer[-2:]
                        break
                    end = buffer.find(b'\xff\xd9', start)
                    if end == -1:
                        buffer = buffer[start:]
                        break
                    frame = buffer[start:end+2]
                    buffer = buffer[end+2:]
                    header = f"\r\n--frame\r\nContent-Type: image/jpeg\r\nContent-Length: {len(frame)}\r\n\r\n".encode('ascii')
                    self.wfile.write(header + frame)
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            pass
        finally:
            try:
                proc.terminate()
                proc.wait(timeout=1)
            except Exception:
                proc.kill()

    def handle_camera_web_page(self):
        html = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>PC Webcam Live Viewer</title>
    <style>
        body { margin: 0; background: #0f172a; color: #f8fafc; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100vh; }
        .card { background: #1e293b; border-radius: 16px; padding: 20px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); max-width: 720px; width: 90%; text-align: center; }
        h1 { margin-top: 0; font-size: 20px; color: #06b6d4; display: flex; align-items: center; justify-content: center; gap: 8px; }
        .stream-container { position: relative; border-radius: 12px; overflow: hidden; background: #000; width: 100%; aspect-ratio: 4/3; display: flex; align-items: center; justify-content: center; }
        .stream-container img { width: 100%; height: 100%; object-fit: contain; }
        .actions { margin-top: 16px; display: flex; gap: 10px; justify-content: center; flex-wrap: wrap; }
        button, a.btn { background: #0ea5e9; color: white; border: none; padding: 10px 18px; border-radius: 8px; font-weight: bold; cursor: pointer; text-decoration: none; font-size: 14px; transition: background 0.2s; }
        button:hover, a.btn:hover { background: #0284c7; }
        .btn-green { background: #10b981; }
        .btn-green:hover { background: #059669; }
    </style>
</head>
<body>
    <div class="card">
        <h1>📹 PC Camera Live Feed</h1>
        <div class="stream-container">
            <img src="/api/camera/stream" alt="Live Camera Feed">
        </div>
        <div class="actions">
            <a class="btn btn-green" href="/api/camera/snapshot" target="_blank" download="snapshot.jpg">📸 Save Snapshot</a>
            <button onclick="location.reload()">🔄 Reconnect</button>
            <a class="btn" href="/browse">📁 File Explorer</a>
        </div>
    </div>
</body>
</html>"""
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(html.encode('utf-8'))))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(html.encode('utf-8'))

    def read_json(self):
        try:
            length = int(self.headers.get('Content-Length', 0))
            if length > 0:
                return json.loads(self.rfile.read(length).decode('utf-8'))
        except Exception:
            pass
        return {}

    def send_json(self, data, status=200):
        content = json.dumps(data).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(content)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        try:
            self.wfile.write(content)
        except (BrokenPipeError, ConnectionResetError):
            pass

# ==========================================
# Network Discovery & Beaconing
# ==========================================

def get_default_gateway():
    try:
        out = subprocess.check_output(['ip', 'route', 'show', 'default'], stderr=subprocess.DEVNULL).decode()
        import re
        m = re.search(r'default\s+via\s+([0-9.]+)', out)
        if m:
            return m.group(1)
    except Exception:
        pass
    return None

def start_udp_discovery_server(port):
    def udp_loop():
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(('0.0.0.0', port))
            sys.stderr.write(f"  UDP Discovery Listener active on port {port}\n")
            sys.stderr.flush()
            while True:
                data, addr = sock.recvfrom(1024)
                local_ip = get_local_ip()
                reply = {
                    "type": "pc_auth_discovery_reply",
                    "device_id": get_laptop_id(),
                    "hostname": socket.gethostname(),
                    "ip": local_ip,
                    "port": port,
                    "user": os.environ.get("USER", "lunarphoton"),
                    "local_url": f"http://{local_ip}:{port}",
                    "internet_url": tunnel_mgr.get_url()
                }
                try:
                    sock.sendto(json.dumps(reply).encode('utf-8'), addr)
                except Exception:
                    pass
        except Exception as e:
            sys.stderr.write(f"UDP discovery error: {e}\n")
            sys.stderr.flush()

    t = threading.Thread(target=udp_loop, daemon=True)
    t.start()

def start_udp_beacon(port):
    def beacon_loop():
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        while True:
            try:
                local_ip = get_local_ip()
                gw = get_default_gateway()
                beacon_data = json.dumps({
                    "type": "pc_auth_discovery_reply",
                    "device_id": get_laptop_id(),
                    "hostname": socket.gethostname(),
                    "ip": local_ip,
                    "port": port,
                    "user": os.environ.get("USER", "lunarphoton"),
                    "local_url": f"http://{local_ip}:{port}",
                    "internet_url": tunnel_mgr.get_url()
                }).encode('utf-8')

                try:
                    sock.sendto(beacon_data, ('255.255.255.255', port))
                except Exception:
                    pass

                if gw and gw != '127.0.0.1':
                    try:
                        sock.sendto(beacon_data, (gw, port))
                    except Exception:
                        pass
            except Exception:
                pass
            time.sleep(3)

    t = threading.Thread(target=beacon_loop, daemon=True)
    t.start()

def cli_pair():
    cfg = load_config()
    port = cfg.get('web_port', 1760)
    try:
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}/api/pair/request",
            data=b'{"client_name":"Terminal CLI"}',
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=3) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            print("==================================================")
            print("  PC Authenticator - Device Pairing")
            print("==================================================")
            print("  A pairing PIN has been triggered.")
            print("  Check your desktop notifications or system logs.")
            print(f"  Laptop ID: {data.get('device_id')}")
            print(f"  Hostname:  {data.get('hostname')}")
            print("==================================================")
    except Exception as e:
        print(f"Error requesting pairing PIN: {e}")

def main():
    cfg = load_config()
    port = cfg.get('web_port', 1760)
    local_ip = get_local_ip()

    update_dolphin_servicemenu()
    start_udp_discovery_server(port)
    start_udp_beacon(port)
    start_udp_mouse_listener(1762)
    start_clipboard_monitor(auth_mgr)

    tunnel_mgr.port = port
    tunnel_mgr.start()

    server = ThreadingHTTPServer(('0.0.0.0', port), AuthenticatorHandler)
    sys.stderr.write("==================================================\n")
    sys.stderr.write(f"  PC Connect & Authenticator Daemon Running (Port {port})\n")
    sys.stderr.write(f"  Device ID: {get_laptop_id()}\n")
    sys.stderr.write(f"  Web Explorer: http://{local_ip}:{port}/browse\n")
    sys.stderr.write(f"  Download APK: http://{local_ip}:{port}/apk\n")
    sys.stderr.write(f"  Topic Stream: http://{local_ip}:{port}/login/json\n")
    sys.stderr.write("==================================================\n")
    sys.stderr.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        tunnel_mgr.stop()
        server.server_close()

if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] in ('--pair', 'pair'):
        cli_pair()
    else:
        main()
