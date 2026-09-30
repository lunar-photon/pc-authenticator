package com.lunarphoton.pcauthenticator;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class NetworkUtils {

    public static void applyTunnelHeaders(HttpURLConnection conn) {
        if (conn != null) {
            try {
                conn.setRequestProperty("ngrok-skip-browser-warning", "1");
            } catch (Exception ignored) {}
        }
    }

    public static String httpGet(String urlStr, int timeoutMs) {
        return httpGetWithAuth(urlStr, null, timeoutMs);
    }

    public static String httpGetWithAuth(String urlStr, String authToken, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            applyTunnelHeaders(conn);
            if (authToken != null && !authToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + authToken);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readStream(conn.getInputStream());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    public static boolean httpPost(String urlStr, String jsonBody, int timeoutMs) {
        return httpPostWithAuth(urlStr, null, null, null, null, timeoutMs);
    }

    public static String httpPostJson(String urlStr, String jsonBody, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            applyTunnelHeaders(conn);

            if (jsonBody != null && !jsonBody.isEmpty()) {
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes("UTF-8"));
                }
            } else {
                conn.setFixedLengthStreamingMode(0);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readStream(conn.getInputStream());
            } else if (conn.getErrorStream() != null) {
                return readStream(conn.getErrorStream());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    public static boolean httpPostWithAuth(String urlStr, String authToken, String secretKey, String sessionId, String action, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            applyTunnelHeaders(conn);

            if (authToken != null && !authToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + authToken);
            }
            if (secretKey != null && !secretKey.isEmpty()) {
                long ts = System.currentTimeMillis() / 1000L;
                String sid = (sessionId != null) ? sessionId : "";
                String act = (action != null) ? action : "approve";
                String msg = sid + ":" + ts + ":" + act;
                String sig = CryptoUtils.hmacSha256(secretKey, msg);
                conn.setRequestProperty("X-Auth-Timestamp", String.valueOf(ts));
                conn.setRequestProperty("X-Auth-Signature", sig);
                if (sessionId != null && !sessionId.isEmpty()) {
                    conn.setRequestProperty("X-Session-ID", sessionId);
                }
            }

            conn.setFixedLengthStreamingMode(0);

            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }
}
