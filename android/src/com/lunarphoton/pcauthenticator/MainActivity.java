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
import android.os.Environment;
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
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.widget.ImageView;
import android.widget.ProgressBar;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
    private Button btnRemoteTrackpad;
    private Button btnCameraView;

    private TextView tvMediaStatus;
    private TextView tvMediaTitle;
    private TextView tvMediaArtist;
    private Button btnMediaPrev;
    private Button btnMediaPlayPause;
    private Button btnMediaNext;
    private Button btnMediaVolDown;
    private Button btnMediaVolUp;

    // Laptop Live Status UI
    private TextView tvPcLockBadge;
    private TextView tvPcBattery;
    private TextView tvPcUptime;
    private TextView tvPcCpu;
    private TextView tvPcMemory;
    private TextView tvPcStorage;
    private TextView tvPcWeather;
    private Button btnLockLaptopAction;
    private Button btnScreenshotAction;
    private Button btnBrowseLaptopAction;
    private Button btnSearchFilesAction;
    private Button btnPhoneScreenOff;
    private boolean isPcCurrentlyLocked = false;
    private Bitmap currentScreenshotBitmap = null;
    private String currentBrowsePath = "shortcuts";
    private String parentBrowsePath = null;

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
    private File pendingInstallApk = null;
    private ClipboardManager.OnPrimaryClipChangedListener mainClipListener = null;

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
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
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
        btnRemoteTrackpad = findViewById(R.id.btn_remote_trackpad);
        btnCameraView = findViewById(R.id.btn_camera_view);

        tvMediaStatus = findViewById(R.id.tv_media_status);
        tvMediaTitle = findViewById(R.id.tv_media_title);
        tvMediaArtist = findViewById(R.id.tv_media_artist);
        btnMediaPrev = findViewById(R.id.btn_media_prev);
        btnMediaPlayPause = findViewById(R.id.btn_media_play_pause);
        btnMediaNext = findViewById(R.id.btn_media_next);
        btnMediaVolDown = findViewById(R.id.btn_media_voldown);
        btnMediaVolUp = findViewById(R.id.btn_media_volup);

        setupKdeConnectListeners();

        // Laptop Live Status UI bindings
        tvPcLockBadge = findViewById(R.id.tv_pc_lock_badge);
        tvPcBattery = findViewById(R.id.tv_pc_battery);
        tvPcUptime = findViewById(R.id.tv_pc_uptime);
        tvPcCpu = findViewById(R.id.tv_pc_cpu);
        tvPcMemory = findViewById(R.id.tv_pc_memory);
        tvPcStorage = findViewById(R.id.tv_pc_storage);
        tvPcWeather = findViewById(R.id.tv_pc_weather);
        btnLockLaptopAction = findViewById(R.id.btn_lock_laptop_action);
        btnScreenshotAction = findViewById(R.id.btn_screenshot_action);
        btnBrowseLaptopAction = findViewById(R.id.btn_browse_laptop_action);
        btnSearchFilesAction = findViewById(R.id.btn_search_files_action);

        if (btnLockLaptopAction != null) {
            btnLockLaptopAction.setOnClickListener(v -> {
                if (isPcCurrentlyLocked) {
                    onUnlockButtonClicked();
                } else {
                    lockPc();
                }
            });
        }
        if (btnScreenshotAction != null) {
            btnScreenshotAction.setOnClickListener(v -> showScreenshotDialog());
        }
        if (btnBrowseLaptopAction != null) {
            btnBrowseLaptopAction.setOnClickListener(v -> showFileBrowseDialog());
        }
        if (btnSearchFilesAction != null) {
            btnSearchFilesAction.setOnClickListener(v -> showFileSearchDialog());
        }

        // Header Settings Button
        btnSettings = findViewById(R.id.btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsDialog());
        }
        btnPhoneScreenOff = findViewById(R.id.btn_phone_screen_off);
        if (btnPhoneScreenOff != null) {
            btnPhoneScreenOff.setOnClickListener(v -> ScreenOffHelper.requestTurnScreenOff(MainActivity.this));
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
        checkStoragePermissions();
        checkPhonePermission();

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
        if (intent == null) {
            return;
        }

        if ("com.lunarphoton.pcauthenticator.INSTALL_APK".equals(intent.getAction()) || intent.hasExtra("apk_path")) {
            String apkPath = intent.getStringExtra("apk_path");
            intent.removeExtra("apk_path");
            if (apkPath != null) {
                File apkFile = new File(apkPath);
                if (apkFile.exists()) {
                    openDownloadedFile(apkFile);
                }
            }
        }

        if (!intent.getBooleanExtra("from_challenge", false)) {
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

    private void checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle("Allow Storage Access")
                        .setMessage("To allow browsing, downloading, and sharing files between your laptop and phone, please enable 'Allow access to manage all files'.")
                        .setPositiveButton("Grant Access", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Exception e) {
                                try {
                                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                                    startActivity(intent);
                                } catch (Exception ignored) {}
                            }
                        })
                        .setNegativeButton("Later", null)
                        .show();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 102);
            }
        }
    }

    private void checkPhonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, 103);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 103 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            reconnectService();
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
        String modePrefix = DeviceManager.isInternetActive() ? "🌐 Internet: " : "🟢 Wi-Fi: ";
        tvActiveIp.setText(modePrefix + active.getBaseUrl());
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
                                    String localUrl = json.optString("local_url", null);
                                    String internetUrl = json.optString("internet_url", null);
                                    if (localUrl != null) device.localUrl = localUrl;
                                    if (internetUrl != null) device.internetUrl = internetUrl;
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
        tvStatusText.setText("Pinging " + active.hostname + "...");
        btnTestConnection.setEnabled(false);

        new Thread(() -> {
            long start = System.currentTimeMillis();
            String url = active.getBaseUrl();
            String res = NetworkUtils.httpGet(url + "/api/info", 3000);
            boolean isInternet = false;

            if (res == null && active.getInternetUrl() != null && !url.equals(active.getInternetUrl())) {
                url = active.getInternetUrl();
                start = System.currentTimeMillis();
                res = NetworkUtils.httpGet(url + "/api/info", 4500);
                if (res != null) {
                    isInternet = true;
                    DeviceManager.setActiveUrl(url);
                }
            } else if (res != null) {
                DeviceManager.setActiveUrl(url);
                isInternet = url.startsWith("https://");
            }
            long latency = System.currentTimeMillis() - start;

            final String finalRes = res;
            final boolean finalIsInternet = isInternet;
            final String finalUrl = url;
            runOnUiThread(() -> {
                btnTestConnection.setEnabled(true);
                if (finalRes != null) {
                    try {
                        JSONObject json = new JSONObject(finalRes);
                        String host = json.optString("hostname", active.hostname);
                        String devId = json.optString("device_id", null);
                        if (devId != null) active.deviceId = devId;
                        String localUrl = json.optString("local_url", null);
                        String internetUrl = json.optString("internet_url", null);
                        if (localUrl != null) active.localUrl = localUrl;
                        if (internetUrl != null) active.internetUrl = internetUrl;
                        DeviceManager.addOrUpdateDevice(MainActivity.this, active);

                        tvHostname.setText(host);
                        String modeTag = finalIsInternet ? "🌐 Internet" : "🟢 Wi-Fi";
                        if (active.isPaired()) {
                            updateConnectionStatus(true, "Connected • " + modeTag + " • 🔒 Paired (" + latency + "ms)");
                        } else {
                            updateConnectionStatus(false, "Connected • " + modeTag + " • ⚠️ Unpaired (Tap 'Pair 🔑' below)");
                        }
                        tvActiveIp.setText(modeTag + ": " + finalUrl);
                        Toast.makeText(MainActivity.this, "Connected to " + host + " (" + modeTag + ", " + latency + "ms)", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        updateConnectionStatus(true, "Connected");
                    }
                } else {
                    updateConnectionStatus(false, "Connection Failed");
                    Toast.makeText(MainActivity.this, "Cannot connect to " + active.hostname + " via Wi-Fi or Internet", Toast.LENGTH_LONG).show();
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
        if (btnRemoteTrackpad != null) {
            btnRemoteTrackpad.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, TrackpadActivity.class);
                startActivity(intent);
            });
        }
        if (btnCameraView != null) {
            btnCameraView.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, CameraActivity.class);
                startActivity(intent);
            });
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

            AuthService.lastSyncedClipboard = text.toString();
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
                            AuthService.lastSyncedClipboard = text;
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
        Toast.makeText(this, "🔒 Locking laptop...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                PairedDevice active = DeviceManager.getActiveDevice(this);
                if (active == null) return;
                String url = active.getBaseUrl() + "/api/action";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoOutput(true);
                if (active.isPaired()) {
                    conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                }
                JSONObject body = new JSONObject().put("action", "lock");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                runOnUiThread(() -> {
                    if (code == 200) {
                        isPcCurrentlyLocked = true;
                        if (tvPcLockBadge != null) {
                            tvPcLockBadge.setText("🔒 Locked");
                            tvPcLockBadge.setTextColor(Color.parseColor("#bf616a"));
                        }
                        if (btnLockLaptopAction != null) {
                            btnLockLaptopAction.setText("🔓 Unlock Screen");
                            btnLockLaptopAction.setBackgroundResource(R.drawable.btn_approve);
                        }
                        Toast.makeText(MainActivity.this, "🔒 Laptop screen locked!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "❌ Failed to lock (HTTP " + code + ")", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void onUnlockButtonClicked() {
        SharedPreferences prefs = getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE);
        String savedPwd = prefs.getString("saved_laptop_password", null);
        if (savedPwd != null && !savedPwd.isEmpty() && isBiometricRequiredAndSupported()) {
            promptBiometricForSavedPassword(savedPwd);
        } else {
            showUnlockPasswordDialog();
        }
    }

    private void promptBiometricForSavedPassword(String savedPassword) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            executeUnlockPc(savedPassword);
            return;
        }

        cancelBiometricPrompt();
        cancellationSignal = new CancellationSignal();
        isPromptShowing = true;

        PairedDevice active = DeviceManager.getActiveDevice(this);

        try {
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                    .setTitle("🔓 Unlock Laptop")
                    .setSubtitle("Confirm fingerprint to unlock " + (active != null ? active.hostname : "laptop"))
                    .setNegativeButton("Enter Password", getMainExecutor(), (dialog, which) -> {
                        isPromptShowing = false;
                        cancellationSignal = null;
                        showUnlockPasswordDialog();
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
                            executeUnlockPc(savedPassword);
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
                            isPromptShowing = false;
                            cancellationSignal = null;
                            if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED &&
                                errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                                showUnlockPasswordDialog();
                            }
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            super.onAuthenticationFailed();
                            vibrate(100);
                        }
                    }
            );
        } catch (Exception e) {
            isPromptShowing = false;
            cancellationSignal = null;
            showUnlockPasswordDialog();
        }
    }

    private void showUnlockPasswordDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🔓 Unlock Laptop Screen");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 30, 60, 10);

        TextView tvHint = new TextView(this);
        tvHint.setText("Enter your laptop password to unlock:");
        tvHint.setTextColor(Color.parseColor("#d8dee9"));
        tvHint.setTextSize(13);
        layout.addView(tvHint);

        EditText etPassword = new EditText(this);
        etPassword.setHint("Laptop password");
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setTextColor(Color.WHITE);
        etPassword.setHintTextColor(Color.parseColor("#4c566a"));
        layout.addView(etPassword);

        CheckBox cbRemember = new CheckBox(this);
        cbRemember.setText("Save password for fingerprint unlock");
        cbRemember.setTextColor(Color.parseColor("#d8dee9"));
        cbRemember.setTextSize(12);
        cbRemember.setChecked(true);
        if (isBiometricRequiredAndSupported()) {
            layout.addView(cbRemember);
        }

        builder.setView(layout);
        builder.setPositiveButton("🔓 Unlock", (dialog, which) -> {
            String pwd = etPassword.getText().toString().trim();
            if (pwd.isEmpty()) {
                Toast.makeText(MainActivity.this, "Please enter your password", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isBiometricRequiredAndSupported()) {
                promptBiometricForUnlock(pwd, cbRemember.isChecked());
            } else {
                if (cbRemember.isChecked()) {
                    getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE)
                            .edit().putString("saved_laptop_password", pwd).apply();
                }
                executeUnlockPc(pwd);
            }
        });
        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.show();
        etPassword.requestFocus();
    }

    private void promptBiometricForUnlock(String password, boolean rememberChecked) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            if (rememberChecked) {
                getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE)
                        .edit().putString("saved_laptop_password", password).apply();
            }
            executeUnlockPc(password);
            return;
        }

        cancelBiometricPrompt();
        cancellationSignal = new CancellationSignal();
        isPromptShowing = true;

        PairedDevice active = DeviceManager.getActiveDevice(this);

        try {
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                    .setTitle("🔓 Authorize Laptop Unlock")
                    .setSubtitle("Confirm your fingerprint to unlock laptop screen")
                    .setDescription("Authorize unlock for " + (active != null ? active.hostname : "Laptop"))
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
                            if (rememberChecked) {
                                getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE)
                                        .edit().putString("saved_laptop_password", password).apply();
                            }
                            executeUnlockPc(password);
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
                            isPromptShowing = false;
                            cancellationSignal = null;
                            if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED &&
                                errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                                Toast.makeText(MainActivity.this, "Authentication failed: " + errString, Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            super.onAuthenticationFailed();
                            vibrate(100);
                        }
                    }
            );
        } catch (Exception e) {
            isPromptShowing = false;
            cancellationSignal = null;
            if (rememberChecked) {
                getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE)
                        .edit().putString("saved_laptop_password", password).apply();
            }
            executeUnlockPc(password);
        }
    }

    private void executeUnlockPc(String password) {
        Toast.makeText(this, "🔓 Unlocking laptop...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                PairedDevice active = DeviceManager.getActiveDevice(this);
                if (active == null) return;
                String url = active.getBaseUrl() + "/api/action";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                if (active.isPaired()) {
                    conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                }
                conn.setDoOutput(true);
                JSONObject body = new JSONObject();
                body.put("action", "unlock");
                if (password != null && !password.isEmpty()) {
                    body.put("password", password);
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }
                int code = conn.getResponseCode();
                conn.disconnect();

                runOnUiThread(() -> {
                    if (code == 200) {
                        isPcCurrentlyLocked = false;
                        if (tvPcLockBadge != null) {
                            tvPcLockBadge.setText("🔓 Unlocked");
                            tvPcLockBadge.setTextColor(Color.parseColor("#a3be8c"));
                        }
                        if (btnLockLaptopAction != null) {
                            btnLockLaptopAction.setText("🔒 Lock Screen");
                            btnLockLaptopAction.setBackgroundResource(R.drawable.btn_deny);
                        }
                        hideChallenge();
                        AuthService.cancelChallengeNotification(MainActivity.this);
                        Toast.makeText(MainActivity.this, "✅ Laptop screen unlocked!", Toast.LENGTH_SHORT).show();
                    } else if (code == 401) {
                        Toast.makeText(MainActivity.this, "❌ Incorrect password. Please try again.", Toast.LENGTH_LONG).show();
                        getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE)
                                .edit().remove("saved_laptop_password").apply();
                        showUnlockPasswordDialog();
                    } else {
                        Toast.makeText(MainActivity.this, "❌ Failed to unlock (HTTP " + code + ")", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void fetchLaptopStatus() {
        new Thread(() -> {
            try {
                PairedDevice active = DeviceManager.getActiveDevice(this);
                if (active == null) return;
                String url = active.getBaseUrl() + "/api/pc/status";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
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
                    String localUrl = res.optString("local_url", null);
                    String internetUrl = res.optString("internet_url", null);
                    boolean updated = false;
                    if (localUrl != null && !localUrl.equals(active.localUrl)) {
                        active.localUrl = localUrl;
                        updated = true;
                    }
                    if (internetUrl != null && !internetUrl.equals(active.internetUrl)) {
                        active.internetUrl = internetUrl;
                        updated = true;
                    }
                    if (updated) {
                        DeviceManager.addOrUpdateDevice(this, active);
                    }
                    boolean locked = res.optBoolean("locked", false);
                    isPcCurrentlyLocked = locked;
                    JSONObject bat = res.optJSONObject("battery");
                    JSONObject cpu = res.optJSONObject("cpu");
                    JSONObject mem = res.optJSONObject("memory");
                    JSONObject up = res.optJSONObject("uptime");
                    JSONObject stor = res.optJSONObject("storage");

                    String weatherFormatted = res.optString("weather_formatted", "");
                    if (weatherFormatted.isEmpty() || "Weather Unavailable".equalsIgnoreCase(weatherFormatted)) {
                        JSONObject weatherObj = res.optJSONObject("weather");
                        if (weatherObj != null) {
                            weatherFormatted = weatherObj.optString("formatted", "");
                            if (weatherFormatted.isEmpty()) {
                                String city = weatherObj.optString("city", "");
                                String temp = weatherObj.optString("temp_c", "");
                                String desc = weatherObj.optString("desc", "");
                                if (!city.isEmpty()) {
                                    weatherFormatted = city + " • " + temp + "°C, " + desc;
                                }
                            }
                        }
                    }

                    final String finalWeather = weatherFormatted;
                    runOnUiThread(() -> {
                        if (tvPcLockBadge != null) {
                            if (locked) {
                                tvPcLockBadge.setText("🔒 Locked");
                                tvPcLockBadge.setTextColor(Color.parseColor("#bf616a"));
                            } else {
                                tvPcLockBadge.setText("🔓 Unlocked");
                                tvPcLockBadge.setTextColor(Color.parseColor("#a3be8c"));
                            }
                        }
                        if (btnLockLaptopAction != null) {
                            if (locked) {
                                btnLockLaptopAction.setText("🔓 Unlock Screen");
                                btnLockLaptopAction.setBackgroundResource(R.drawable.btn_approve);
                            } else {
                                btnLockLaptopAction.setText("🔒 Lock Screen");
                                btnLockLaptopAction.setBackgroundResource(R.drawable.btn_deny);
                            }
                        }
                        if (tvPcBattery != null && bat != null) {
                            int pct = bat.optInt("capacity", bat.optInt("percent", -1));
                            String st = bat.optString("status", "");
                            boolean charging = bat.optBoolean("charging", false) || "Charging".equalsIgnoreCase(st);
                            boolean ac = bat.optBoolean("ac_online", false);
                            if (pct >= 0) {
                                tvPcBattery.setText("🔋 Battery: " + pct + "%" + (charging ? " (⚡ Charging)" : (ac ? " (⚡ Plugged in)" : "")));
                            }
                        }
                        if (tvPcUptime != null && up != null) {
                            String formatted = up.optString("formatted", "--");
                            tvPcUptime.setText("⏱️ Uptime: " + formatted);
                        }
                        if (tvPcCpu != null && cpu != null) {
                            double usage = cpu.optDouble("usage_pct", 0.0);
                            tvPcCpu.setText(String.format(Locale.US, "📊 CPU: %.1f%%", usage));
                        }
                        if (tvPcMemory != null && mem != null) {
                            double used = mem.optDouble("used_gb", 0.0);
                            double total = mem.optDouble("total_gb", 0.0);
                            int pct = mem.optInt("used_pct", 0);
                            tvPcMemory.setText(String.format(Locale.US, "💾 RAM: %.1f / %.1f GB (%d%%)", used, total, pct));
                        }
                        if (tvPcStorage != null && stor != null) {
                            String sf = stor.optString("formatted", "");
                            if (!sf.isEmpty()) {
                                tvPcStorage.setText("💽 Disk: " + sf);
                            }
                        }
                        if (tvPcWeather != null && !finalWeather.isEmpty() && !finalWeather.startsWith("{")) {
                            tvPcWeather.setText("🌤️ " + finalWeather);
                        }
                    });
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void showScreenshotDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_screenshot, null);
        builder.setView(dialogView);

        ImageView ivPreview = dialogView.findViewById(R.id.iv_screenshot_preview);
        ProgressBar pbLoading = dialogView.findViewById(R.id.pb_screenshot_loading);
        TextView tvTime = dialogView.findViewById(R.id.tv_screenshot_time);
        Button btnRefresh = dialogView.findViewById(R.id.btn_screenshot_refresh);
        Button btnSave = dialogView.findViewById(R.id.btn_screenshot_save);

        AlertDialog dialog = builder.create();

        Runnable fetchScreenshot = () -> {
            pbLoading.setVisibility(View.VISIBLE);
            tvTime.setText("Capturing screen...");
            new Thread(() -> {
                try {
                    PairedDevice active = DeviceManager.getActiveDevice(this);
                    String url = active.getBaseUrl() + "/api/screen/screenshot";
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(10000);
                    if (active.isPaired()) {
                        conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                    }
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        try (InputStream is = conn.getInputStream()) {
                            Bitmap bm = BitmapFactory.decodeStream(is);
                            currentScreenshotBitmap = bm;
                            String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                            runOnUiThread(() -> {
                                pbLoading.setVisibility(View.GONE);
                                if (bm != null) {
                                    ivPreview.setImageBitmap(bm);
                                    tvTime.setText("Captured at " + timeStr);
                                } else {
                                    tvTime.setText("Failed to decode screenshot");
                                }
                            });
                        }
                    } else {
                        runOnUiThread(() -> {
                            pbLoading.setVisibility(View.GONE);
                            tvTime.setText("Capture error (HTTP " + code + ")");
                        });
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        pbLoading.setVisibility(View.GONE);
                        tvTime.setText("Error: " + e.getMessage());
                    });
                }
            }).start();
        };

        btnRefresh.setOnClickListener(v -> fetchScreenshot.run());

        btnSave.setOnClickListener(v -> {
            if (currentScreenshotBitmap == null) {
                Toast.makeText(this, "No screenshot available to save", Toast.LENGTH_SHORT).show();
                return;
            }
            saveScreenshotToGallery(currentScreenshotBitmap);
        });

        fetchScreenshot.run();
        dialog.show();
    }

    private void saveScreenshotToGallery(Bitmap bitmap) {
        String filename = "PC_Screenshot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        try {
            OutputStream fos = null;
            File imageFile = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PC_Screenshots");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    fos = getContentResolver().openOutputStream(uri);
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PC_Screenshots");
                if (!dir.exists()) dir.mkdirs();
                imageFile = new File(dir, filename);
                fos = new FileOutputStream(imageFile);
            }

            if (fos != null) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.flush();
                fos.close();
                if (imageFile != null) {
                    MediaScannerConnection.scanFile(this, new String[]{imageFile.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
                }
                Toast.makeText(this, "📸 Saved to Pictures/PC_Screenshots!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Could not open storage to save screenshot", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showFileSearchDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_file_search, null);
        builder.setView(dialogView);

        EditText etQuery = dialogView.findViewById(R.id.et_search_query);
        Button btnSearch = dialogView.findViewById(R.id.btn_search_submit);
        TextView tvStatus = dialogView.findViewById(R.id.tv_search_status);
        LinearLayout containerResults = dialogView.findViewById(R.id.container_search_results);

        AlertDialog dialog = builder.create();

        Runnable runSearch = () -> {
            String q = etQuery.getText().toString().trim();
            if (q.isEmpty()) {
                tvStatus.setText("Please enter a search term");
                return;
            }
            tvStatus.setText("🔍 Searching laptop files for '" + q + "'...");
            containerResults.removeAllViews();

            new Thread(() -> {
                try {
                    PairedDevice active = DeviceManager.getActiveDevice(this);
                    String url = active.getBaseUrl() + "/api/files/search?q=" + URLEncoder.encode(q, "UTF-8");
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(12000);
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
                        JSONArray results = new JSONArray(sb.toString());
                        runOnUiThread(() -> {
                            int count = results.length();
                            if (count == 0) {
                                tvStatus.setText("No matching files found on laptop.");
                                return;
                            }
                            tvStatus.setText("Found " + count + " file" + (count == 1 ? "" : "s") + " on laptop:");
                            LayoutInflater inflater = LayoutInflater.from(this);
                            for (int i = 0; i < count; i++) {
                                JSONObject item = results.optJSONObject(i);
                                if (item == null) continue;
                                String name = item.optString("name", "Unknown");
                                String path = item.optString("path", "");
                                String sizeStr = item.optString("size_formatted", "");

                                View row = inflater.inflate(R.layout.item_file_search, containerResults, false);
                                TextView tvIcon = row.findViewById(R.id.tv_file_icon);
                                TextView tvName = row.findViewById(R.id.tv_file_name);
                                TextView tvDetails = row.findViewById(R.id.tv_file_details);
                                Button btnGet = row.findViewById(R.id.btn_file_download);

                                tvName.setText(name);
                                String lower = name.toLowerCase();
                                if (lower.endsWith(".pdf")) tvIcon.setText("📕");
                                else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif")) tvIcon.setText("🖼️");
                                else if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".avi")) tvIcon.setText("🎥");
                                else if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".m4a")) tvIcon.setText("🎵");
                                else if (lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".7z")) tvIcon.setText("📦");
                                else if (lower.endsWith(".apk")) tvIcon.setText("📱");
                                else if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".py") || lower.endsWith(".java") || lower.endsWith(".c")) tvIcon.setText("📝");
                                else tvIcon.setText("📄");

                                String shortDir = path;
                                int lastSlash = path.lastIndexOf('/');
                                if (lastSlash > 0) {
                                    shortDir = path.substring(0, lastSlash);
                                    if (shortDir.length() > 28) {
                                        shortDir = "..." + shortDir.substring(shortDir.length() - 25);
                                    }
                                }
                                tvDetails.setText(sizeStr + " • " + shortDir);

                                btnGet.setOnClickListener(v -> downloadFileFromLaptop(path, name, dialog));
                                containerResults.addView(row);
                            }
                        });
                    } else {
                        runOnUiThread(() -> tvStatus.setText("Search failed (HTTP " + code + ")"));
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> tvStatus.setText("Error: " + e.getMessage()));
                }
            }).start();
        };

        btnSearch.setOnClickListener(v -> runSearch.run());
        etQuery.setOnEditorActionListener((v, actionId, event) -> {
            runSearch.run();
            return true;
        });

        dialog.show();
    }

    private void downloadFileFromLaptop(String pcPath, String filename, AlertDialog parentDialog) {
        Toast.makeText(this, "⏳ Downloading " + filename + "...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                PairedDevice active = DeviceManager.getActiveDevice(this);
                String url = active.getBaseUrl() + "/api/files/download_pc?path=" + URLEncoder.encode(pcPath, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                if (active.isPaired()) {
                    conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                }

                File destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!destDir.exists()) destDir.mkdirs();
                File dest = new File(destDir, filename);
                int c = 1;
                String base = filename;
                String ext = "";
                int dot = filename.lastIndexOf(".");
                if (dot > 0) {
                    base = filename.substring(0, dot);
                    ext = filename.substring(dot);
                }
                while (dest.exists()) {
                    dest = new File(destDir, base + " (" + c + ")" + ext);
                    c++;
                }

                String mimeType = PCFileProvider.getMimeType(dest.getName());
                OutputStream fos = null;
                try {
                    fos = new FileOutputStream(dest);
                } catch (Exception directEx) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            ContentValues values = new ContentValues();
                            values.put(MediaStore.MediaColumns.DISPLAY_NAME, dest.getName());
                            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                            Uri insertedUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                            if (insertedUri != null) {
                                fos = getContentResolver().openOutputStream(insertedUri);
                            }
                        } catch (Exception ignored) {}
                    }
                }

                if (fos != null) {
                    try (InputStream in = conn.getInputStream();
                         OutputStream outStream = fos) {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) != -1) {
                            outStream.write(buf, 0, r);
                        }
                        outStream.flush();
                    }

                    try {
                        MediaScannerConnection.scanFile(this, new String[]{dest.getAbsolutePath()}, new String[]{mimeType}, null);
                    } catch (Exception ignored) {}

                    final File finalFile = dest;
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "✅ Saved: " + finalFile.getName() + "! Opening...", Toast.LENGTH_LONG).show();
                        openDownloadedFile(finalFile);
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Download error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void openDownloadedFile(File file) {
        try {
            String mimeType = PCFileProvider.getMimeType(file.getAbsolutePath());
            Uri fileUri = PCFileProvider.getUriForFile(this, file);
            boolean isApk = file.getName().toLowerCase().endsWith(".apk") || "application/vnd.android.package-archive".equals(mimeType);
            if (isApk) {
                mimeType = "application/vnd.android.package-archive";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
                    pendingInstallApk = file;
                    new AlertDialog.Builder(this)
                        .setTitle("📦 Install Permission Required")
                        .setMessage("To install " + file.getName() + ", please enable 'Allow from this source' for PC Connect in Settings.")
                        .setPositiveButton("Open Settings", (d, w) -> {
                            try {
                                Intent sIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(sIntent);
                            } catch (Exception ex) {
                                try {
                                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                                } catch (Exception ignored) {}
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                    return;
                }
            }

            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(fileUri, mimeType);
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (isApk) {
                viewIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            }

            try {
                List<android.content.pm.ResolveInfo> resInfoList = getPackageManager().queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY);
                for (android.content.pm.ResolveInfo resolveInfo : resInfoList) {
                    grantUriPermission(resolveInfo.activityInfo.packageName, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Exception ignored) {}

            if (isApk) {
                String[] commonInstallers = {"com.google.android.packageinstaller", "com.android.packageinstaller", "com.google.android.permissioncontroller"};
                for (String pkg : commonInstallers) {
                    try {
                        grantUriPermission(pkg, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {}
                }
            }

            startActivity(viewIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Saved to Downloads: " + file.getName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void streamFileFromLaptop(String pcPath, String filename) {
        try {
            PairedDevice active = DeviceManager.getActiveDevice(this);
            if (active == null) {
                Toast.makeText(this, "No active PC connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String streamUrl = active.getBaseUrl() + "/api/files/download_pc?path=" + URLEncoder.encode(pcPath, "UTF-8");
            if (active.isPaired()) {
                streamUrl += "&token=" + active.authToken;
            }
            String mimeType = PCFileProvider.getMimeType(filename);
            String lower = filename.toLowerCase();
            if (mimeType == null || "application/octet-stream".equals(mimeType)) {
                if (lower.endsWith(".mp4")) mimeType = "video/mp4";
                else if (lower.endsWith(".mkv")) mimeType = "video/x-matroska";
                else if (lower.endsWith(".webm")) mimeType = "video/webm";
                else if (lower.endsWith(".avi")) mimeType = "video/x-msvideo";
                else if (lower.endsWith(".mov")) mimeType = "video/quicktime";
                else if (lower.endsWith(".mp3")) mimeType = "audio/mpeg";
                else if (lower.endsWith(".flac")) mimeType = "audio/flac";
                else if (lower.endsWith(".wav")) mimeType = "audio/wav";
                else if (lower.endsWith(".m4a")) mimeType = "audio/mp4";
                else if (lower.endsWith(".ogg")) mimeType = "audio/ogg";
                else if (lower.endsWith(".pdf")) mimeType = "application/pdf";
                else if (lower.endsWith(".png")) mimeType = "image/png";
                else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) mimeType = "image/jpeg";
                else if (lower.endsWith(".webp")) mimeType = "image/webp";
                else if (lower.endsWith(".gif")) mimeType = "image/gif";
                else if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md") || lower.endsWith(".py") || lower.endsWith(".java") || lower.endsWith(".c") || lower.endsWith(".sh")) mimeType = "text/plain";
                else mimeType = "*/*";
            }

            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(Uri.parse(streamUrl), mimeType);
            viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(Intent.createChooser(viewIntent, "Stream / Open " + filename));
            } catch (Exception ex) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(streamUrl));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(browserIntent);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Stream error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showFileBrowseDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_file_browse, null);
        builder.setView(dialogView);

        TextView tvStorage = dialogView.findViewById(R.id.tv_browser_storage);
        Button btnHome = dialogView.findViewById(R.id.btn_browse_home);
        Button btnUp = dialogView.findViewById(R.id.btn_browse_up);
        TextView tvPath = dialogView.findViewById(R.id.tv_browse_path);
        Button btnRefresh = dialogView.findViewById(R.id.btn_browse_refresh);

        Button btnShortcutDrives = dialogView.findViewById(R.id.btn_shortcut_drives);
        Button btnQuickDownloads = dialogView.findViewById(R.id.btn_shortcut_downloads);
        Button btnQuickDocs = dialogView.findViewById(R.id.btn_shortcut_documents);
        Button btnQuickPics = dialogView.findViewById(R.id.btn_shortcut_pictures);
        Button btnQuickVids = dialogView.findViewById(R.id.btn_shortcut_videos);
        Button btnQuickDesk = dialogView.findViewById(R.id.btn_shortcut_desktop);

        ProgressBar pbLoading = dialogView.findViewById(R.id.pb_browse_loading);
        TextView tvEmpty = dialogView.findViewById(R.id.tv_browse_empty);
        LinearLayout containerItems = dialogView.findViewById(R.id.container_browse_items);

        AlertDialog dialog = builder.create();

        class BrowseLoader {
            void load(String targetPath) {
                currentBrowsePath = targetPath;
                pbLoading.setVisibility(View.VISIBLE);
                tvEmpty.setVisibility(View.GONE);
                containerItems.removeAllViews();

                new Thread(() -> {
                    try {
                        PairedDevice active = DeviceManager.getActiveDevice(MainActivity.this);
                        String url = active.getBaseUrl() + "/api/laptop/files/list?path=" + URLEncoder.encode(targetPath, "UTF-8");
                        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(12000);
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
                            String curPath = res.optString("current_path", targetPath);
                            parentBrowsePath = res.isNull("parent_path") ? null : res.optString("parent_path", null);
                            JSONObject stor = res.optJSONObject("storage");
                            String storStr = (stor != null) ? stor.optString("formatted", "") : "";
                            JSONArray items = res.optJSONArray("items");

                            runOnUiThread(() -> {
                                pbLoading.setVisibility(View.GONE);
                                if (!storStr.isEmpty()) {
                                    tvStorage.setText(storStr);
                                    if (tvPcStorage != null) tvPcStorage.setText("💽 Disk: " + storStr);
                                }
                                String dispPath = curPath;
                                if (dispPath.startsWith("/home/")) {
                                    int nextSlash = dispPath.indexOf('/', 6);
                                    if (nextSlash > 0) dispPath = "~" + dispPath.substring(nextSlash);
                                    else dispPath = "~";
                                }
                                tvPath.setText("shortcuts".equals(dispPath) ? "Shortcuts / Drives" : dispPath);
                                btnUp.setEnabled(parentBrowsePath != null);
                                btnUp.setAlpha(parentBrowsePath != null ? 1.0f : 0.4f);

                                if (items == null || items.length() == 0) {
                                    tvEmpty.setVisibility(View.VISIBLE);
                                    return;
                                }

                                LayoutInflater inflater = LayoutInflater.from(MainActivity.this);
                                for (int i = 0; i < items.length(); i++) {
                                    JSONObject it = items.optJSONObject(i);
                                    if (it == null) continue;
                                    String name = it.optString("name", "Unknown");
                                    String itemPath = it.optString("path", "");
                                    boolean isDir = it.optBoolean("is_dir", false);
                                    String sizeFormatted = it.optString("size_formatted", "");
                                    String customIcon = it.optString("icon", "");

                                    View row = inflater.inflate(R.layout.item_file_browse, containerItems, false);
                                    TextView tvIcon = row.findViewById(R.id.tv_browse_item_icon);
                                    TextView tvName = row.findViewById(R.id.tv_browse_item_name);
                                    TextView tvDetails = row.findViewById(R.id.tv_browse_item_details);
                                    Button btnAction = row.findViewById(R.id.btn_browse_item_action);
                                    Button btnDownload = row.findViewById(R.id.btn_browse_item_download);

                                    tvName.setText(name);
                                    if (isDir) {
                                        tvIcon.setText(!customIcon.isEmpty() ? customIcon : "📁");
                                        tvDetails.setText("Directory / Folder");
                                        btnAction.setText("Open");
                                        btnAction.setBackgroundResource(R.drawable.card_bg);
                                        btnAction.setOnClickListener(v -> load(itemPath));
                                        if (btnDownload != null) btnDownload.setVisibility(View.GONE);
                                        row.setOnClickListener(v -> load(itemPath));
                                    } else {
                                        String lower = name.toLowerCase();
                                        if (lower.endsWith(".pdf")) tvIcon.setText("📕");
                                        else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif")) tvIcon.setText("🖼️");
                                        else if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".avi") || lower.endsWith(".webm")) tvIcon.setText("🎥");
                                        else if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".m4a") || lower.endsWith(".ogg")) tvIcon.setText("🎵");
                                        else if (lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".7z")) tvIcon.setText("📦");
                                        else if (lower.endsWith(".apk")) tvIcon.setText("📱");
                                        else if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".py") || lower.endsWith(".java") || lower.endsWith(".c") || lower.endsWith(".sh")) tvIcon.setText("📝");
                                        else tvIcon.setText("📄");

                                        tvDetails.setText(sizeFormatted);
                                        btnAction.setText("▶️ Stream");
                                        btnAction.setBackgroundResource(R.drawable.btn_approve);
                                        btnAction.setOnClickListener(v -> streamFileFromLaptop(itemPath, name));
                                        if (btnDownload != null) {
                                            btnDownload.setVisibility(View.VISIBLE);
                                            btnDownload.setOnClickListener(v -> downloadFileFromLaptop(itemPath, name, null));
                                        }
                                        row.setOnClickListener(v -> streamFileFromLaptop(itemPath, name));
                                    }

                                    containerItems.addView(row);
                                }
                            });
                        } else {
                            runOnUiThread(() -> {
                                pbLoading.setVisibility(View.GONE);
                                tvEmpty.setText("Failed to load folder (HTTP " + code + ")");
                                tvEmpty.setVisibility(View.VISIBLE);
                            });
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            pbLoading.setVisibility(View.GONE);
                            tvEmpty.setText("Error: " + e.getMessage());
                            tvEmpty.setVisibility(View.VISIBLE);
                        });
                    }
                }).start();
            }
        }

        BrowseLoader loader = new BrowseLoader();
        btnHome.setOnClickListener(v -> loader.load("shortcuts"));
        if (btnShortcutDrives != null) btnShortcutDrives.setOnClickListener(v -> loader.load("shortcuts"));
        btnUp.setOnClickListener(v -> {
            if (parentBrowsePath != null) {
                loader.load(parentBrowsePath);
            }
        });
        btnRefresh.setOnClickListener(v -> loader.load(currentBrowsePath));

        btnQuickDownloads.setOnClickListener(v -> loader.load("~/Downloads"));
        btnQuickDocs.setOnClickListener(v -> loader.load("~/Documents"));
        btnQuickPics.setOnClickListener(v -> loader.load("~/Pictures"));
        btnQuickVids.setOnClickListener(v -> loader.load("~/Videos"));
        btnQuickDesk.setOnClickListener(v -> loader.load("~/Desktop"));

        loader.load("shortcuts");
        dialog.show();
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
        if (RingManager.isRinging()) {
            RingManager.stopAlarm(this);
            Toast.makeText(this, "🔔 Alarm stopped", Toast.LENGTH_SHORT).show();
        }

        // Auto-install pending APK if permission was just granted
        if (pendingInstallApk != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (getPackageManager().canRequestPackageInstalls()) {
                File apkToInstall = pendingInstallApk;
                pendingInstallApk = null;
                openDownloadedFile(apkToInstall);
            }
        }

        // Auto-sync clipboard if changed
        AuthService.checkAndSyncPhoneClipboard(this);

        // Register clipboard listener while in foreground
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                if (mainClipListener == null) {
                    mainClipListener = () -> AuthService.checkAndSyncPhoneClipboard(MainActivity.this);
                }
                cm.removePrimaryClipChangedListener(mainClipListener);
                cm.addPrimaryClipChangedListener(mainClipListener);
            }
        } catch (Exception ignored) {}

        refreshDeviceList();
        updateChallengeUIFromStore();
        checkActiveChallenge();
        fetchMediaStatus();
        fetchLaptopStatus();

        // Start periodic check while activity is in foreground
        pollRunnable = new Runnable() {
            private int statusCounter = 0;
            @Override
            public void run() {
                checkActiveChallenge();
                fetchMediaStatus();
                statusCounter++;
                if (statusCounter % 2 == 0) {
                    fetchLaptopStatus();
                }
                pollHandler.postDelayed(this, 2500);
            }
        };
        pollHandler.postDelayed(pollRunnable, 2500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        cancelBiometricPrompt();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && mainClipListener != null) {
                cm.removePrimaryClipChangedListener(mainClipListener);
            }
        } catch (Exception ignored) {}
        if (pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            AuthService.checkAndSyncPhoneClipboard(this);
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

