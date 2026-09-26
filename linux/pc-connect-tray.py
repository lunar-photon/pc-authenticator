#!/usr/bin/env python3
import sys
import os
import json
import urllib.request
import urllib.error
from urllib.parse import quote, unquote
import subprocess
import time

from PyQt6.QtWidgets import (
    QApplication, QSystemTrayIcon, QMenu, QMainWindow, QWidget,
    QVBoxLayout, QHBoxLayout, QTableWidget, QTableWidgetItem,
    QPushButton, QLabel, QLineEdit, QFileDialog, QMessageBox,
    QHeaderView, QListWidget, QListWidgetItem, QAbstractItemView,
    QInputDialog, QProgressBar
)
from PyQt6.QtCore import Qt, QTimer, QThread, pyqtSignal, QSize
from PyQt6.QtGui import QIcon, QAction, QFont, QColor, QDragEnterEvent, QDropEvent

DAEMON_URL = "http://127.0.0.1:1760"

def fetch_json(endpoint, timeout=3):
    try:
        req = urllib.request.Request(f"{DAEMON_URL}{endpoint}")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except Exception as e:
        return {"error": str(e)}

def post_json(endpoint, data=None, timeout=5):
    try:
        body = json.dumps(data or {}).encode('utf-8')
        req = urllib.request.Request(f"{DAEMON_URL}{endpoint}", data=body, headers={'Content-Type': 'application/json'}, method='POST')
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except Exception as e:
        return {"error": str(e)}

def format_size(bytes_num):
    if not bytes_num:
        return "-"
    for unit in ['B', 'KB', 'MB', 'GB', 'TB']:
        if bytes_num < 1024.0:
            return f"{bytes_num:.1f} {unit}"
        bytes_num /= 1024.0
    return f"{bytes_num:.1f} PB"

def format_time(ts):
    if not ts:
        return "-"
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(ts))

# ==========================================
# Phone File Explorer Window
# ==========================================

class PhoneExplorerWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("PC Connect - Phone File Explorer")
        self.resize(950, 600)
        self.setAcceptDrops(True)

        self.current_path = "/storage/emulated/0"
        self.history = [self.current_path]
        self.history_idx = 0

        self.setup_ui()
        self.load_directory(self.current_path)

    def setup_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QHBoxLayout(central)
        main_layout.setContentsMargins(0, 0, 0, 0)
        main_layout.setSpacing(0)

        # 1. Left Sidebar
        sidebar = QWidget()
        sidebar.setFixedWidth(220)
        sidebar.setStyleSheet("background-color: #1e222d; border-right: 1px solid #2e3440;")
        sidebar_layout = QVBoxLayout(sidebar)
        sidebar_layout.setContentsMargins(10, 15, 10, 15)

        title_lbl = QLabel("📱 PHONE STORAGE")
        title_lbl.setStyleSheet("color: #88c0d0; font-weight: bold; font-size: 11px;")
        sidebar_layout.addWidget(title_lbl)

        self.bookmark_list = QListWidget()
        self.bookmark_list.setStyleSheet("""
            QListWidget { background: transparent; border: none; outline: none; }
            QListWidget::item { padding: 8px 12px; color: #eceff4; border-radius: 6px; }
            QListWidget::item:hover { background-color: #2e3440; }
            QListWidget::item:selected { background-color: #3b4252; color: #88c0d0; font-weight: bold; }
        """)

        bookmarks = [
            ("📱 Internal Storage", "/storage/emulated/0"),
            ("📷 Camera & DCIM", "/storage/emulated/0/DCIM"),
            ("📥 Downloads", "/storage/emulated/0/Download"),
            ("📄 Documents", "/storage/emulated/0/Documents"),
            ("🖼️ Pictures", "/storage/emulated/0/Pictures"),
            ("🎵 Music", "/storage/emulated/0/Music"),
            ("🎬 Movies", "/storage/emulated/0/Movies")
        ]
        for label, path in bookmarks:
            item = QListWidgetItem(label)
            item.setData(Qt.ItemDataRole.UserRole, path)
            self.bookmark_list.addItem(item)
        self.bookmark_list.itemClicked.connect(self.on_bookmark_clicked)
        sidebar_layout.addWidget(self.bookmark_list)
        sidebar_layout.addStretch()

        # Ring & Stop Phone button in sidebar
        btn_ring = QPushButton("🔔 Ring Phone")
        btn_ring.setStyleSheet("background-color: #3b4252; color: #eceff4; border-radius: 6px; padding: 8px;")
        btn_ring.clicked.connect(lambda: post_json("/api/ring"))
        sidebar_layout.addWidget(btn_ring)

        btn_unring = QPushButton("🛑 Stop Ringing")
        btn_unring.setStyleSheet("background-color: #4c1d24; color: #ff8080; border-radius: 6px; padding: 6px;")
        btn_unring.clicked.connect(lambda: post_json("/api/unring"))
        sidebar_layout.addWidget(btn_unring)

        main_layout.addWidget(sidebar)

        # 2. Right Content Area
        content = QWidget()
        content_layout = QVBoxLayout(content)
        content_layout.setContentsMargins(15, 15, 15, 15)
        content_layout.setSpacing(10)

        # Navigation Bar
        nav_layout = QHBoxLayout()
        self.btn_back = QPushButton("◀")
        self.btn_back.setFixedWidth(36)
        self.btn_back.clicked.connect(self.navigate_back)
        self.btn_up = QPushButton("⬆ Up")
        self.btn_up.clicked.connect(self.navigate_up)
        self.btn_refresh = QPushButton("🔄 Refresh")
        self.btn_refresh.clicked.connect(lambda: self.load_directory(self.current_path))

        self.path_edit = QLineEdit(self.current_path)
        self.path_edit.returnPressed.connect(self.on_path_entered)
        self.path_edit.setStyleSheet("padding: 6px 10px; border-radius: 6px; background: #2e3440; color: #eceff4; border: 1px solid #434c5e;")

        nav_layout.addWidget(self.btn_back)
        nav_layout.addWidget(self.btn_up)
        nav_layout.addWidget(self.btn_refresh)
        nav_layout.addWidget(self.path_edit)
        content_layout.addLayout(nav_layout)

        # Drop Zone & Progress Indicator
        self.status_bar = QLabel("Double-click any folder to open, or double-click a file to download to PC Downloads.")
        self.status_bar.setStyleSheet("color: #88c0d0; font-size: 12px; padding: 4px;")
        content_layout.addWidget(self.status_bar)

        # File Table
        self.table = QTableWidget(0, 3)
        self.table.setHorizontalHeaderLabels(["Name", "Size", "Modified"])
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        self.table.horizontalHeader().setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.setStyleSheet("""
            QTableWidget { background-color: #242933; color: #eceff4; border: 1px solid #2e3440; border-radius: 8px; }
            QHeaderView::section { background-color: #1e222d; color: #88c0d0; padding: 6px; border: none; font-weight: bold; }
            QTableWidget::item { padding: 6px; }
            QTableWidget::item:selected { background-color: #3b4252; color: #88c0d0; }
        """)
        self.table.cellDoubleClicked.connect(self.on_cell_double_clicked)
        self.table.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.table.customContextMenuRequested.connect(self.show_context_menu)
        content_layout.addWidget(self.table)

        # Action Buttons Bottom
        btn_layout = QHBoxLayout()
        self.btn_download = QPushButton("📥 Download to PC")
        self.btn_download.setStyleSheet("background-color: #88c0d0; color: #1e222d; font-weight: bold; padding: 8px 16px; border-radius: 6px;")
        self.btn_download.clicked.connect(self.download_selected)

        self.btn_upload = QPushButton("📤 Upload Files...")
        self.btn_upload.setStyleSheet("background-color: #434c5e; color: #eceff4; padding: 8px 16px; border-radius: 6px;")
        self.btn_upload.clicked.connect(self.upload_files)

        self.btn_mkdir = QPushButton("📁 New Folder")
        self.btn_mkdir.setStyleSheet("background-color: #434c5e; color: #eceff4; padding: 8px 16px; border-radius: 6px;")
        self.btn_mkdir.clicked.connect(self.create_folder)

        self.btn_delete = QPushButton("🗑️ Delete")
        self.btn_delete.setStyleSheet("background-color: #bf616a; color: #eceff4; padding: 8px 16px; border-radius: 6px;")
        self.btn_delete.clicked.connect(self.delete_selected)

        btn_layout.addWidget(self.btn_download)
        btn_layout.addWidget(self.btn_upload)
        btn_layout.addWidget(self.btn_mkdir)
        btn_layout.addStretch()
        btn_layout.addWidget(self.btn_delete)
        content_layout.addLayout(btn_layout)

        main_layout.addWidget(content)

    def on_bookmark_clicked(self, item):
        path = item.data(Qt.ItemDataRole.UserRole)
        if path:
            self.load_directory(path)

    def on_path_entered(self):
        path = self.path_edit.text().strip()
        if path:
            self.load_directory(path)

    def navigate_back(self):
        if self.history_idx > 0:
            self.history_idx -= 1
            self.load_directory(self.history[self.history_idx], record_history=False)

    def navigate_up(self):
        if self.current_path in ("/", "/storage/emulated/0"):
            return
        parent = os.path.dirname(self.current_path.rstrip('/'))
        if parent:
            self.load_directory(parent)

    def load_directory(self, path, record_history=True):
        self.current_path = path
        self.path_edit.setText(path)
        if record_history:
            if not self.history or self.history[-1] != path:
                self.history.append(path)
                self.history_idx = len(self.history) - 1

        self.status_bar.setText(f"Loading {path}...")
        QApplication.processEvents()

        data = fetch_json(f"/api/phone/files/list?path={quote(path)}")
        if isinstance(data, dict) and "error" in data:
            self.status_bar.setText(f"❌ Error: {data.get('message', data['error'])}")
            self.table.setRowCount(0)
            return

        self.table.setRowCount(0)
        files = data if isinstance(data, list) else []
        self.status_bar.setText(f"📁 {len(files)} items in {path}")

        for f in files:
            row = self.table.rowCount()
            self.table.insertRow(row)

            is_dir = f.get('is_dir', False)
            icon = "📁" if is_dir else "📄"
            name = f.get('name', '')
            size = format_size(f.get('size', 0)) if not is_dir else "-"
            mtime = format_time(f.get('mtime', 0))

            item_name = QTableWidgetItem(f"{icon}  {name}")
            item_name.setData(Qt.ItemDataRole.UserRole, f)
            item_size = QTableWidgetItem(size)
            item_mtime = QTableWidgetItem(mtime)

            self.table.setItem(row, 0, item_name)
            self.table.setItem(row, 1, item_size)
            self.table.setItem(row, 2, item_mtime)

    def on_cell_double_clicked(self, row, col):
        item = self.table.item(row, 0)
        if not item:
            return
        f = item.data(Qt.ItemDataRole.UserRole)
        if f.get('is_dir'):
            self.load_directory(f.get('path'))
        else:
            self.download_file(f)

    def show_context_menu(self, pos):
        item = self.table.itemAt(pos)
        if not item:
            return
        row = item.row()
        f_item = self.table.item(row, 0)
        f = f_item.data(Qt.ItemDataRole.UserRole)

        menu = QMenu(self)
        if not f.get('is_dir'):
            act_dl = menu.addAction("📥 Download to PC")
            act_dl.triggered.connect(lambda: self.download_file(f))
        act_del = menu.addAction("🗑️ Delete")
        act_del.triggered.connect(lambda: self.delete_file(f))
        menu.exec(self.table.viewport().mapToGlobal(pos))

    def download_selected(self):
        rows = set(index.row() for index in self.table.selectedIndexes())
        for row in rows:
            f = self.table.item(row, 0).data(Qt.ItemDataRole.UserRole)
            if not f.get('is_dir'):
                self.download_file(f)

    def download_file(self, f):
        fn = f.get('name', 'file')
        dl_dir = os.path.expanduser('~/Downloads')
        dest = os.path.join(dl_dir, fn)
        base, ext = os.path.splitext(fn)
        c = 1
        while os.path.exists(dest):
            dest = os.path.join(dl_dir, f"{base} ({c}){ext}")
            c += 1

        self.status_bar.setText(f"⏳ Downloading {fn}...")
        QApplication.processEvents()

        try:
            url = f"{DAEMON_URL}/api/phone/files/download?path={quote(f.get('path'))}"
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=30) as resp:
                with open(dest, 'wb') as out_f:
                    while chunk := resp.read(65536):
                        out_f.write(chunk)
            self.status_bar.setText(f"✅ Downloaded to {dest}")
            subprocess.Popen(['notify-send', '-i', 'document-save', '-a', 'PC Connect', 'File Downloaded', f"Saved to {dest}"])
        except Exception as e:
            self.status_bar.setText(f"❌ Download failed: {e}")
            QMessageBox.critical(self, "Download Error", str(e))

    def upload_files(self):
        paths, _ = QFileDialog.getOpenFileNames(self, "Select Files to Upload to Phone")
        if not paths:
            return
        for p in paths:
            self.do_upload(p)
        self.load_directory(self.current_path)

    def do_upload(self, filepath):
        fn = os.path.basename(filepath)
        self.status_bar.setText(f"⏳ Uploading {fn} to phone...")
        QApplication.processEvents()
        try:
            url = f"{DAEMON_URL}/api/phone/files/upload?path={quote(self.current_path)}"
            with open(filepath, 'rb') as f:
                data = f.read()
            req = urllib.request.Request(
                url,
                data=data,
                headers={'Content-Type': 'application/octet-stream', 'X-Filename': quote(fn)},
                method='POST'
            )
            with urllib.request.urlopen(req, timeout=30) as resp:
                self.status_bar.setText(f"✅ Uploaded {fn}")
        except Exception as e:
            self.status_bar.setText(f"❌ Upload error: {e}")

    def create_folder(self):
        name, ok = QInputDialog.getText(self, "New Folder", "Enter folder name:")
        if ok and name.strip():
            post_json("/api/phone/files/mkdir", {"path": self.current_path, "name": name.strip()})
            self.load_directory(self.current_path)

    def delete_selected(self):
        rows = set(index.row() for index in self.table.selectedIndexes())
        for row in rows:
            f = self.table.item(row, 0).data(Qt.ItemDataRole.UserRole)
            self.delete_file(f)

    def delete_file(self, f):
        fn = f.get('name')
        ans = QMessageBox.question(self, "Confirm Delete", f"Are you sure you want to delete:\n{fn}?", QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No)
        if ans == QMessageBox.StandardButton.Yes:
            post_json("/api/phone/files/delete", {"path": f.get('path')})
            self.load_directory(self.current_path)

    # Drag and Drop support
    def dragEnterEvent(self, event: QDragEnterEvent):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event: QDropEvent):
        urls = event.mimeData().urls()
        for url in urls:
            path = url.toLocalFile()
            if os.path.isfile(path):
                self.do_upload(path)
        self.load_directory(self.current_path)

