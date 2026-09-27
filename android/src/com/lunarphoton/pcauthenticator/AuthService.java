package com.lunarphoton.pcauthenticator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.net.Uri;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import android.os.BatteryManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.media.MediaScannerConnection;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyCallback;
import android.telephony.PhoneStateListener;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.StatFs;

public class AuthService extends Service {
    private static final String TAG = "PCAuthService";
    public static final String CHANNEL_FOREGROUND = "pc_auth_status";
    public static final String CHANNEL_CHALLENGE = "pc_auth_challenge";

    public static final int NOTIFICATION_ID_FOREGROUND = 1001;
    public static final int NOTIFICATION_ID_CHALLENGE = 1002;

    public static final String ACTION_CHALLENGE = "com.lunarphoton.pcauthenticator.NEW_CHALLENGE";
    public static final String ACTION_STATUS = "com.lunarphoton.pcauthenticator.CONNECTION_STATUS";

    public static final String ACTION_RECONNECT = "com.lunarphoton.pcauthenticator.RECONNECT";

    private volatile boolean isRunning = false;
    private volatile HttpURLConnection currentConn = null;
    private Thread workerThread;
    private PowerManager.WakeLock wakeLock;
    private FileServer fileServer;

    private BroadcastReceiver phoneStateReceiver = null;
    private Object telephonyCallbackObj = null;
    private String lastSentCallState = "idle";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            android.os.StrictMode.VmPolicy.Builder builder = new android.os.StrictMode.VmPolicy.Builder();
            android.os.StrictMode.setVmPolicy(builder.build());
        } catch (Exception ignored) {}
        createNotificationChannels();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PCAuth::ListeningLock");
            wakeLock.acquire(10 * 60 * 1000L); // 10 minutes, refreshed periodically
        }

        fileServer = new FileServer(this);
        fileServer.start();

        IntentFilter unlockFilter = new IntentFilter();
        unlockFilter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    if (RingManager.isRinging()) {
                        Log.i(TAG, "Device unlocked by user - stopping Find My Phone alarm");
                        RingManager.stopAlarm(context);
                    }
                }
            }
        }, unlockFilter);

        // Automatic battery & charging status reporting
        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(new BroadcastReceiver() {
            private int lastReportedLevel = -1;
            private boolean lastReportedCharging = false;
            @Override
            public void onReceive(Context context, Intent intent) {
                int rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                int level = (rawLevel >= 0 && scale > 0) ? (rawLevel * 100) / scale : -1;
                if (level != lastReportedLevel || isCharging != lastReportedCharging) {
                    lastReportedLevel = level;
                    lastReportedCharging = isCharging;
                    PairedDevice active = DeviceManager.getActiveDevice(AuthService.this);
                    if (active != null) {
                        sendPhoneStatusToPc(active);
                    }
                }
            }
        }, batteryFilter);

        startUdpBeaconListener();
        initTelephonyListener();
    }

    private void startUdpBeaconListener() {
        new Thread(() -> {
            DatagramSocket udpSock = null;
            try {
                udpSock = new DatagramSocket(1760);
                udpSock.setReuseAddress(true);
                byte[] buf = new byte[1024];
                while (isRunning) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        udpSock.receive(packet);
                        String msg = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                        JSONObject json = new JSONObject(msg);
                        String type = json.optString("type", "");
                        if ("pc_auth_discovery_reply".equals(type) || "pc_auth_beacon".equals(type)) {
                            String host = json.optString("hostname", "");
                            String newIp = packet.getAddress().getHostAddress();
                            int port = json.optInt("port", 1760);

                            PairedDevice active = DeviceManager.getActiveDevice(this);
                            String devId = json.optString("device_id", null);
                            if (active != null && ((devId != null && devId.equals(active.deviceId)) || host.equalsIgnoreCase(active.hostname))) {
                                if (!active.ip.equals(newIp)) {
                                    Log.i(TAG, "Beacon: laptop changed IP to " + newIp);
                                    DeviceManager.addOrUpdateDevice(this, new PairedDevice(devId, host, newIp, port, "user", null, null, true));
                                    if (currentConn != null) {
                                        try { currentConn.disconnect(); } catch (Exception ignored) {}
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.w(TAG, "Cannot bind UDP listener: " + e.getMessage());
            } finally {
                if (udpSock != null) udpSock.close();
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID_FOREGROUND, buildForegroundNotification("Connecting to laptop..."));

        if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
            if (currentConn != null) {
                new Thread(() -> {
                    try { currentConn.disconnect(); } catch (Exception ignored) {}
                }).start();
            }
        }

        if (phoneStateReceiver == null) {
            initTelephonyListener();
        }

        if (!isRunning) {
            isRunning = true;
            workerThread = new Thread(this::listenLoop);
            workerThread.start();
        }

        return START_STICKY;
    }

    private void listenLoop() {
        int consecutiveFails = 0;
        while (isRunning) {
            PairedDevice active = DeviceManager.getActiveDevice(this);
            String serverUrl = active.getBaseUrl();

            HttpURLConnection conn = null;
            try {
                URL url = new URL(serverUrl + "/login/json");
                conn = (HttpURLConnection) url.openConnection();
                currentConn = conn;
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(60000); // 60s read timeout
                conn.setRequestProperty("Accept", "application/x-ndjson");

                if (active.isPaired()) {
                    conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    consecutiveFails = 0;
                    broadcastStatus(true, "Connected • 🔒 E2E Secure");
                    updateForegroundNotification("Connected to " + active.hostname + " (Secure)");
                    sendPhoneStatusToPc(active);

                    try (InputStream is = conn.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                        String line;
                        while (isRunning && (line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty()) continue;

                            try {
                                JSONObject json = new JSONObject(line);
                                String event = json.optString("event", "");
                                if ("message".equals(event)) {
                                    handleChallengeEvent(json);
                                } else if ("cancelled".equals(event)) {
                                    cancelChallengeNotification(AuthService.this);
                                    Intent updateIntent = new Intent("com.lunarphoton.pcauthenticator.CHALLENGE_RESOLVED");
                                    sendBroadcast(updateIntent);
                                } else if ("ring".equals(event)) {
                                    RingManager.startAlarm(AuthService.this);
                                } else if ("unring".equals(event) || "stop_alarm".equals(event)) {
                                    RingManager.stopAlarm(AuthService.this);
                                } else if ("clipboard".equals(event)) {
                                    handleClipboardEvent(json);
                                } else if ("incoming_file".equals(event)) {
                                    handleIncomingFileEvent(json);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "JSON parse error", e);
                            }
                        }
                    }
                } else if (code == 401) {
                    consecutiveFails = 0;
                    broadcastStatus(false, "⚠️ Unpaired (Tap to Authorize)");
                    updateForegroundNotification("⚠️ Authorization Required - Tap to Pair");
                } else {
                    consecutiveFails++;
                    broadcastStatus(false, "Server HTTP " + code);
                    updateForegroundNotification("Server HTTP " + code);
                }
            } catch (Exception e) {
                consecutiveFails++;
                Log.w(TAG, "Connection loop error: " + e.getMessage() + " (fail #" + consecutiveFails + ")");
                broadcastStatus(false, "Disconnected (Reconnecting...)");
                updateForegroundNotification("Searching for laptop...");

                // If connection failed 2+ times, automatically probe network/hotspot to find new laptop IP
                if (consecutiveFails >= 2) {
                    DeviceManager.discoverDevices(this, new DeviceManager.DiscoveryCallback() {
                        @Override
                        public void onDiscovered(PairedDevice device) {
                            PairedDevice activeDev = DeviceManager.getActiveDevice(AuthService.this);
                            if (activeDev != null && ((device.deviceId != null && device.deviceId.equals(activeDev.deviceId)) || device.hostname.equalsIgnoreCase(activeDev.hostname)) && !device.ip.equals(activeDev.ip)) {
                                Log.i(TAG, "Auto-discovered laptop at new IP: " + device.ip);
                                DeviceManager.addOrUpdateDevice(AuthService.this, device);
                                if (currentConn != null) {
                                    try { currentConn.disconnect(); } catch (Exception ignored) {}
                                }
                            }
                        }

                        @Override
                        public void onFinished(List<PairedDevice> allFound) {}
                    });
                }
            } finally {
                if (conn != null) conn.disconnect();
                currentConn = null;
            }

            if (isRunning) {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private void handleChallengeEvent(JSONObject json) {
        String title = json.optString("title", "🔒 PC Unlock Request");
        String message = json.optString("message", "Screen unlock requested on your laptop. Tap Approve to unlock.");
        String sessionId = json.optString("id", "");

        // 0. Save active challenge into shared preferences so MainActivity renders it instantly
        SharedPreferences prefs = getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE);
        prefs.edit()
             .putString("active_challenge_id", sessionId)
             .putString("active_challenge_msg", message)
             .putLong("active_challenge_expiry", System.currentTimeMillis() + 35000L)
             .apply();

        // 1. Wake up the phone screen immediately (turn display ON)
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            try {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                        "PCAuth::AlertWakeScreen"
                );
                wl.acquire(15000); // Keep screen lit for 15s
            } catch (Exception ignored) {}
        }

        // 2. Trigger high-priority notification with FullScreenIntent & Lock Screen actions
        triggerChallengeNotification(title, message, sessionId);

        boolean requireBiometric = prefs.getBoolean("require_biometric", true);

        // 3. Launch MainActivity over keyguard
        Intent lockIntent = new Intent(this, MainActivity.class);
        lockIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        lockIntent.putExtra("from_challenge", true);
        lockIntent.putExtra("auto_biometric", requireBiometric);
        lockIntent.putExtra("message", message);
        lockIntent.putExtra("session_id", sessionId);
        try {
            startActivity(lockIntent);
        } catch (Exception ignored) {}

        Intent intent = new Intent(ACTION_CHALLENGE);
        intent.putExtra("title", title);
        intent.putExtra("message", message);
        intent.putExtra("session_id", sessionId);
        sendBroadcast(intent);

        vibrateStrong();
    }

    private void triggerChallengeNotification(String title, String message, String sessionId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        SharedPreferences prefs = getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE);
        boolean requireBiometric = prefs.getBoolean("require_biometric", true);

        // Intent to launch MainActivity when tapped or as full-screen alert
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mainIntent.putExtra("from_challenge", true);
        mainIntent.putExtra("auto_biometric", requireBiometric);
        mainIntent.putExtra("message", message);
        mainIntent.putExtra("session_id", sessionId);

        PendingIntent mainPending = PendingIntent.getActivity(
                this, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        PendingIntent fullScreenPending = PendingIntent.getActivity(
                this, 100, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        // Approve Intent (Activity with biometric prompt OR direct Broadcast if disabled)
        PendingIntent approvePending;
        String approveTitle;
        if (requireBiometric) {
            approveTitle = "👆 Approve (Fingerprint)";
            Intent approveActIntent = new Intent(this, MainActivity.class);
            approveActIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            approveActIntent.putExtra("from_challenge", true);
            approveActIntent.putExtra("auto_biometric", true);
            approveActIntent.putExtra("message", message);
            approveActIntent.putExtra("session_id", sessionId);
            approvePending = PendingIntent.getActivity(
                    this, 1, approveActIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );
        } else {
            approveTitle = "✅ Approve";
            Intent approveIntent = new Intent(this, ActionReceiver.class);
            approveIntent.setAction(ActionReceiver.ACTION_APPROVE);
            approveIntent.putExtra("session_id", sessionId);
            approvePending = PendingIntent.getBroadcast(
                    this, 1, approveIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );
        }

        // Deny Intent
        Intent denyIntent = new Intent(this, ActionReceiver.class);
        denyIntent.setAction(ActionReceiver.ACTION_DENY);
        denyIntent.putExtra("session_id", sessionId);
        PendingIntent denyPending = PendingIntent.getBroadcast(
                this, 2, denyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        // Public version shown on lock screen with explicit action buttons
        Notification.Builder publicBuilder;
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            publicBuilder = new Notification.Builder(this, CHANNEL_CHALLENGE);
            builder = new Notification.Builder(this, CHANNEL_CHALLENGE);
        } else {
            publicBuilder = new Notification.Builder(this);
            builder = new Notification.Builder(this);
        }

        publicBuilder.setSmallIcon(R.mipmap.ic_launcher)
                     .setContentTitle(title)
                     .setContentText(message)
                     .setContentIntent(mainPending)
                     .setStyle(new Notification.BigTextStyle().bigText(message).setBigContentTitle(title))
                     .addAction(new Notification.Action.Builder(
                             android.R.drawable.checkbox_on_background, approveTitle, approvePending).build())
                     .addAction(new Notification.Action.Builder(
                             android.R.drawable.ic_delete, "❌ Deny", denyPending).build());

        builder.setSmallIcon(R.mipmap.ic_launcher)
               .setContentTitle(title)
               .setContentText(message)
               .setContentIntent(mainPending)
               .setFullScreenIntent(fullScreenPending, true)
               .setPublicVersion(publicBuilder.setAutoCancel(true).build())
               .setStyle(new Notification.BigTextStyle().bigText(message).setBigContentTitle(title))
               .setPriority(Notification.PRIORITY_MAX)
               .setDefaults(Notification.DEFAULT_ALL)
               .setAutoCancel(true)
               .setOngoing(false)
               .addAction(new Notification.Action.Builder(
                       android.R.drawable.checkbox_on_background, approveTitle, approvePending).build())
               .addAction(new Notification.Action.Builder(
                       android.R.drawable.ic_delete, "❌ Deny", denyPending).build());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_CALL);
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        }

        nm.notify(NOTIFICATION_ID_CHALLENGE, builder.build());

        // Auto-cancel notification after 35 seconds when challenge expires
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            cancelChallengeNotification(AuthService.this);
        }, 35000L);
    }

    public static void cancelChallengeNotification(Context context) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID_CHALLENGE);
            }
            context.getSharedPreferences("pc_auth_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .remove("active_challenge_id")
                    .remove("active_challenge_msg")
                    .remove("active_challenge_expiry")
                    .apply();
        } catch (Exception ignored) {}
    }

    private Notification buildForegroundNotification(String status) {
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, mainIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_FOREGROUND);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_MIN);
        }

        return builder.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("PC Authenticator")
                .setContentText(status)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateForegroundNotification(String status) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID_FOREGROUND, buildForegroundNotification(status));
        }
    }

    private void broadcastStatus(boolean connected, String statusText) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra("connected", connected);
        intent.putExtra("status_text", statusText);
        sendBroadcast(intent);
    }

    private void vibrateStrong() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                long[] pattern = {0, 250, 150, 250};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    v.vibrate(pattern, -1);
                }
            }
        } catch (Exception ignored) {}
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            // 1. Foreground Status Channel (Silent)
            NotificationChannel fgChan = new NotificationChannel(
                    CHANNEL_FOREGROUND,
                    "Service Status",
                    NotificationManager.IMPORTANCE_LOW
            );
            fgChan.setDescription("Shows active connection state to your laptop");
            fgChan.setShowBadge(false);
            nm.createNotificationChannel(fgChan);

            // 2. High-Priority Challenge Channel (Sound & Vibration)
            NotificationChannel chChan = new NotificationChannel(
                    CHANNEL_CHALLENGE,
                    "PC Unlock Requests",
                    NotificationManager.IMPORTANCE_HIGH
            );
            chChan.setDescription("Alerts for 2FA screen unlock challenges");
            chChan.enableVibration(true);
            chChan.enableLights(true);
            chChan.setLightColor(Color.CYAN);
            chChan.setBypassDnd(true);
            chChan.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(chChan);
        }
    }

    private void sendPhoneStatusToPc(PairedDevice active) {
        if (active == null) return;
        new Thread(() -> {
            try {
                Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int level = -1;
                boolean isCharging = false;
                if (batteryIntent != null) {
                    int rawLevel = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (rawLevel >= 0 && scale > 0) {
                        level = (rawLevel * 100) / scale;
                    }
                    int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL;
                }

                // Storage stats
                long storageFree = 0;
                long storageTotal = 0;
                try {
                    File extDir = Environment.getExternalStorageDirectory();
                    StatFs stat = new StatFs(extDir.getPath());
                    long blockSize = stat.getBlockSizeLong();
                    storageFree = stat.getAvailableBlocksLong() * blockSize;
                    storageTotal = stat.getBlockCountLong() * blockSize;
                } catch (Exception ignored) {}

                JSONObject body = new JSONObject();
                if (level >= 0) body.put("battery", level);
                body.put("charging", isCharging);
                body.put("port", FileServer.PORT);

                String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
                String model = Build.MODEL != null ? Build.MODEL : "Android";
                String fullName = model.toLowerCase().startsWith(manufacturer.toLowerCase()) ? model : (manufacturer + " " + model);
                body.put("device_name", fullName.trim());
                body.put("model", model);
                body.put("manufacturer", manufacturer);
                body.put("android_version", Build.VERSION.RELEASE);
                body.put("sdk_int", Build.VERSION.SDK_INT);
                body.put("storage_free", storageFree);
                body.put("storage_total", storageTotal);

                HttpURLConnection conn = (HttpURLConnection) new URL(active.getBaseUrl() + "/api/phone/status").openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                if (active.isPaired()) {
                    conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                }
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private void handleClipboardEvent(JSONObject json) {
        String text = json.optString("text", "");
        if (!text.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("PC Connect", text));
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    private void handleIncomingFileEvent(JSONObject json) {
        String downloadUrl = json.optString("download_url", "");
        String filename = json.optString("filename", "received_file");
        if (downloadUrl.isEmpty()) return;

        new Thread(() -> {
            try {
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

                HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);

                OutputStream fos = null;
                try {
                    fos = new FileOutputStream(dest);
                } catch (Exception directEx) {
                    Log.w(TAG, "Direct FileOutputStream failed, trying MediaStore: " + directEx.getMessage());
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
                        } catch (Exception mediaEx) {
                            Log.e(TAG, "MediaStore insert error", mediaEx);
                        }
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

                    showFileNotification(dest);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error downloading incoming file", e);
            }
        }).start();
    }

    private void showFileNotification(File file) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            String mimeType = PCFileProvider.getMimeType(file.getAbsolutePath());
            Uri fileUri = PCFileProvider.getUriForFile(this, file);

            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(fileUri, mimeType);
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            try {
                List<ResolveInfo> resInfoList = getPackageManager().queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY);
                for (ResolveInfo resolveInfo : resInfoList) {
                    grantUriPermission(resolveInfo.activityInfo.packageName, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Exception ignored) {}

            PendingIntent pi = PendingIntent.getActivity(
                    this, (int) System.currentTimeMillis(), viewIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, FileServer.CHANNEL_FILE);
            } else {
                builder = new Notification.Builder(this);
            }

            String typeLabel = "File";
            if (mimeType.startsWith("image/")) typeLabel = "Image";
            else if (mimeType.startsWith("video/")) typeLabel = "Video";
            else if (mimeType.startsWith("audio/")) typeLabel = "Audio";
            else if (mimeType.equals("application/pdf")) typeLabel = "PDF Document";
            else if (mimeType.contains("word") || mimeType.contains("document")) typeLabel = "Document";
            else if (mimeType.contains("excel") || mimeType.contains("sheet")) typeLabel = "Spreadsheet";
            else if (mimeType.contains("package-archive")) typeLabel = "Android App (APK)";
            else if (mimeType.contains("zip") || mimeType.contains("compressed") || mimeType.contains("tar")) typeLabel = "Archive";

            builder.setContentTitle("📁 " + typeLabel + " Received from PC")
                    .setContentText(file.getName() + " (" + formatFileSize(file.length()) + ")")
                    .setSubText(typeLabel)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setPriority(Notification.PRIORITY_HIGH)
                        .setVibrate(new long[]{0, 250, 150, 250});
            }

            nm.notify((int) (FileServer.NOTIF_BASE_ID + (System.currentTimeMillis() % 1000)), builder.build());
            Log.i(TAG, "Notification posted for incoming file: " + file.getName() + " (" + mimeType + ")");
        } catch (Throwable t) {
            Log.e(TAG, "Error posting file notification: " + t.getMessage(), t);
        }
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);
        return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private void initTelephonyListener() {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE not granted; waiting for user permission");
        }

        if (telephonyCallbackObj == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallbackObj = Api31TelephonyHelper.register(this, this);
            } else {
                telephonyCallbackObj = LegacyTelephonyHelper.register(this, this);
            }
        }

        try {
            if (phoneStateReceiver == null) {
                IntentFilter phoneFilter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
                phoneStateReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
                            String stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                            if (stateStr != null) {
                                handlePhoneStateString(stateStr);
                            }
                        }
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(phoneStateReceiver, phoneFilter, Context.RECEIVER_EXPORTED);
                } else {
                    registerReceiver(phoneStateReceiver, phoneFilter);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "phoneStateReceiver registration error: " + t.getMessage());
        }
    }

    void handlePhoneCallStateCode(int state) {
        String mappedState = "idle";
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            mappedState = "ringing";
        } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            mappedState = "talking";
        } else if (state == TelephonyManager.CALL_STATE_IDLE) {
            mappedState = "idle";
        } else {
            return;
        }
        sendTelephonyCallState(mappedState);
    }

    void handlePhoneStateString(String stateStr) {
        String mappedState = "idle";
        if (TelephonyManager.EXTRA_STATE_RINGING.equalsIgnoreCase(stateStr)) {
            mappedState = "ringing";
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equalsIgnoreCase(stateStr)) {
            mappedState = "talking";
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equalsIgnoreCase(stateStr)) {
            mappedState = "idle";
        } else {
            return;
        }
        sendTelephonyCallState(mappedState);
    }

    private void sendTelephonyCallState(String state) {
        if (state == null) return;
        synchronized (this) {
            if (state.equals(lastSentCallState)) {
                return;
            }
            lastSentCallState = state;
        }

        Log.i(TAG, "Dispatching phone call state to PC: " + state);
        new Thread(() -> {
            try {
                PairedDevice active = DeviceManager.getActiveDevice(AuthService.this);
                if (active == null) return;

                URL u = new URL(active.getBaseUrl() + "/api/telephony/call");
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                if (active.isPaired() && active.authToken != null) {
                    conn.setRequestProperty("Authorization", "Bearer " + active.authToken);
                }
                JSONObject payload = new JSONObject();
                payload.put("state", state);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes("UTF-8"));
                }
                int code = conn.getResponseCode();
                Log.d(TAG, "Sent telephony state '" + state + "' to PC, response: " + code);
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Failed to send telephony state to PC: " + e.getMessage());
            }
        }).start();
    }

    private static class Api31TelephonyHelper {
        static Object register(Context context, AuthService service) {
            try {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm == null) return null;
                CallCallback cb = new CallCallback(service);
                tm.registerTelephonyCallback(context.getMainExecutor(), cb);
                return cb;
            } catch (Throwable t) {
                Log.w(TAG, "registerTelephonyCallback failed: " + t.getMessage());
                return null;
            }
        }

        static void unregister(Context context, Object cb) {
            try {
                if (cb instanceof TelephonyCallback) {
                    TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                    if (tm != null) {
                        tm.unregisterTelephonyCallback((TelephonyCallback) cb);
                    }
                }
            } catch (Throwable ignored) {}
        }

        private static class CallCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
            private final AuthService service;
            CallCallback(AuthService service) {
                this.service = service;
            }
            @Override
            public void onCallStateChanged(int state) {
                service.handlePhoneCallStateCode(state);
            }
        }
    }

    private static class LegacyTelephonyHelper {
        static Object register(Context context, AuthService service) {
            try {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm == null) return null;
                PhoneStateListener psl = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        service.handlePhoneCallStateCode(state);
                    }
                };
                tm.listen(psl, PhoneStateListener.LISTEN_CALL_STATE);
                return psl;
            } catch (Throwable t) {
                Log.w(TAG, "PhoneStateListener failed: " + t.getMessage());
                return null;
            }
        }

        static void unregister(Context context, Object psl) {
            try {
                if (psl instanceof PhoneStateListener) {
                    TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                    if (tm != null) {
                        tm.listen((PhoneStateListener) psl, PhoneStateListener.LISTEN_NONE);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (fileServer != null) {
            fileServer.stop();
        }
        if (phoneStateReceiver != null) {
            try {
                unregisterReceiver(phoneStateReceiver);
            } catch (Exception ignored) {}
            phoneStateReceiver = null;
        }
        if (telephonyCallbackObj != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Api31TelephonyHelper.unregister(this, telephonyCallbackObj);
            } else {
                LegacyTelephonyHelper.unregister(this, telephonyCallbackObj);
            }
            telephonyCallbackObj = null;
        }
        if (workerThread != null) workerThread.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
