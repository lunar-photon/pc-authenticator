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
from urllib.parse import urlparse, parse_qs, unquote, quote
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(BASE_DIR, 'config.json')
STATIC_DIR = os.path.join(BASE_DIR, 'static')
APK_FILE = os.path.join(STATIC_DIR, 'authenticator.apk')
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
    "auth_token": None
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
    except Exception as e:
        sys.stderr.write(f"Error saving config: {e}\n")

def get_laptop_id():
    try:
        if os.path.exists('/etc/machine-id'):
            with open('/etc/machine-id', 'r') as f:
                mid = f.read().strip()
                if mid:
                    return mid
    except Exception:
        pass
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

def authenticate_client(handler, cfg):
    client_ip = handler.client_address[0]
    # Local requests (e.g. from desktop tray or pc-connect CLI) are always authorized
    if client_ip in ('127.0.0.1', '::1', 'localhost'):
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
# KDE Plasma 6 & MPRIS Helpers
# ==========================================

def get_mpris_players():
    try:
        out = subprocess.check_output(['qdbus6'], stderr=subprocess.DEVNULL).decode('utf-8')
        return [l.strip() for l in out.splitlines() if l.strip().startswith('org.mpris.MediaPlayer2.')]
    except Exception:
        return []

def get_mpris_status():
    players = get_mpris_players()
    if not players:
        return {"has_player": False}
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
        "art_url": art_url
    }

def send_mpris_command(command):
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
    elif command == "VolumeUp":
        try:
            curr = float(subprocess.check_output(['qdbus6', target, '/org/mpris/MediaPlayer2', 'org.mpris.MediaPlayer2.Player.Volume'], stderr=subprocess.DEVNULL).decode().strip())
            new_vol = min(1.0, curr + 0.05)
            subprocess.run(['qdbus6', target, '/org/mpris/MediaPlayer2', 'org.mpris.MediaPlayer2.Player.Volume', str(new_vol)], stderr=subprocess.DEVNULL)
            return True
        except Exception:
            pass
    elif command == "VolumeDown":
        try:
            curr = float(subprocess.check_output(['qdbus6', target, '/org/mpris/MediaPlayer2', 'org.mpris.MediaPlayer2.Player.Volume'], stderr=subprocess.DEVNULL).decode().strip())
            new_vol = max(0.0, curr - 0.05)
            subprocess.run(['qdbus6', target, '/org/mpris/MediaPlayer2', 'org.mpris.MediaPlayer2.Player.Volume', str(new_vol)], stderr=subprocess.DEVNULL)
            return True
        except Exception:
            pass
    return False

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

def get_phone_target():
    ip = active_phone_state.get('ip')
    port = active_phone_state.get('port', 1761)
    if not ip or ip in ('127.0.0.1', 'localhost', '::1'):
        cfg = load_config()
        for token, client in cfg.get('paired_clients', {}).items():
            client_ip = client.get('ip')
            if client_ip and client_ip not in ('127.0.0.1', 'localhost', '::1'):
                ip = client_ip
                active_phone_state['ip'] = ip
                active_phone_state['client_name'] = client.get('client_name', 'Android Phone')
                break
    return ip, port

def ring_phone(phone_ip=None, port=1761):
    # 1. Broadcast via NDJSON stream
    auth_mgr.broadcast_ndjson(json.dumps({"event": "ring", "title": "Find My Phone", "time": int(time.time())}) + "\n")
    # 2. Direct HTTP to phone if IP is known
    target_ip, target_port = get_phone_target()
    target_ip = phone_ip or target_ip
    target_port = port or target_port
    if target_ip:
        def _direct_ring():
            try:
                req = urllib.request.Request(f"http://{target_ip}:{target_port}/api/ring", data=b'{}', headers={'Content-Type': 'application/json'}, method='POST')
                urllib.request.urlopen(req, timeout=3)
            except Exception:
                pass
        threading.Thread(target=_direct_ring, daemon=True).start()

def unring_phone(phone_ip=None, port=1761):
    # 1. Broadcast unring via NDJSON stream
    auth_mgr.broadcast_ndjson(json.dumps({"event": "unring", "title": "Stop Alarm", "time": int(time.time())}) + "\n")
    # 2. Direct HTTP to phone to silence
    target_ip, target_port = get_phone_target()
    target_ip = phone_ip or target_ip
    target_port = port or target_port
    if target_ip:
        def _direct_unring():
            try:
                req = urllib.request.Request(f"http://{target_ip}:{target_port}/api/unring", data=b'{}', headers={'Content-Type': 'application/json'}, method='POST')
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

