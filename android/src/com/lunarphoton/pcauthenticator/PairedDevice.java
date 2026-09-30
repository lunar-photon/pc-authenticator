package com.lunarphoton.pcauthenticator;

import org.json.JSONObject;

public class PairedDevice {
    public String deviceId;
    public String hostname;
    public String ip;
    public int port;
    public String user;
    public String authToken;
    public String secretKey;
    public boolean isActive;

    public PairedDevice(String hostname, String ip, int port, String user, boolean isActive) {
        this(null, hostname, ip, port, user, null, null, isActive);
    }

    public PairedDevice(String deviceId, String hostname, String ip, int port, String user, String authToken, String secretKey, boolean isActive) {
        this.deviceId = deviceId;
        this.hostname = hostname != null ? hostname : "Unknown PC";
        this.ip = ip;
        this.port = port > 0 ? port : 1760;
        this.user = user != null ? user : "user";
        this.authToken = authToken;
        this.secretKey = secretKey;
        this.isActive = isActive;
    }

    public boolean isPaired() {
        return authToken != null && !authToken.isEmpty() && secretKey != null && !secretKey.isEmpty();
    }

    public String getBaseUrl() {
        if (ip == null || ip.isEmpty()) return "http://127.0.0.1:" + port;
        String clean = ip.trim();
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
            return clean;
        }
        return "http://" + clean + ":" + port;
    }

    public JSONObject toJsonObject() {
        JSONObject obj = new JSONObject();
        try {
            if (deviceId != null) obj.put("deviceId", deviceId);
            obj.put("hostname", hostname);
            obj.put("ip", ip);
            obj.put("port", port);
            obj.put("user", user);
            if (authToken != null) obj.put("authToken", authToken);
            if (secretKey != null) obj.put("secretKey", secretKey);
            obj.put("isActive", isActive);
        } catch (Exception ignored) {}
        return obj;
    }

    public static PairedDevice fromJsonObject(JSONObject obj) {
        if (obj == null) return null;
        String deviceId = obj.optString("deviceId", null);
        String hostname = obj.optString("hostname", "Unknown PC");
        String ip = obj.optString("ip", "127.0.0.1");
        int port = obj.optInt("port", 1760);
        String user = obj.optString("user", "user");
        String authToken = obj.optString("authToken", null);
        String secretKey = obj.optString("secretKey", null);
        boolean isActive = obj.optBoolean("isActive", false);
        return new PairedDevice(deviceId, hostname, ip, port, user, authToken, secretKey, isActive);
    }
}