# ==========================================
# System Tray Application
# ==========================================

class PCConnectTrayApp:
    def __init__(self):
        self.app = QApplication(sys.argv)
        self.app.setQuitOnLastWindowClosed(False)

        # System Tray Icon
        self.tray = QSystemTrayIcon()
        self.tray.setIcon(QIcon.fromTheme("smartphone", QIcon.fromTheme("phone")))
        self.tray.setVisible(True)

        self.explorer_window = None

        self.menu = QMenu()
        self.build_menu()
        self.tray.setContextMenu(self.menu)

        # Polling Timer for status
        self.timer = QTimer()
        self.timer.timeout.connect(self.update_status)
        self.timer.start(4000)
        self.update_status()

    def build_menu(self):
        self.menu.clear()

        self.act_status = self.menu.addAction("📱 Checking Phone Status...")
        self.act_status.setEnabled(False)

        self.menu.addSeparator()

        act_browse = self.menu.addAction("📂 Browse Phone Files...")
        act_browse.triggered.connect(self.open_explorer)

        act_send = self.menu.addAction("📤 Send File to Phone...")
        act_send.triggered.connect(self.send_file_dialog)

        act_clip = self.menu.addAction("📋 Copy PC Clipboard to Phone")
        act_clip.triggered.connect(self.sync_clipboard)

        act_ring = self.menu.addAction("🔔 Find My Phone (Ring)")
        act_ring.triggered.connect(lambda: post_json("/api/ring"))

        act_unring = self.menu.addAction("🛑 Stop Ringing Phone")
        act_unring.triggered.connect(lambda: post_json("/api/unring"))

        act_lock = self.menu.addAction("🔒 Lock PC Screen")
        act_lock.triggered.connect(lambda: post_json("/api/action", {"action": "lock"}))

        self.menu.addSeparator()

        act_web = self.menu.addAction("🌐 Open Web Explorer")
        act_web.triggered.connect(lambda: subprocess.Popen(["xdg-open", f"{DAEMON_URL}/browse"]))

        act_pair = self.menu.addAction("➕ Pair New Device...")
        act_pair.triggered.connect(lambda: subprocess.Popen(["pc-auth", "--pair"]))

        self.menu.addSeparator()

        act_quit = self.menu.addAction("🚪 Exit PC Connect")
        act_quit.triggered.connect(self.app.quit)

    def update_status(self):
        st = fetch_json("/api/phone/status")
        if isinstance(st, dict) and "phone" in st:
            phone = st["phone"]
            connected = st.get("connected", False)
            name = phone.get("client_name", "Android Phone")
            bat = phone.get("battery_level")
            charging = phone.get("is_charging", False)

            bat_str = f" • {bat}%{' ⚡' if charging else ''}" if bat is not None else ""
            status_text = f"🟢 {name}{bat_str}" if connected else f"⚪ {name} (Idle)"
            self.act_status.setText(status_text)
            self.tray.setToolTip(f"PC Connect: {status_text}")
        else:
            self.act_status.setText("⚠️ Daemon Offline")
            self.tray.setToolTip("PC Connect: Daemon Offline")

    def open_explorer(self):
        if not self.explorer_window:
            self.explorer_window = PhoneExplorerWindow()
        self.explorer_window.show()
        self.explorer_window.raise_()
        self.explorer_window.activateWindow()

    def send_file_dialog(self):
        files, _ = QFileDialog.getOpenFileNames(None, "Send Files to Phone")
        if files:
            send_bin = os.path.expanduser("~/.local/bin/pc-connect-send")
            subprocess.Popen([send_bin] + files)

    def sync_clipboard(self):
        post_json("/api/clipboard")
        subprocess.Popen(['notify-send', '-i', 'edit-paste', '-a', 'PC Connect', 'Clipboard Synced', 'PC clipboard sent to phone'])

    def run(self):
        if "--browse" in sys.argv:
            self.open_explorer()
        return self.app.exec()

def main():
    app = PCConnectTrayApp()
    sys.exit(app.run())

if __name__ == '__main__':
    main()
