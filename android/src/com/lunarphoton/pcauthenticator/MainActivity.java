package com.lunarphoton.pcauthenticator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

public class MainActivity extends Activity {
    private TextView tvHostname;
    private TextView tvActiveIp;
    private TextView tvStatusDot;
    private TextView tvStatusText;
    private Button btnTestConnection;

    // KDE Connect Feature UI references
    private static final int REQUEST_PICK_FILE = 4001;
    private Button btnSendFileToPc;
    private Button btnSendClipToPc;
    private Button btnGetClipFromPc;
    private Button btnRingPc;
    private Button btnLockPc;
    private Button btnRemoteTrackpad;
    private Button btnOpenWebpage;

    private TextView tvMediaStatus;
    private TextView tvMediaTitle;
    private TextView tvMediaArtist;
    private Button btnMediaPrev;
    private Button btnMediaPlayPause;
    private Button btnMediaNext;
    private Button btnMediaVolDown;
    private Button btnMediaVolUp;

    private LinearLayout layoutChallenge;
    private TextView tvChallengeMessage;
    private TextView tvTimer;
    private Button btnApproveChallenge;
    private Button btnDenyChallenge;

    private Button btnSettings;

    private CancellationSignal cancellationSignal = null;
    private boolean isPromptShowing = false;

    private LinearLayout layoutIdle;

    private TextView tvDeviceCount;
    private Button btnScanNetwork;
    private Button btnAddManual;
    private TextView tvScanStatus;
    private LinearLayout containerDevices;

    private CountDownTimer countDownTimer;
    private Handler pollHandler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (AuthService.ACTION_CHALLENGE.equals(action)) {
                String message = intent.getStringExtra("message");
                showChallenge(message);
                if (isBiometricRequiredAndSupported()) {
                    btnApproveChallenge.postDelayed(MainActivity.this::promptBiometricAuthentication, 350);
                }
            } else if (AuthService.ACTION_STATUS.equals(action)) {
                boolean connected = intent.getBooleanExtra("connected", false);
                String statusText = intent.getStringExtra("status_text");
                updateConnectionStatus(connected, statusText);
            } else if ("com.lunarphoton.pcauthenticator.CHALLENGE_RESOLVED".equals(action)) {
                hideChallenge();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Allow Activity to show and turn on screen over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_main);

        // UI references
        tvHostname = findViewById(R.id.tv_hostname);
        tvActiveIp = findViewById(R.id.tv_active_ip);
        tvStatusDot = findViewById(R.id.tv_status_dot);
        tvStatusText = findViewById(R.id.tv_status_text);
        btnTestConnection = findViewById(R.id.btn_test_connection);

        layoutChallenge = findViewById(R.id.layout_challenge);
        tvChallengeMessage = findViewById(R.id.tv_challenge_message);
        tvTimer = findViewById(R.id.tv_timer);
        btnApproveChallenge = findViewById(R.id.btn_approve_challenge);
        btnDenyChallenge = findViewById(R.id.btn_deny_challenge);

        layoutIdle = findViewById(R.id.layout_idle);

        tvDeviceCount = findViewById(R.id.tv_device_count);
        btnScanNetwork = findViewById(R.id.btn_scan_network);
        btnAddManual = findViewById(R.id.btn_add_manual);
        tvScanStatus = findViewById(R.id.tv_scan_status);
        containerDevices = findViewById(R.id.container_devices);

        // Bind KDE Connect UI
        btnSendFileToPc = findViewById(R.id.btn_send_file_to_pc);
        btnSendClipToPc = findViewById(R.id.btn_send_clip_to_pc);
        btnGetClipFromPc = findViewById(R.id.btn_get_clip_from_pc);
        btnRingPc = findViewById(R.id.btn_ring_pc);
        btnLockPc = findViewById(R.id.btn_lock_pc);
        btnRemoteTrackpad = findViewById(R.id.btn_remote_trackpad);
        btnOpenWebpage = findViewById(R.id.btn_open_webpage);

        tvMediaStatus = findViewById(R.id.tv_media_status);
        tvMediaTitle = findViewById(R.id.tv_media_title);
        tvMediaArtist = findViewById(R.id.tv_media_artist);
        btnMediaPrev = findViewById(R.id.btn_media_prev);
        btnMediaPlayPause = findViewById(R.id.btn_media_play_pause);
        btnMediaNext = findViewById(R.id.btn_media_next);
        btnMediaVolDown = findViewById(R.id.btn_media_voldown);
        btnMediaVolUp = findViewById(R.id.btn_media_volup);

        setupKdeConnectListeners();