class VirtualMouse:
    def __init__(self):
        self.lock = threading.Lock()
        self.fd = None
        try:
            self.fd = os.open('/dev/uinput', os.O_WRONLY | os.O_NONBLOCK)
            fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_KEY)
            for btn in (BTN_LEFT, BTN_RIGHT, BTN_MIDDLE):
                fcntl.ioctl(self.fd, UI_SET_KEYBIT, btn)

            fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_REL)
            for rel in (REL_X, REL_Y, REL_WHEEL, REL_HWHEEL):
                fcntl.ioctl(self.fd, UI_SET_RELBIT, rel)

            name = b"PC Connect Virtual Mouse".ljust(80, b'\x00')
            input_id = struct.pack('HHHH', 0x03, 0x1234, 0x5678, 1)
            user_dev = name + input_id + struct.pack('I', 0) + b'\x00' * (64 * 4 * 4)
            os.write(self.fd, user_dev)
            fcntl.ioctl(self.fd, UI_DEV_CREATE)
            sys.stderr.write("  Virtual Mouse (/dev/uinput) initialized successfully\n")
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
            if dx != 0:
                self.emit(EV_REL, REL_X, dx)
            if dy != 0:
                self.emit(EV_REL, REL_Y, dy)
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

    def close(self):
        if self.fd:
            try:
                fcntl.ioctl(self.fd, UI_DEV_DESTROY)
                os.close(self.fd)
            except Exception:
                pass
            self.fd = None

virtual_mouse = VirtualMouse()

