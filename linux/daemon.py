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
from urllib.parse import urlparse, parse_qs
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(BASE_DIR, 'config.json')
STATIC_DIR = os.path.join(BASE_DIR, 'static')
APK_FILE = os.path.join(STATIC_DIR, 'authenticator.apk')

DEFAULT_CONFIG = {
    "web_port": 1760,
    "auth_timeout_seconds": 35,
    "failmode": "secure",
    "allowed_usb_serials": [],
    "paired_clients": {}
}

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
    paired = cfg.get('paired_clients', {})
    token = get_auth_token_from_request(handler)
    if not token or token not in paired:
        return None, token
    return paired[token], token

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
        
        # 1. Broadcast to Web PWA
        self.broadcast_sse("auth_request", self.current_session)

        # 2. Broadcast to Android App (ntfy-android protocol)
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

        # 1. ntfy Server Health Check
        if path in ('/v1/health', '/health'):
            self.send_json({"healthy": True, "schema": 1})
            return

        # 2. ntfy Server Config / Base URL check
        if path in ('/config', '/v1/config'):
            cfg = load_config()
            self.send_json({"base_url": f"http://{get_local_ip()}:{cfg.get('web_port', 1760)}", "app_root": "/"})
            return

        # 3. Topic Authorization Check (ntfy Android AddDialog queries /<topic>/auth)
        if path.endswith('/auth'):
            topic_name = path.strip('/').split('/')[0] if '/' in path.strip('/') else "login"
            self.send_json({"topic": topic_name, "read": True, "write": True})
            return

        # 4. APK Download
        if path in ('/apk', '/authenticator.apk', '/download'):
            self.serve_apk()
            return

        # 5. Static Files for Web Interface
        if path in ('/', '/index.html'):
            self.serve_file(os.path.join(STATIC_DIR, 'index.html'), 'text/html; charset=utf-8')
            return
        elif path == '/manifest.json':
            self.serve_file(os.path.join(STATIC_DIR, 'manifest.json'), 'application/manifest+json')
            return
        elif path == '/sw.js':
            self.serve_file(os.path.join(STATIC_DIR, 'sw.js'), 'application/javascript')
            return
        elif path == '/icon-192.png':
            self.serve_file(os.path.join(STATIC_DIR, 'icon-192.png'), 'image/png')
            return
        elif path == '/icon-512.png':
            self.serve_file(os.path.join(STATIC_DIR, 'icon-512.png'), 'image/png')
            return

        # 6. PC Authenticator API endpoints
        if path == '/api/info':
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

        # 7. Topic Streaming / Polling (ntfy-android protocol)
        # Check if this is a topic polling request (?poll=1)
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
        body = self.read_json()

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
                sys.stderr.write(f"SECURITY ALERT: Unauthorized approve attempt from {self.client_address[0]}\n")
                sys.stderr.flush()
                self.send_json({"status": "unauthorized", "message": "Device not paired. Pairing required."}, status=401)
                return

            sig = self.headers.get('X-Auth-Signature', '')
            ts_str = self.headers.get('X-Auth-Timestamp', '')
            try:
                ts = int(ts_str)
                now = int(time.time())
                if abs(now - ts) > 45:
                    self.send_json({"status": "forbidden", "message": "Timestamp expired (clock skew)"}, status=403)
                    return
            except Exception:
                self.send_json({"status": "forbidden", "message": "Invalid or missing timestamp header"}, status=403)
                return

            with auth_mgr.lock:
                current_sid = auth_mgr.current_session.get("session_id", "") if auth_mgr.current_session else ""

            if not current_sid:
                self.send_json({"status": "no_active_session"}, status=404)
                return

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
                sys.stderr.write(f"SECURITY ALERT: Signature mismatch from {self.client_address[0]} for {client_info.get('client_name')}\n")
                sys.stderr.flush()
                self.send_json({"status": "forbidden", "message": "Cryptographic signature mismatch"}, status=403)
                return

            if path == '/api/approve_current':
                ok = auth_mgr.approve_current()
            else:
                session_id = body.get('session_id') if isinstance(body, dict) else current_sid
                ok = auth_mgr.approve(session_id)
            self.send_json({"status": "ok" if ok else "error"})

        elif path in ('/api/deny_current', '/api/deny'):
            cfg = load_config()
            client_info, token = authenticate_client(self, cfg)

            if not client_info:
                sys.stderr.write(f"SECURITY ALERT: Unauthorized deny attempt from {self.client_address[0]}\n")
                sys.stderr.flush()
                self.send_json({"status": "unauthorized", "message": "Device not paired"}, status=401)
                return

            sig = self.headers.get('X-Auth-Signature', '')
            ts_str = self.headers.get('X-Auth-Timestamp', '')
            try:
                ts = int(ts_str)
                now = int(time.time())
                if abs(now - ts) > 45:
                    self.send_json({"status": "forbidden", "message": "Timestamp expired"}, status=403)
                    return
            except Exception:
                self.send_json({"status": "forbidden", "message": "Invalid timestamp"}, status=403)
                return

            with auth_mgr.lock:
                current_sid = auth_mgr.current_session.get("session_id", "") if auth_mgr.current_session else ""

            if not current_sid:
                self.send_json({"status": "no_active_session"}, status=404)
                return

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
                session_id = body.get('session_id') if isinstance(body, dict) else current_sid
                ok = auth_mgr.deny(session_id)
            self.send_json({"status": "ok" if ok else "error"})

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

        # 2. If a challenge is already pending, push it immediately
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
            try:
                self.send_error(500, str(e))
            except Exception:
                pass

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
            try:
                self.send_error(500, str(e))
            except Exception:
                pass

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

                # Announce directly to the hotspot gateway (the phone)
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

    server = ThreadingHTTPServer(('0.0.0.0', port), AuthenticatorHandler)
    sys.stderr.write("==================================================\n")
    sys.stderr.write(f"  PC Authenticator Daemon Running on port {port}\n")
    sys.stderr.write(f"  Device ID: {get_laptop_id()}\n")
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
        import urllib.request
        cli_pair()
    else:
        main()