        // Header Settings Button
        btnSettings = findViewById(R.id.btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsDialog());
        }
        updateApproveButtonText();

        // Listeners
        btnTestConnection.setOnClickListener(v -> testActiveConnection());
        btnScanNetwork.setOnClickListener(v -> startNetworkScan());
        btnAddManual.setOnClickListener(v -> showAddDeviceDialog());

        btnApproveChallenge.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE);
            long expiry = prefs.getLong("active_challenge_expiry", 0);
            String activeId = prefs.getString("active_challenge_id", null);
            if (activeId == null || System.currentTimeMillis() >= expiry) {
                Toast.makeText(MainActivity.this, "Challenge expired or already handled", Toast.LENGTH_SHORT).show();
                hideChallenge();
                AuthService.cancelChallengeNotification(MainActivity.this);
                return;
            }
            if (isBiometricRequiredAndSupported()) {
                promptBiometricAuthentication();
            } else {
                executeApprove();
            }
        });

        btnDenyChallenge.setOnClickListener(v -> {
            vibrate(200);
            PairedDevice active = DeviceManager.getActiveDevice(this);
            String url = active.getBaseUrl();
            SharedPreferences prefs = getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE);
            String activeChallengeId = prefs.getString("active_challenge_id", "");

            AuthService.cancelChallengeNotification(MainActivity.this);
            hideChallenge();

            new Thread(() -> {
                NetworkUtils.httpPostWithAuth(url + "/api/deny_current", active.authToken, active.secretKey, activeChallengeId, "deny", 5000);
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "❌ Unlock Denied", Toast.LENGTH_SHORT).show();
                });
            }).start();
        });

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        // Request battery optimization exemption for uninterrupted background connection
        checkBatteryOptimization();

        // Register receiver for background challenge alerts
        IntentFilter filter = new IntentFilter();
        filter.addAction(AuthService.ACTION_CHALLENGE);
        filter.addAction(AuthService.ACTION_STATUS);
        filter.addAction("com.lunarphoton.pcauthenticator.CHALLENGE_RESOLVED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReceiver, filter);
        }

        // Refresh device list and start background service
        refreshDeviceList();
        startAuthService();

        // Check active challenge immediately from persistent store and intent
        updateChallengeUIFromStore();
        handleIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        updateChallengeUIFromStore();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        updateChallengeUIFromStore();
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("from_challenge", false)) {
            return;
        }

        // Consume and strip extras immediately to prevent repeat triggers
        boolean autoBio = intent.getBooleanExtra("auto_biometric", false);
        String msg = intent.getStringExtra("message");
        intent.removeExtra("from_challenge");
        intent.removeExtra("auto_biometric");
        intent.removeExtra("message");
        intent.removeExtra("session_id");
        setIntent(new Intent(this, MainActivity.class));

        // Verify active challenge status from local store
        SharedPreferences prefs = getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE);
        long expiry = prefs.getLong("active_challenge_expiry", 0);
        String activeId = prefs.getString("active_challenge_id", null);
        long now = System.currentTimeMillis();

        if (activeId == null || now >= expiry) {
            Log.d("MainActivity", "handleIntent: Stale or expired challenge ignored");
            hideChallenge();
            cancelBiometricPrompt();
            AuthService.cancelChallengeNotification(this);
            return;
        }

        int remainingSec = Math.max(1, (int) ((expiry - now) / 1000L));
        if (msg == null || msg.isEmpty()) {
            msg = prefs.getString("active_challenge_msg", "Screen unlock requested on your laptop. Tap Approve to unlock.");
        }
        showChallengeWithDuration(msg, remainingSec);

        if (autoBio && isBiometricRequiredAndSupported()) {
            btnApproveChallenge.postDelayed(this::promptBiometricAuthentication, 350);
        }
    }

    private void updateChallengeUIFromStore() {
        SharedPreferences prefs = getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE);
        long expiry = prefs.getLong("active_challenge_expiry", 0);
        String activeId = prefs.getString("active_challenge_id", null);
        long now = System.currentTimeMillis();
        if (activeId != null && now < expiry) {
            String msg = prefs.getString("active_challenge_msg", "Screen unlock requested on your laptop. Tap Approve to unlock.");
            int remainingSec = (int) ((expiry - now) / 1000L);
            showChallengeWithDuration(msg, Math.max(1, remainingSec));
        } else {
            hideChallenge();
            AuthService.cancelChallengeNotification(this);
        }
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    private void startAuthService() {
        Intent serviceIntent = new Intent(this, AuthService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void reconnectService() {
        Intent serviceIntent = new Intent(this, AuthService.class);
        serviceIntent.setAction(AuthService.ACTION_RECONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void refreshDeviceList() {
        List<PairedDevice> devices = DeviceManager.getDevices(this);
        PairedDevice active = DeviceManager.getActiveDevice(this);

        tvHostname.setText(active.hostname);
        tvActiveIp.setText(active.getBaseUrl());
        tvDeviceCount.setText(devices.size() + " Saved");

        containerDevices.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (PairedDevice d : devices) {
            View itemView = inflater.inflate(R.layout.item_device, containerDevices, false);

            TextView tvHost = itemView.findViewById(R.id.tv_item_hostname);
            TextView tvIp = itemView.findViewById(R.id.tv_item_ip);
            TextView tvStatus = itemView.findViewById(R.id.tv_item_status);
            Button btnSelect = itemView.findViewById(R.id.btn_select_device);
            Button btnDelete = itemView.findViewById(R.id.btn_delete_device);

            tvHost.setText(d.hostname + (d.user != null && !d.user.isEmpty() ? " (" + d.user + ")" : ""));
            tvIp.setText(d.ip + ":" + d.port);

            if (d.isPaired()) {
                if (d.isActive) {
                    tvStatus.setText("🟢 Active Target • 🔒 E2E Paired");
                    tvStatus.setTextColor(Color.parseColor("#10b981"));
                    btnSelect.setVisibility(View.GONE);
                } else {
                    tvStatus.setText("⚪ Inactive • 🔒 E2E Paired");
                    tvStatus.setTextColor(Color.parseColor("#94a3b8"));
                    btnSelect.setVisibility(View.VISIBLE);
                    btnSelect.setText("Select");
                    btnSelect.setOnClickListener(v -> {
                        DeviceManager.setActiveDevice(MainActivity.this, d.ip);
                        reconnectService();
                        refreshDeviceList();
                        testActiveConnection();
                        Toast.makeText(MainActivity.this, "Switched to " + d.hostname, Toast.LENGTH_SHORT).show();
                    });
                }
            } else {
                tvStatus.setText("⚠️ Not Paired (Tap 'Pair' to Authorize)");
                tvStatus.setTextColor(Color.parseColor("#f59e0b"));
                btnSelect.setVisibility(View.VISIBLE);
                btnSelect.setText("Pair 🔑");
                btnSelect.setOnClickListener(v -> showPairDialog(d));
            }

            btnDelete.setOnClickListener(v -> {
                if (devices.size() <= 1) {
                    Toast.makeText(MainActivity.this, "Cannot remove the only paired laptop", Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Remove Laptop")
                        .setMessage("Remove " + d.hostname + " (" + d.ip + ") from your paired devices?")
                        .setPositiveButton("Remove", (dialog, which) -> {
                            DeviceManager.removeDevice(MainActivity.this, d.ip);
                            reconnectService();
                            refreshDeviceList();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });

            containerDevices.addView(itemView);
        }
    }

    private void showPairDialog(PairedDevice device) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pair, null);
        builder.setView(dialogView);

        TextView tvTarget = dialogView.findViewById(R.id.tv_pair_target);
        EditText etPin = dialogView.findViewById(R.id.et_pair_pin);
        TextView tvStatus = dialogView.findViewById(R.id.tv_pair_status);
        Button btnResend = dialogView.findViewById(R.id.btn_pair_resend);

        tvTarget.setText(device.hostname + " (" + device.ip + ":" + device.port + ")");

        builder.setPositiveButton("Pair & Authorize", null);
        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.show();

        SharedPreferences prefs = getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE);
        String clientId = prefs.getString("client_uuid", null);
        if (clientId == null) {
            clientId = java.util.UUID.randomUUID().toString();
            prefs.edit().putString("client_uuid", clientId).apply();
        }
        final String finalClientId = clientId;
        final String clientName = Build.MANUFACTURER + " " + Build.MODEL;

        Runnable requestPinRunnable = () -> {
            tvStatus.setTextColor(Color.parseColor("#06b6d4"));
            tvStatus.setText("Requesting pairing PIN from " + device.hostname + "...");
            new Thread(() -> {
                try {
                    JSONObject req = new JSONObject();
                    req.put("client_id", finalClientId);
                    req.put("client_name", clientName);
                    String res = NetworkUtils.httpPostJson(device.getBaseUrl() + "/api/pair/request", req.toString(), 4000);
                    runOnUiThread(() -> {
                        if (res != null) {
                            tvStatus.setTextColor(Color.parseColor("#10b981"));
                            tvStatus.setText("👉 6-digit PIN sent to your laptop screen!");
                        } else {
                            tvStatus.setTextColor(Color.parseColor("#ef4444"));
                            tvStatus.setText("Cannot connect to " + device.hostname);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        tvStatus.setTextColor(Color.parseColor("#ef4444"));
                        tvStatus.setText("Error: " + e.getMessage());
                    });
                }
            }).start();
        };

        requestPinRunnable.run();
        btnResend.setOnClickListener(v -> requestPinRunnable.run());

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = etPin.getText().toString().trim();
            if (pin.length() < 6) {
                tvStatus.setTextColor(Color.parseColor("#ef4444"));
                tvStatus.setText("Please enter the 6-digit PIN shown on screen");
                return;
            }

            tvStatus.setTextColor(Color.parseColor("#06b6d4"));
            tvStatus.setText("Verifying PIN with laptop...");

            new Thread(() -> {
                try {
                    JSONObject req = new JSONObject();
                    req.put("client_id", finalClientId);
                    req.put("client_name", clientName);
                    req.put("pin", pin);
                    String res = NetworkUtils.httpPostJson(device.getBaseUrl() + "/api/pair/confirm", req.toString(), 5000);
                    runOnUiThread(() -> {
                        if (res != null) {
                            try {
                                JSONObject json = new JSONObject(res);
                                if ("ok".equals(json.optString("status"))) {
                                    device.authToken = json.getString("auth_token");
                                    device.secretKey = json.getString("secret_key");
                                    device.deviceId = json.optString("device_id", device.deviceId);
                                    device.hostname = json.optString("hostname", device.hostname);
                                    DeviceManager.addOrUpdateDevice(MainActivity.this, device);
                                    DeviceManager.setActiveDevice(MainActivity.this, device.ip);
                                    reconnectService();
                                    refreshDeviceList();
                                    testActiveConnection();
                                    dialog.dismiss();
                                    Toast.makeText(MainActivity.this, "✅ Successfully Paired with " + device.hostname + "!", Toast.LENGTH_LONG).show();
                                } else {
                                    String msg = json.optString("message", "Incorrect PIN");
                                    tvStatus.setTextColor(Color.parseColor("#ef4444"));
                                    tvStatus.setText("❌ " + msg);
                                }
                            } catch (Exception e) {
                                tvStatus.setTextColor(Color.parseColor("#ef4444"));
                                tvStatus.setText("Invalid response: " + e.getMessage());
                            }
                        } else {
                            tvStatus.setTextColor(Color.parseColor("#ef4444"));
                            tvStatus.setText("Failed to connect or incorrect PIN");
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        tvStatus.setTextColor(Color.parseColor("#ef4444"));
                        tvStatus.setText("Error: " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    private void startNetworkScan() {
        tvScanStatus.setVisibility(View.VISIBLE);
        tvScanStatus.setText("🔍 Broadcasting on Wi-Fi for active laptops...");
        btnScanNetwork.setEnabled(false);

        DeviceManager.discoverDevices(this, new DeviceManager.DiscoveryCallback() {
            @Override
            public void onDiscovered(PairedDevice device) {
                PairedDevice updated = DeviceManager.addOrUpdateDevice(MainActivity.this, device);
                DeviceManager.setActiveDevice(MainActivity.this, updated.ip);
                refreshDeviceList();
                reconnectService();
                testActiveConnection();
                if (!updated.isPaired()) {
                    Toast.makeText(MainActivity.this, "Found " + updated.hostname + "! Tap 'Pair 🔑' to authorize.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "Auto-connected to " + updated.hostname + " (" + updated.ip + ")", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFinished(List<PairedDevice> allFound) {
                tvScanStatus.setVisibility(View.GONE);
                btnScanNetwork.setEnabled(true);
                if (allFound.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Scan complete: No new laptops found. Try 'Add PC Manually'.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "Scan complete: Found " + allFound.size() + " laptop(s)", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void showAddDeviceDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_device, null);
        builder.setView(dialogView);

        EditText etIp = dialogView.findViewById(R.id.et_dialog_ip);
        EditText etPort = dialogView.findViewById(R.id.et_dialog_port);
        TextView tvError = dialogView.findViewById(R.id.tv_dialog_error);

        builder.setPositiveButton("Connect & Pair", null);
        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ip = etIp.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();
            int port = 1760;
            try {
                if (!portStr.isEmpty()) port = Integer.parseInt(portStr);
            } catch (Exception ignored) {}

            if (ip.isEmpty()) {
                tvError.setVisibility(View.VISIBLE);
                tvError.setText("Please enter an IP address");
                return;
            }

            tvError.setVisibility(View.VISIBLE);
            tvError.setTextColor(Color.parseColor("#06b6d4"));
            tvError.setText("Testing connection to laptop...");

            final int finalPort = port;
            new Thread(() -> {
                String testUrl = "http://" + ip + ":" + finalPort;
                String res = NetworkUtils.httpGet(testUrl + "/api/info", 4000);

                runOnUiThread(() -> {
                    if (res != null) {
                        try {
                            JSONObject json = new JSONObject(res);
                            String host = json.optString("hostname", "Laptop");
                            String devId = json.optString("device_id", null);
                            PairedDevice newDev = new PairedDevice(devId, host, ip, finalPort, "user", null, null, true);
                            DeviceManager.addOrUpdateDevice(MainActivity.this, newDev);
                            DeviceManager.setActiveDevice(MainActivity.this, ip);
                            reconnectService();
                            refreshDeviceList();
                            dialog.dismiss();
                            showPairDialog(newDev);
                        } catch (Exception e) {
                            tvError.setTextColor(Color.parseColor("#ef4444"));
                            tvError.setText("Invalid response from server");
                        }
                    } else {
                        tvError.setTextColor(Color.parseColor("#ef4444"));
                        tvError.setText("Cannot connect to " + testUrl + ". Check Wi-Fi and firewall.");
                    }
                });
            }).start();
        });
    }

    private void testActiveConnection() {
        PairedDevice active = DeviceManager.getActiveDevice(this);
        String url = active.getBaseUrl();
        tvStatusText.setText("Pinging " + active.hostname + "...");
        btnTestConnection.setEnabled(false);

        new Thread(() -> {
            long start = System.currentTimeMillis();
            String res = NetworkUtils.httpGet(url + "/api/info", 4000);
            long latency = System.currentTimeMillis() - start;

            runOnUiThread(() -> {
                btnTestConnection.setEnabled(true);
                if (res != null) {
                    try {
                        JSONObject json = new JSONObject(res);
                        String host = json.optString("hostname", active.hostname);
                        String devId = json.optString("device_id", null);
                        if (devId != null) active.deviceId = devId;
                        tvHostname.setText(host);
                        if (active.isPaired()) {
                            updateConnectionStatus(true, "Connected • 🔒 E2E Paired (" + latency + "ms)");
                        } else {
                            updateConnectionStatus(false, "Connected • ⚠️ Unpaired (Tap 'Pair 🔑' below)");
                        }
                        Toast.makeText(MainActivity.this, "Connected to " + host + " (" + latency + "ms)", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        updateConnectionStatus(true, "Connected");
                    }
                } else {
                    updateConnectionStatus(false, "Connection Failed");
                    Toast.makeText(MainActivity.this, "Cannot connect to " + url, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    // Auto-catch any missed challenge directly from laptop server
    private void checkActiveChallenge() {
        PairedDevice active = DeviceManager.getActiveDevice(this);
        String url = active.getBaseUrl();
        new Thread(() -> {
            String res = NetworkUtils.httpGetWithAuth(url + "/api/current_challenge", active.authToken, 2500);
            if (res != null) {
                try {
                    JSONObject json = new JSONObject(res);
                    if (json.optBoolean("has_challenge", false)) {
                        JSONObject ch = json.getJSONObject("challenge");
                        int remaining = ch.optInt("remaining_seconds", 35);
                        String user = ch.optString("user", "user");
                        String host = ch.optString("hostname", active.hostname);
                        String chId = ch.optString("session_id", ch.optString("id", null));
                        String msg = "Login requested for " + user + " on " + host + ". Tap Approve to unlock.";

                        if (chId != null) {
                            getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE).edit()
                                    .putString("active_challenge_id", chId)
                                    .putString("active_challenge_msg", msg)
                                    .putLong("active_challenge_expiry", System.currentTimeMillis() + (remaining * 1000L))
                                    .apply();
                        }

                        runOnUiThread(() -> showChallengeWithDuration(msg, remaining));
                    } else {
                        runOnUiThread(() -> {
                            if (layoutChallenge.getVisibility() == View.VISIBLE) {
                                hideChallenge();
                            }
                            AuthService.cancelChallengeNotification(MainActivity.this);
                        });
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    public void showChallenge(String message) {
        showChallengeWithDuration(message, 35);
    }

    public void showChallengeWithDuration(String message, int seconds) {
        if (message != null && !message.isEmpty()) {
            tvChallengeMessage.setText(message);
        }
        updateApproveButtonText();
        layoutChallenge.setVisibility(View.VISIBLE);
        layoutIdle.setVisibility(View.GONE);

        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        countDownTimer = new CountDownTimer(seconds * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                tvTimer.setText("Expires in: " + (millisUntilFinished / 1000) + "s");
            }

            @Override
            public void onFinish() {
                hideChallenge();
            }
        }.start();

        vibrate(100);
    }

    private void hideChallenge() {
        cancelBiometricPrompt();
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        layoutChallenge.setVisibility(View.GONE);
        layoutIdle.setVisibility(View.VISIBLE);
    }

    private void updateConnectionStatus(boolean connected, String text) {
        if (connected) {
            tvStatusDot.setTextColor(Color.parseColor("#10b981"));
            tvStatusText.setText(text);
        } else {
            tvStatusDot.setTextColor(Color.parseColor("#ef4444"));
            tvStatusText.setText(text);
        }
    }

    private void vibrate(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(ms);
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean isBiometricSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        try {
            BiometricManager bm = (BiometricManager) getSystemService(Context.BIOMETRIC_SERVICE);
            if (bm == null) return false;
            return (bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isBiometricRequiredAndSupported() {
        SharedPreferences prefs = getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE);
        boolean required = prefs.getBoolean("require_biometric", true);
        return required && isBiometricSupported();
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        builder.setView(dialogView);

        Switch switchBio = dialogView.findViewById(R.id.switch_dialog_biometric);
        TextView tvDesc = dialogView.findViewById(R.id.tv_dialog_biometric_desc);

        SharedPreferences prefs = getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE);
        boolean savedReq = prefs.getBoolean("require_biometric", true);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (switchBio != null) {
                switchBio.setEnabled(false);
                switchBio.setChecked(false);
            }
            if (tvDesc != null) tvDesc.setText("Biometrics requires Android 10+");
        } else {
            try {
                BiometricManager bm = (BiometricManager) getSystemService(Context.BIOMETRIC_SERVICE);
                int canAuth = (bm != null) ? bm.canAuthenticate() : BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE;
                if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
                    if (switchBio != null) {
                        switchBio.setChecked(savedReq);
                        switchBio.setEnabled(true);
                        switchBio.setOnCheckedChangeListener((buttonView, isChecked) -> {
                            prefs.edit().putBoolean("require_biometric", isChecked).apply();
                            if (tvDesc != null) {
                                tvDesc.setText(isChecked ? "Fingerprint match required for PC login" : "Fingerprint check disabled (Direct tap)");
                            }
                            updateApproveButtonText();
                        });
                    }
                    if (tvDesc != null) {
                        tvDesc.setText(savedReq ? "Fingerprint match required for PC login" : "Fingerprint check disabled (Direct tap)");
                    }
                } else if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                    if (switchBio != null) {
                        switchBio.setChecked(false);
                        switchBio.setEnabled(false);
                    }
                    if (tvDesc != null) tvDesc.setText("⚠️ No fingerprints enrolled on device");
                } else if (canAuth == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
                    if (switchBio != null) {
                        switchBio.setChecked(false);
                        switchBio.setEnabled(false);
                    }
                    if (tvDesc != null) tvDesc.setText("No biometric sensor detected");
                } else {
                    if (switchBio != null) {
                        switchBio.setChecked(false);
                        switchBio.setEnabled(false);
                    }
                    if (tvDesc != null) tvDesc.setText("Biometrics unavailable (code " + canAuth + ")");
                }
            } catch (Exception e) {
                if (switchBio != null) {
                    switchBio.setEnabled(false);
                    switchBio.setChecked(false);
                }
                if (tvDesc != null) tvDesc.setText("Biometrics error: " + e.getMessage());
            }
        }

        builder.setPositiveButton("Done", null);
        builder.show();
    }

    private void updateApproveButtonText() {
        if (btnApproveChallenge != null) {
            if (isBiometricRequiredAndSupported()) {
                btnApproveChallenge.setText("👆 APPROVE WITH FINGERPRINT");
            } else {
                btnApproveChallenge.setText("✅ APPROVE UNLOCK");
            }
        }
    }

    private void promptBiometricAuthentication() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            executeApprove();
            return;
        }

        if (isPromptShowing) {
            return;
        }

        cancelBiometricPrompt();

        cancellationSignal = new CancellationSignal();
        isPromptShowing = true;

        PairedDevice active = DeviceManager.getActiveDevice(this);

        try {
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                    .setTitle("🔒 PC Unlock Authentication")
                    .setSubtitle("Confirm your fingerprint to approve PC login")
                    .setDescription("Request from " + active.hostname)
                    .setNegativeButton("Cancel", getMainExecutor(), (dialog, which) -> {
                        isPromptShowing = false;
                        cancellationSignal = null;
                    });

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setConfirmationRequired(false);
            }

            BiometricPrompt prompt = builder.build();

            prompt.authenticate(
                    cancellationSignal,
                    getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            isPromptShowing = false;
                            cancellationSignal = null;
                            executeApprove();
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
                            isPromptShowing = false;
                            cancellationSignal = null;
                            if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                                errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                                Toast.makeText(MainActivity.this, "Authentication: " + errString, Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            super.onAuthenticationFailed();
                            vibrateError();
                        }

                        @Override
                        public void onAuthenticationHelp(int helpCode, CharSequence helpString) {
                            super.onAuthenticationHelp(helpCode, helpString);
                            Toast.makeText(MainActivity.this, String.valueOf(helpString), Toast.LENGTH_SHORT).show();
                        }
                    }
            );
        } catch (Exception e) {
            isPromptShowing = false;
            cancellationSignal = null;
            Log.e("MainActivity", "Error displaying BiometricPrompt", e);
            executeApprove();
        }
    }

    private void cancelBiometricPrompt() {
        if (cancellationSignal != null && !cancellationSignal.isCanceled()) {
            try {
                cancellationSignal.cancel();
            } catch (Exception ignored) {}
        }
        cancellationSignal = null;
        isPromptShowing = false;
    }

    private void executeApprove() {
        cancelBiometricPrompt();
        SharedPreferences prefs = getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE);
        String activeChallengeId = prefs.getString("active_challenge_id", "");
        AuthService.cancelChallengeNotification(this);
        hideChallenge();
        vibrateSuccess();

        PairedDevice active = DeviceManager.getActiveDevice(this);
        String url = active.getBaseUrl();

        new Thread(() -> {
            boolean ok = NetworkUtils.httpPostWithAuth(url + "/api/approve_current", active.authToken, active.secretKey, activeChallengeId, "approve", 5000);
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(MainActivity.this, "✅ Verified! " + active.hostname + " Unlocked", Toast.LENGTH_LONG).show();
                    KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && km.isKeyguardLocked()) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try { moveTaskToBack(true); } catch (Exception ignored) {}
                        }, 1200);
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Unlock failed: check pairing or challenge expired", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void vibrateSuccess() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    long[] timings = {0, 70, 50, 90};
                    int[] amplitudes = {0, 160, 0, 255};
                    v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
                } else {
                    v.vibrate(120);
                }
            }
        } catch (Exception ignored) {}
    }

    private void vibrateError() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(200);
                }
            }
        } catch (Exception ignored) {}
    }

    private void setupKdeConnectListeners() {
        if (btnSendFileToPc != null) btnSendFileToPc.setOnClickListener(v -> pickFileToSend());
        if (btnSendClipToPc != null) btnSendClipToPc.setOnClickListener(v -> sendPhoneClipboardToPc());
        if (btnGetClipFromPc != null) btnGetClipFromPc.setOnClickListener(v -> fetchPcClipboard());
        if (btnRingPc != null) btnRingPc.setOnClickListener(v -> ringPc());
        if (btnLockPc != null) btnLockPc.setOnClickListener(v -> lockPc());
        if (btnRemoteTrackpad != null) {
            btnRemoteTrackpad.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, TrackpadActivity.class);
                startActivity(intent);
            });
        }
        if (btnOpenWebpage != null) {
            btnOpenWebpage.setOnClickListener(v -> showOpenWebpageDialog());
        }

        if (btnMediaPlayPause != null) btnMediaPlayPause.setOnClickListener(v -> sendMediaCommand("PlayPause"));
        if (btnMediaPrev != null) btnMediaPrev.setOnClickListener(v -> sendMediaCommand("Previous"));
        if (btnMediaNext != null) btnMediaNext.setOnClickListener(v -> sendMediaCommand("Next"));
        if (btnMediaVolDown != null) btnMediaVolDown.setOnClickListener(v -> sendMediaCommand("VolumeDown"));
        if (btnMediaVolUp != null) btnMediaVolUp.setOnClickListener(v -> sendMediaCommand("VolumeUp"));
    }

    private void pickFileToSend() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Select File to Send to PC"), REQUEST_PICK_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "No file manager found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            uploadFileUri(data.getData());
        }
    }

    private void uploadFileUri(Uri uri) {
        String filename = getFileName(uri);
        Toast.makeText(this, "⏳ Uploading " + filename + " to PC...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                PairedDevice active = DeviceManager.getActiveDevice(this);
                String uploadUrl = active.getBaseUrl() + "/api/files/upload";
                HttpURLConnection conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setRequestProperty("X-Filename", URLEncoder.encode(filename, "UTF-8"));
                if (active.isPaired()) {
                    conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                }

                try (InputStream is = getContentResolver().openInputStream(uri);
                     OutputStream os = conn.getOutputStream()) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        os.write(buf, 0, r);
                    }
                }

                int code = conn.getResponseCode();
                runOnUiThread(() -> {
                    if (code == 200) {
                        Toast.makeText(MainActivity.this, "✅ Sent " + filename + " to PC Downloads!", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(MainActivity.this, "❌ Upload failed (HTTP " + code + ")", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        result = cursor.getString(idx);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return (result != null) ? result : ("file_" + System.currentTimeMillis());
    }

    private void sendPhoneClipboardToPc() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
                Toast.makeText(this, "Phone clipboard is empty", Toast.LENGTH_SHORT).show();
                return;
            }
            CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
            if (text == null || text.length() == 0) {
                Toast.makeText(this, "Phone clipboard is empty", Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(this, "📋 Sending clipboard to PC...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    PairedDevice active = DeviceManager.getActiveDevice(this);
                    String url = active.getBaseUrl() + "/api/clipboard";
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    if (active.isPaired()) {
                        conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                    }
                    conn.setDoOutput(true);
                    JSONObject body = new JSONObject().put("text", text.toString());
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.toString().getBytes("UTF-8"));
                    }
                    int code = conn.getResponseCode();
                    runOnUiThread(() -> {
                        if (code == 200) {
                            Toast.makeText(MainActivity.this, "✅ Copied to PC Clipboard!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "❌ Failed to copy to PC", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
        } catch (Exception e) {
            Toast.makeText(this, "Error accessing clipboard: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void fetchPcClipboard() {
        Toast.makeText(this, "📋 Fetching PC clipboard...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                PairedDevice active = DeviceManager.getActiveDevice(this);
                String url = active.getBaseUrl() + "/api/clipboard";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                if (active.isPaired()) {
                    conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                }
                int code = conn.getResponseCode();
                if (code == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONObject res = new JSONObject(sb.toString());
                    String text = res.optString("text", "");
                    runOnUiThread(() -> {
                        if (!text.isEmpty()) {
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("PC Clipboard", text));
                                Toast.makeText(MainActivity.this, "✅ Copied from PC: " + (text.length() > 30 ? text.substring(0, 30) + "..." : text), Toast.LENGTH_LONG).show();
                            }
                        } else {
                            Toast.makeText(MainActivity.this, "PC clipboard is currently empty", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void ringPc() {
        new Thread(() -> {
            try {
                PairedDevice active = DeviceManager.getActiveDevice(this);
                String url = active.getBaseUrl() + "/api/ping";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                if (active.isPaired()) {
                    conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write("{}".getBytes("UTF-8"));
                }
                conn.getResponseCode();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "🔔 Laptop is ringing!", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void lockPc() {
        new Thread(() -> {
            try {
                PairedDevice active = DeviceManager.getActiveDevice(this);
                String url = active.getBaseUrl() + "/api/action";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                if (active.isPaired()) {
                    conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                }
                JSONObject body = new JSONObject().put("action", "lock");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }
                conn.getResponseCode();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "🔒 Laptop screen locked!", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showOpenWebpageDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🌐 Open Webpage on PC");
        final EditText input = new EditText(this);
        input.setHint("https://example.com");
        input.setSingleLine(true);
        input.setPadding(40, 30, 40, 30);
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
                if (clip != null && (clip.toString().startsWith("http://") || clip.toString().startsWith("https://"))) {
                    input.setText(clip.toString());
                    input.setSelection(clip.length());
                }
            }
        } catch (Exception ignored) {}

        builder.setView(input);

        builder.setPositiveButton("Open on PC", (dialog, which) -> {
            String url = input.getText().toString().trim();
            if (url.isEmpty()) return;
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            final String finalUrl = url;
            Toast.makeText(this, "🌐 Opening on PC browser...", Toast.LENGTH_SHORT).show();

            new Thread(() -> {
                try {
                    PairedDevice active = DeviceManager.getActiveDevice(this);
                    String endpoint = active.getBaseUrl() + "/api/open_url";
                    HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    if (active.isPaired()) {
                        conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                    }
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    JSONObject body = new JSONObject().put("url", finalUrl);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.toString().getBytes("UTF-8"));
                    }
                    int code = conn.getResponseCode();
                    runOnUiThread(() -> {
                        if (code == 200) {
                            Toast.makeText(MainActivity.this, "✅ Opened in PC browser!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "❌ Failed to open (HTTP " + code + ")", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void sendMediaCommand(String command) {
        new Thread(() -> {
            try {
                PairedDevice active = DeviceManager.getActiveDevice(this);
                String url = active.getBaseUrl() + "/api/media/command";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                if (active.isPaired()) {
                    conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                }
                JSONObject body = new JSONObject().put("command", command);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }
                conn.getResponseCode();
                runOnUiThread(() -> pollHandler.postDelayed(this::fetchMediaStatus, 300));
            } catch (Exception ignored) {}
        }).start();
    }

    private void fetchMediaStatus() {
        new Thread(() -> {
            try {
                PairedDevice active = DeviceManager.getActiveDevice(this);
                String url = active.getBaseUrl() + "/api/media/status";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                if (active.isPaired()) {
                    conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                }
                int code = conn.getResponseCode();
                if (code == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONObject res = new JSONObject(sb.toString());
                    boolean hasPlayer = res.optBoolean("has_player", false);
                    String status = res.optString("status", "");
                    String title = res.optString("title", "");
                    String artist = res.optString("artist", "");
                    String player = res.optString("player", "");

                    runOnUiThread(() -> {
                        if (hasPlayer && (!title.isEmpty() || !artist.isEmpty())) {
                            if (tvMediaStatus != null) tvMediaStatus.setText(status + " (" + player + ")");
                            if (tvMediaTitle != null) tvMediaTitle.setText(title.isEmpty() ? "Playing" : title);
                            if (tvMediaArtist != null) tvMediaArtist.setText(artist);
                            if (btnMediaPlayPause != null) {
                                btnMediaPlayPause.setText("Playing".equalsIgnoreCase(status) ? "⏸" : "▶");
                            }
                        } else {
                            if (tvMediaStatus != null) tvMediaStatus.setText("Idle");
                            if (tvMediaTitle != null) tvMediaTitle.setText("No media active on PC");
                            if (tvMediaArtist != null) tvMediaArtist.setText("");
                        }
                    });
                }
            } catch (Exception ignored) {}
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDeviceList();
        updateChallengeUIFromStore();
        checkActiveChallenge();
        fetchMediaStatus();

        // Start periodic check every 2.5 seconds while activity is in foreground
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                checkActiveChallenge();
                fetchMediaStatus();
                pollHandler.postDelayed(this, 2500);
            }
        };
        pollHandler.postDelayed(pollRunnable, 2500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        cancelBiometricPrompt();
        if (pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        cancelBiometricPrompt();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        try {
            unregisterReceiver(serviceReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}