def start_udp_mouse_listener(port=1762):
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
                    mtype = payload.get('type', '')
                    if mtype == 'move':
                        virtual_mouse.move(payload.get('dx', 0), payload.get('dy', 0))
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
        session_id = str(uuid.uuid4())
        hostname = socket.gethostname()
        cfg = load_config()

        with self.lock:
            self.current_session = {
                "session_id": session_id,
                "user": user,
                "hostname": hostname,
                "created_at": time.time(),
                "duration": duration,
                "expires_at": time.time() + duration,
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
# HTTP Request Handler
# ==========================================

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
            self.send_json({"base_url": f"http://{get_local_ip()}:{cfg.get('web_port', 1760)}", "app_root": "/"})
            return
        elif path.endswith('/auth'):
            topic_name = path.strip('/').split('/')[0] if '/' in path.strip('/') else "login"
            self.send_json({"topic": topic_name, "read": True, "write": True})
            return

        # 2. APK Download
        elif path in ('/apk', '/authenticator.apk', '/download'):
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
            self.send_json({
                "hostname": socket.gethostname(),
                "device_id": get_laptop_id(),
                "status": "ready",
                "smart_dual_mode": True,
                "offline_apk_ready": True,
                "paired_clients_count": len(cfg.get('paired_clients', {}))
            })
            return
        elif path == '/api/events':
            self.handle_sse()
            return
        elif path == '/api/wait_auth':
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
            self.send_json({
                "connected": is_connected,
                "phone": active_phone_state,
                "clients_count": len(cfg.get('paired_clients', {}))
            })
            return

        elif path == '/api/clipboard':
            self.send_json({"status": "ok", "text": get_kde_clipboard()})
            return

        elif path == '/api/media/status':
            self.send_json(get_mpris_status())
            return

        # 6. File Staging Download (PC to Phone pull)
        elif path.startswith('/api/files/staging/'):
            token = path.replace('/api/files/staging/', '').strip('/')
            staged = staged_files.get(token)
            if not staged or not os.path.exists(staged['filepath']):
                self.send_error(404, "Staged file not found or expired")
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
            except Exception as e:
                try: self.send_error(500, str(e))
                except Exception: pass
            return

        # 7. Phone File Browser Proxies (Linux Desktop -> Phone)
        elif path == '/api/phone/files/list':
            phone_ip, phone_port = get_phone_target()
            if not phone_ip:
                self.send_json({"error": "phone_not_connected", "message": "Phone is not connected or IP unknown"}, status=503)
                return
            req_path = qs.get('path', ['/storage/emulated/0'])[0]
            try:
                target_url = f"http://{phone_ip}:{phone_port}/api/files/list?path={quote(req_path)}"
                req = urllib.request.Request(target_url)
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

        elif path == '/api/phone/files/download':
            phone_ip, phone_port = get_phone_target()
            if not phone_ip:
                self.send_error(503, "Phone is not connected")
                return
            req_path = qs.get('path', [''])[0]
            try:
                target_url = f"http://{phone_ip}:{phone_port}/api/files/download?path={quote(req_path)}"
                req = urllib.request.Request(target_url)
                with urllib.request.urlopen(req, timeout=30) as resp:
                    self.send_response(200)
                    for h, v in resp.headers.items():
                        if h.lower() in ('content-type', 'content-length', 'content-disposition'):
                            self.send_header(h, v)
                    self.send_header('Access-Control-Allow-Origin', '*')
                    self.end_headers()
                    while chunk := resp.read(65536):
                        self.wfile.write(chunk)
            except Exception as e:
                try: self.send_error(502, f"Download error: {e}")
                except Exception: pass
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
            phone_ip, phone_port = get_phone_target()
            if not phone_ip:
                self.send_json({"error": "phone_not_connected"}, status=503)
                return
            target_path = qs.get('path', ['/storage/emulated/0/Download'])[0]
            raw_fn = self.headers.get('X-Filename', '')
            target_url = f"http://{phone_ip}:{phone_port}/api/files/upload?path={quote(target_path)}"
            try:
                length = int(self.headers.get('Content-Length', 0))
                data = self.rfile.read(length)
                req = urllib.request.Request(
                    target_url,
                    data=data,
                    headers={
                        'Content-Type': 'application/octet-stream',
                        'X-Filename': raw_fn
                    },
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

        # 3. Core PC Authenticator APIs
        if path == '/api/request_auth':
            user = body.get('user', os.environ.get('USER', 'lunarphoton'))
            cfg = load_config()
            timeout = cfg.get('auth_timeout_seconds', 35)
            session = auth_mgr.create_request(user, duration=timeout)
            self.send_json(session)

        elif path == '/api/pair/request':
            client_id = body.get('client_id', '')
            client_name = body.get('client_name', 'Android Phone')
            if not client_id:
                client_id = str(uuid.uuid4())
            pin = f"{random.randint(100000, 999999)}"
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
                subprocess.Popen(['notify-send', 'PC Authenticator - Pairing PIN', msg, '-u', 'critical', '-i', 'dialog-password'])
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
                cfg["paired_clients"][auth_token] = {
                    "client_id": client_id,
                    "client_name": client_name,
                    "secret_key": secret_key,
                    "paired_at": time.time(),
                    "ip": self.client_address[0]
                }
                save_config(cfg)
                active_phone_state['ip'] = self.client_address[0]
                active_phone_state['client_name'] = client_name
                active_phone_state['auth_token'] = auth_token
                try:
                    subprocess.Popen(['notify-send', 'PC Authenticator', f'✅ Successfully paired with {client_name}!', '-u', 'normal'])
                except Exception:
                    pass
                sys.stderr.write(f"✅ Successfully paired with {client_name} ({auth_token[:8]}...)\n")
                sys.stderr.flush()
                self.send_json({
                    "status": "ok",
                    "auth_token": auth_token,
                    "secret_key": secret_key,
                    "device_id": get_laptop_id(),
                    "hostname": socket.gethostname()
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
            active_phone_state['ip'] = self.client_address[0]
            active_phone_state['last_seen'] = time.time()
            self.send_json({"status": "ok"})

        # 5. Clipboard Sync
        elif path == '/api/clipboard':
            text = body.get('text', '')
            if text:
                set_kde_clipboard(text)
                # If triggered from local PC, broadcast to phone
                if self.client_address[0] in ('127.0.0.1', '::1', 'localhost'):
                    auth_mgr.broadcast_ndjson(json.dumps({"event": "clipboard", "text": text}) + "\n")
            self.send_json({"status": "ok"})

        # 6. Media Control
        elif path == '/api/media/command':
            cmd = body.get('command', '')
            success = send_mpris_command(cmd)
            self.send_json({"status": "ok" if success else "failed"})

        # 7. Ping (Ring PC)
        elif path == '/api/ping':
            ping_pc()
            self.send_json({"status": "ok"})

        # 8. Ring (Find My Phone)
        elif path == '/api/ring':
            phone_ip = body.get('phone_ip') or active_phone_state.get('ip')
            ring_phone(phone_ip=phone_ip)
            self.send_json({"status": "ok"})

        # 8b. Stop Ringing (Find My Phone)
        elif path == '/api/unring':
            phone_ip = body.get('phone_ip') or active_phone_state.get('ip')
            unring_phone(phone_ip=phone_ip)
            self.send_json({"status": "ok"})

        # 9. Remote Action (Lock, Suspend, Screen off)
        elif path == '/api/action':
            act = body.get('action', '')
            if act == 'lock':
                subprocess.Popen(['loginctl', 'lock-session'])
                self.send_json({"status": "ok", "action": "lock"})
            elif act == 'suspend':
                subprocess.Popen(['systemctl', 'suspend'])
                self.send_json({"status": "ok", "action": "suspend"})
            elif act == 'screen_off':
                subprocess.Popen(['kscreen-doctor', '--dpms', 'off'])
                self.send_json({"status": "ok", "action": "screen_off"})
            else:
                self.send_json({"status": "unknown_action"}, status=400)

        # 10. File Staging for PC-to-Phone send
        elif path == '/api/files/stage':
            filepath = body.get('filepath', '')
            if not filepath or not os.path.exists(filepath):
                self.send_json({"error": "file_not_found"}, status=404)
                return
            token = secrets.token_urlsafe(16)
            fn = os.path.basename(filepath)
            size = os.path.getsize(filepath)
            staged_files[token] = {
                "filepath": filepath,
                "filename": fn,
                "size": size,
                "created_at": time.time()
            }
            local_ip = get_local_ip()
            cfg = load_config()
            port = cfg.get('web_port', 1760)
            dl_url = f"http://{local_ip}:{port}/api/files/staging/{token}"
            auth_mgr.broadcast_ndjson(json.dumps({
                "event": "incoming_file",
                "filename": fn,
                "download_url": dl_url,
                "size": size
            }) + "\n")
            self.send_json({"status": "ok", "token": token, "download_url": dl_url})

        # 11. Phone Filesystem Mutations (Proxy)
        elif path == '/api/phone/files/delete':
            phone_ip, phone_port = get_phone_target()
            if not phone_ip:
                self.send_json({"error": "phone_not_connected"}, status=503)
                return
            target_path = body.get('path', '')
            try:
                target_url = f"http://{phone_ip}:{phone_port}/api/files/delete?path={quote(target_path)}"
                req = urllib.request.Request(target_url, data=b'{}', headers={'Content-Type': 'application/json'}, method='POST')
                with urllib.request.urlopen(req, timeout=5) as resp:
                    self.send_json({"status": "ok"})
            except Exception as e:
                self.send_json({"error": "delete_failed", "message": str(e)}, status=502)

        elif path == '/api/phone/files/mkdir':
            phone_ip, phone_port = get_phone_target()
            if not phone_ip:
                self.send_json({"error": "phone_not_connected"}, status=503)
                return
            parent_path = body.get('path', '')
            name = body.get('name', '')
            try:
                target_url = f"http://{phone_ip}:{phone_port}/api/files/mkdir?path={quote(parent_path)}&name={quote(name)}"
                req = urllib.request.Request(target_url, data=b'{}', headers={'Content-Type': 'application/json'}, method='POST')
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
                    set_kde_clipboard(url)
                    try:
                        subprocess.Popen(['notify-send', '-i', 'applications-internet', '-a', 'PC Connect', '🌐 Webpage Received', url], stderr=subprocess.DEVNULL)
                    except Exception:
                        pass
                    self.send_json({"status": "ok", "url": url})
                except Exception as e:
                    self.send_json({"error": str(e)}, status=500)
            else:
                self.send_json({"error": "invalid_url", "message": "URL must start with http:// or https://"}, status=400)

        # 13. Virtual Mouse / Trackpad (HTTP fallback)
        elif path == '/api/mouse':
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)
            if not client_info:
                self.send_json({"error": "unauthorized"}, status=401)
                return
            mtype = body.get('type', '')
            if mtype == 'move':
                virtual_mouse.move(body.get('dx', 0), body.get('dy', 0))
            elif mtype == 'click':
                virtual_mouse.click(body.get('button', 'left'))
            elif mtype == 'down':
                virtual_mouse.mouse_down(body.get('button', 'left'))
            elif mtype == 'up':
                virtual_mouse.mouse_up(body.get('button', 'left'))
            elif mtype == 'scroll':
                virtual_mouse.scroll(body.get('dy', 0), body.get('dx', 0))
            self.send_json({"status": "ok"})

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

        # 3. Stream loop with keepalive
        try:
            while True:
                try:
                    msg = q.get(timeout=15)
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
            self.send_header('Content-Disposition', 'attachment; filename="authenticator.apk"')
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
                    "user": os.environ.get("USER", "lunarphoton")
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
                    "user": os.environ.get("USER", "lunarphoton")
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

    start_udp_discovery_server(port)
    start_udp_beacon(port)
    start_udp_mouse_listener(1762)

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
        server.server_close()

if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] in ('--pair', 'pair'):
        cli_pair()
    else:
        main()
