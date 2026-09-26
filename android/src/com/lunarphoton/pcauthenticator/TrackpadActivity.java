package com.lunarphoton.pcauthenticator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TrackpadActivity extends Activity implements SensorEventListener {
    private PairedDevice activeDevice;
    private DatagramSocket udpSocket;
    private InetAddress pcAddress;
    private final int UDP_PORT = 1762;

    private float sensitivity = 1.2f;
    private TextView tvSensitivity;
    private Vibrator vibrator;

    private final BlockingQueue<JSONObject> sendQueue = new LinkedBlockingQueue<>();
    private Thread workerThread;
    private volatile boolean isRunning = true;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Single touch tracking
    private float lastX, lastY;
    private float startX, startY;
    private long downTime;
    private boolean hasMoved = false;
    private boolean isDragging = false;
    private long lastUpTime = 0;
    private float lastUpX = 0, lastUpY = 0;

    // Two finger tracking
    private float twoFingerLastY, twoFingerLastX;
    private long twoFingerDownTime;
    private boolean twoFingerMoved = false;

    // Laser Pointer & Presentation Remote
    private SensorManager sensorManager;
    private Sensor gyroSensor;
    private boolean isGyroActive = false;
    private boolean isGyroLocked = false;
    private float filterX = 0f, filterY = 0f;
    private float accumX = 0f, accumY = 0f;
    private long suppressGyroUntil = 0;

    // Mode UI elements
    private Button btnModeTrackpad;
    private Button btnModePointer;
    private View touchpadSurface;
    private LinearLayout layoutPointerSurface;
    private View btnLaserAim;
    private TextView tvLaserStatus;
    private Button btnLockGyro;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_trackpad);

        activeDevice = DeviceManager.getActiveDevice(this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }

        TextView tvTarget = findViewById(R.id.tv_trackpad_target);
        if (activeDevice != null && activeDevice.hostname != null) {
            tvTarget.setText("Target: " + activeDevice.hostname + " (" + activeDevice.ip + ")");
        } else {
            tvTarget.setText("Target: No PC Connected");
        }

        tvSensitivity = findViewById(R.id.tv_sensitivity);
        SharedPreferences prefs = getSharedPreferences("trackpad_prefs", MODE_PRIVATE);
        sensitivity = prefs.getFloat("sensitivity", 1.2f);
        updateSensitivityDisplay();

        findViewById(R.id.btn_sens_down).setOnClickListener(v -> {
            sensitivity = Math.max(0.4f, sensitivity - 0.2f);
            prefs.edit().putFloat("sensitivity", sensitivity).apply();
            updateSensitivityDisplay();
        });

        findViewById(R.id.btn_sens_up).setOnClickListener(v -> {
            sensitivity = Math.min(3.0f, sensitivity + 0.2f);
            prefs.edit().putFloat("sensitivity", sensitivity).apply();
            updateSensitivityDisplay();
        });

        findViewById(R.id.btn_trackpad_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_trackpad_keyboard).setOnClickListener(v -> showKeyboardDialog());

        setupTouchpad();
        setupModeSwitcher();
        setupLaserPointer();
        setupClickButtons();
        startNetworkSender();

        // Check if launched directly in pointer mode
        String mode = getIntent().getStringExtra("mode");
        if ("pointer".equalsIgnoreCase(mode)) {
            switchToMode(false);
        }
    }

    private void updateSensitivityDisplay() {
        tvSensitivity.setText(String.format("Sensitivity: %.1fx", sensitivity));
    }

    private void setupModeSwitcher() {
        btnModeTrackpad = findViewById(R.id.btn_mode_trackpad);
        btnModePointer = findViewById(R.id.btn_mode_pointer);
        touchpadSurface = findViewById(R.id.touchpad_surface);
        layoutPointerSurface = findViewById(R.id.layout_pointer_surface);

        btnModeTrackpad.setOnClickListener(v -> switchToMode(true));
        btnModePointer.setOnClickListener(v -> switchToMode(false));
    }

    private void switchToMode(boolean isTrackpad) {
        if (isTrackpad) {
            touchpadSurface.setVisibility(View.VISIBLE);
            layoutPointerSurface.setVisibility(View.GONE);
            btnModeTrackpad.setTextColor(Color.parseColor("#06b6d4"));
            btnModePointer.setTextColor(Color.parseColor("#64748b"));

            if (isGyroActive || isGyroLocked) {
                isGyroLocked = false;
                enableGyro(false);
                sendLaserState(false);
                updateLaserStatusUi(false);
            }
        } else {
            touchpadSurface.setVisibility(View.GONE);
            layoutPointerSurface.setVisibility(View.VISIBLE);
            btnModePointer.setTextColor(Color.parseColor("#06b6d4"));
            btnModeTrackpad.setTextColor(Color.parseColor("#64748b"));
        }
    }

    private void setupLaserPointer() {
        btnLaserAim = findViewById(R.id.btn_laser_aim);
        tvLaserStatus = findViewById(R.id.tv_laser_status);
        btnLockGyro = findViewById(R.id.btn_lock_gyro);

        Button btnSlidePrev = findViewById(R.id.btn_slide_prev);
        Button btnSlideF5 = findViewById(R.id.btn_slide_f5);
        Button btnSlideNext = findViewById(R.id.btn_slide_next);

        btnLaserAim.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    if (!isGyroActive) {
                        enableGyro(true);
                    }
                    sendLaserState(true);
                    updateLaserStatusUi(true);
                    vibrate(25);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!isGyroLocked) {
                        enableGyro(false);
                        sendLaserState(false);
                        updateLaserStatusUi(false);
                    }
                    return true;
            }
            return false;
        });

        btnLockGyro.setOnClickListener(v -> {
            isGyroLocked = !isGyroLocked;
            if (isGyroLocked) {
                btnLockGyro.setText("🔓 Pointer Locked ON (Tap to Unlock)");
                btnLockGyro.setTextColor(Color.parseColor("#10b981"));
                enableGyro(true);
                sendLaserState(true);
                updateLaserStatusUi(true);
                vibrate(35);
            } else {
                btnLockGyro.setText("🔒 Lock Pointer ON");
                btnLockGyro.setTextColor(Color.parseColor("#94a3b8"));
                enableGyro(false);
                sendLaserState(false);
                updateLaserStatusUi(false);
                vibrate(20);
            }
        });

        btnSlidePrev.setOnClickListener(v -> {
            suppressGyroUntil = System.currentTimeMillis() + 180;
            sendKeyAction("prev");
            vibrate(20);
        });

        btnSlideF5.setOnClickListener(v -> {
            suppressGyroUntil = System.currentTimeMillis() + 180;
            sendKeyAction("f5");
            vibrate(30);
        });

        btnSlideNext.setOnClickListener(v -> {
            suppressGyroUntil = System.currentTimeMillis() + 180;
            sendKeyAction("next");
            vibrate(20);
        });
    }

    private void updateLaserStatusUi(boolean active) {
        if (btnLaserAim != null && tvLaserStatus != null) {
            if (active) {
                btnLaserAim.setBackgroundResource(R.drawable.btn_laser_active);
                tvLaserStatus.setText(isGyroLocked ? "🎯 POINTER LOCKED ON" : "🎯 POINTING ACTIVE");
                tvLaserStatus.setTextColor(Color.parseColor("#ef4444"));
            } else {
                btnLaserAim.setBackgroundResource(R.drawable.btn_laser_idle);
                tvLaserStatus.setText("🔴 HOLD TO POINT");
                tvLaserStatus.setTextColor(Color.parseColor("#ef4444"));
            }
        }
    }

    private void enableGyro(boolean enable) {
        if (sensorManager == null || gyroSensor == null) {
            if (enable) {
                Toast.makeText(this, "Gyroscope sensor not available on this device", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (enable && !isGyroActive) {
            filterX = 0f;
            filterY = 0f;
            accumX = 0f;
            accumY = 0f;
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
            isGyroActive = true;
        } else if (!enable && isGyroActive) {
            sensorManager.unregisterListener(this);
            isGyroActive = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor.getType() != Sensor.TYPE_GYROSCOPE) {
            return;
        }
        if (System.currentTimeMillis() < suppressGyroUntil) {
            return;
        }

        float[] values = event.values;
        // KDE Connect formula:
        // Yaw (axis 2, Z-axis) moves horizontal cursor X
        // Pitch (axis 0, X-axis) moves vertical cursor Y
        float scale = 65.0f * (sensitivity / 1.2f);
        float rawX = -values[2] * scale;
        float rawY = -values[0] * scale;

        // Deadband filter to prevent natural hand trembling when holding still
        if (Math.abs(rawX) < 0.25f) rawX = 0f;
        if (Math.abs(rawY) < 0.25f) rawY = 0f;

        // Low-pass exponential moving average filter for buttery smooth tracking
        filterX = 0.75f * rawX + 0.25f * filterX;
        filterY = 0.75f * rawY + 0.25f * filterY;

        accumX += filterX;
        accumY += filterY;

        int dx = Math.round(accumX);
        int dy = Math.round(accumY);

        if (dx != 0 || dy != 0) {
            accumX -= dx;
            accumY -= dy;
            sendPointerMove(dx, dy);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }

    private void setupTouchpad() {
        View surface = findViewById(R.id.touchpad_surface);
        surface.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            int pointerCount = event.getPointerCount();

            if (pointerCount == 1) {
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        startY = event.getY();
                        lastX = startX;
                        lastY = startY;
                        downTime = System.currentTimeMillis();
                        hasMoved = false;

                        // Double tap detection for drag
                        if (downTime - lastUpTime < 250 && Math.hypot(startX - lastUpX, startY - lastUpY) < 50) {
                            isDragging = true;
                            sendMouseAction("down", "left");
                            vibrate(15);
                        }
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float curX = event.getX();
                        float curY = event.getY();
                        float dx = (curX - lastX) * sensitivity;
                        float dy = (curY - lastY) * sensitivity;

                        if (Math.hypot(curX - startX, curY - startY) > 10) {
                            hasMoved = true;
                        }

                        if (Math.abs(dx) >= 0.5f || Math.abs(dy) >= 0.5f) {
                            sendMouseMove(Math.round(dx), Math.round(dy));
                            lastX = curX;
                            lastY = curY;
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        long upTime = System.currentTimeMillis();
                        if (isDragging) {
                            isDragging = false;
                            sendMouseAction("up", "left");
                            vibrate(15);
                        } else if (!hasMoved && (upTime - downTime < 250)) {
                            // Single tap = Left Click
                            sendMouseAction("click", "left");
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            vibrate(20);
                        }
                        lastUpTime = upTime;
                        lastUpX = event.getX();
                        lastUpY = event.getY();
                        break;
                }
            } else if (pointerCount >= 2) {
                switch (action) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                        twoFingerDownTime = System.currentTimeMillis();
                        twoFingerLastY = (event.getY(0) + event.getY(1)) / 2f;
                        twoFingerLastX = (event.getX(0) + event.getX(1)) / 2f;
                        twoFingerMoved = false;
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float midY = (event.getY(0) + event.getY(1)) / 2f;
                        float midX = (event.getX(0) + event.getX(1)) / 2f;
                        float diffY = midY - twoFingerLastY;
                        float diffX = midX - twoFingerLastX;

                        if (Math.abs(diffY) > 12 || Math.abs(diffX) > 12) {
                            twoFingerMoved = true;
                            int scrollY = 0;
                            if (Math.abs(diffY) > 12) {
                                scrollY = (diffY > 0) ? 1 : -1;
                            }
                            int scrollX = 0;
                            if (Math.abs(diffX) > 15) {
                                scrollX = (diffX > 0) ? -1 : 1;
                            }
                            sendMouseScroll(scrollY, scrollX);
                            twoFingerLastY = midY;
                            twoFingerLastX = midX;
                        }
                        break;

                    case MotionEvent.ACTION_POINTER_UP:
                        long pUpTime = System.currentTimeMillis();
                        if (!twoFingerMoved && (pUpTime - twoFingerDownTime < 300)) {
                            // Two-finger tap = Right Click
                            sendMouseAction("click", "right");
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            vibrate(35);
                        }
                        break;
                }
            }
            return true;
        });
    }

    private void setupClickButtons() {
        Button btnLeft = findViewById(R.id.btn_click_left);
        Button btnMiddle = findViewById(R.id.btn_click_middle);
        Button btnRight = findViewById(R.id.btn_click_right);

        btnLeft.setOnTouchListener((v, event) -> {
            suppressGyroUntil = System.currentTimeMillis() + 180;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                sendMouseAction("down", "left");
                vibrate(20);
                v.setPressed(true);
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                sendMouseAction("up", "left");
                v.setPressed(false);
            }
            return true;
        });

        btnRight.setOnClickListener(v -> {
            suppressGyroUntil = System.currentTimeMillis() + 180;
            sendMouseAction("click", "right");
            vibrate(30);
        });

        btnMiddle.setOnClickListener(v -> {
            suppressGyroUntil = System.currentTimeMillis() + 180;
            sendMouseAction("click", "middle");
            vibrate(20);
        });
    }

    private void showKeyboardDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⌨️ Type or Send to PC");
        final EditText input = new EditText(this);
        input.setHint("Type text or paste URL...");
        input.setPadding(40, 30, 40, 30);
        builder.setView(input);

        builder.setPositiveButton("Send", (dialog, which) -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;

            new Thread(() -> {
                try {
                    boolean isUrl = text.startsWith("http://") || text.startsWith("https://");
                    String endpoint = isUrl ? "/api/open_url" : "/api/clipboard";
                    String urlStr = activeDevice.getBaseUrl() + endpoint;
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    if (activeDevice.isPaired()) {
                        conn.setRequestProperty("Authorization", "Bearer " + activeDevice.authToken);
                    }
                    conn.setDoOutput(true);
                    JSONObject body = new JSONObject();
                    if (isUrl) {
                        body.put("url", text);
                    } else {
                        body.put("text", text);
                    }
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.toString().getBytes("UTF-8"));
                    }
                    int code = conn.getResponseCode();
                    mainHandler.post(() -> {
                        if (code == 200) {
                            Toast.makeText(this, isUrl ? "🌐 Webpage opened on PC!" : "📋 Text copied to PC clipboard!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Failed to send (HTTP " + code + ")", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void sendMouseMove(int dx, int dy) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "move");
            obj.put("dx", dx);
            obj.put("dy", dy);
            sendQueue.offer(obj);
        } catch (Exception ignored) {}
    }

    private void sendPointerMove(int dx, int dy) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "pointer");
            obj.put("dx", dx);
            obj.put("dy", dy);
            obj.put("laser", true);
            sendQueue.offer(obj);
        } catch (Exception ignored) {}
    }

    private void sendLaserState(boolean active) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "laser_state");
            obj.put("laser", active);
            sendQueue.offer(obj);
        } catch (Exception ignored) {}
    }

    private void sendKeyAction(String key) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "key");
            obj.put("key", key);
            sendQueue.offer(obj);
        } catch (Exception ignored) {}
    }

    private void sendMouseAction(String type, String button) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", type);
            obj.put("button", button);
            sendQueue.offer(obj);
        } catch (Exception ignored) {}
    }

    private void sendMouseScroll(int dy, int dx) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "scroll");
            obj.put("dy", dy);
            obj.put("dx", dx);
            sendQueue.offer(obj);
        } catch (Exception ignored) {}
    }

    private void startNetworkSender() {
        workerThread = new Thread(() -> {
            try {
                udpSocket = new DatagramSocket();
                udpSocket.setSoTimeout(1000);
                if (activeDevice != null && activeDevice.ip != null) {
                    pcAddress = InetAddress.getByName(activeDevice.ip);
                }
            } catch (Exception ignored) {}

            while (isRunning) {
                try {
                    JSONObject packet = sendQueue.take();
                    byte[] bytes = packet.toString().getBytes("UTF-8");

                    boolean sentViaUdp = false;
                    if (udpSocket != null && pcAddress != null) {
                        try {
                            DatagramPacket dp = new DatagramPacket(bytes, bytes.length, pcAddress, UDP_PORT);
                            udpSocket.send(dp);
                            sentViaUdp = true;
                        } catch (Exception e) {
                            sentViaUdp = false;
                        }
                    }

                    // HTTP fallback if UDP could not be used
                    if (!sentViaUdp && activeDevice != null) {
                        try {
                            String urlStr = activeDevice.getBaseUrl() + "/api/mouse";
                            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                            conn.setRequestMethod("POST");
                            conn.setRequestProperty("Content-Type", "application/json");
                            if (activeDevice.isPaired()) {
                                conn.setRequestProperty("Authorization", "Bearer " + activeDevice.authToken);
                            }
                            conn.setDoOutput(true);
                            conn.setConnectTimeout(1500);
                            conn.setReadTimeout(1500);
                            try (OutputStream os = conn.getOutputStream()) {
                                os.write(bytes);
                            }
                            conn.getResponseCode();
                        } catch (Exception ignored) {}
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        });
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void vibrate(long ms) {
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                vibrator.vibrate(ms);
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        if (isGyroActive) {
            enableGyro(false);
            sendLaserState(false);
            updateLaserStatusUi(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        isRunning = false;
        enableGyro(false);
        sendLaserState(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
        if (udpSocket != null) {
            try {
                udpSocket.close();
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
