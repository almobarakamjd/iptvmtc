package com.smarttvplus.iptvmtc;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** خادم HTTP محلي مصغّر (بلا أي مكتبة خارجية) يستمع على الشبكة المحلية فقط، ليتحكم بالتلفاز
    عن بُعد من جهاز آخر بنفس الشبكة (لابتوب أبو فيصل) أثناء التطوير والاختبار: الذهاب للرئيسية/
    رجوع، مفاتيح الوسائط، فتح تطبيق أو رابط قناة — دون حاجة لـADB أو كابل USB، فقط بتفعيل خدمة
    الإتاحة يدوياً من إعدادات أندرويد مرة واحدة (انظر RemoteKeyAccessibilityService). محمي برمز
    بسيط (TOKEN) في رابط الطلب لمنع أي جهاز آخر بنفس الشبكة من التحكم بالتلفاز دون قصد — حماية
    كافية لشبكة منزلية موثوقة فقط، وليست تشفيراً حقيقياً. */
class RemoteControlServer {

    private static final String TAG = "RemoteControlServer";
    private static final int PORT = 8642;
    private static final String TOKEN = "abufaisal2026";

    private final RemoteKeyAccessibilityService service;
    private ServerSocket serverSocket;
    private volatile boolean running;

    RemoteControlServer(RemoteKeyAccessibilityService service) {
        this.service = service;
    }

    void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }).start();
    }

    void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(PORT);
            while (running) {
                final Socket socket = serverSocket.accept();
                new Thread(new Runnable() {
                    @Override public void run() { handle(socket); }
                }).start();
            }
        } catch (IOException e) {
            if (running) Log.w(TAG, "server stopped: " + e.getMessage());
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) { /* تجاهل بقية الرؤوس */ }

            String pathAndQuery = "";
            if (requestLine != null) {
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) pathAndQuery = parts[1];
            }

            String body = route(pathAndQuery);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (IOException ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private String route(String pathAndQuery) {
        Uri uri = Uri.parse("http://x" + pathAndQuery);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!TOKEN.equals(uri.getQueryParameter("token"))) {
            return "{\"error\":\"unauthorized\"}";
        }
        if ("/status".equals(path)) return status();
        if ("/action/home".equals(path)) return globalAction(RemoteKeyAccessibilityService.GLOBAL_ACTION_HOME);
        if ("/action/back".equals(path)) return globalAction(RemoteKeyAccessibilityService.GLOBAL_ACTION_BACK);
        if ("/action/recents".equals(path)) return globalAction(RemoteKeyAccessibilityService.GLOBAL_ACTION_RECENTS);
        if ("/media/playpause".equals(path)) return mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        if ("/media/next".equals(path)) return mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
        if ("/media/previous".equals(path)) return mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        if ("/media/volup".equals(path)) return mediaKey(KeyEvent.KEYCODE_VOLUME_UP);
        if ("/media/voldown".equals(path)) return mediaKey(KeyEvent.KEYCODE_VOLUME_DOWN);
        if ("/media/mute".equals(path)) return mediaKey(KeyEvent.KEYCODE_VOLUME_MUTE);
        if ("/launch".equals(path)) return launch(uri.getQueryParameter("pkg"));
        if ("/open".equals(path)) return openUrl(uri.getQueryParameter("url"));
        return "{\"error\":\"not_found\"}";
    }

    private String status() {
        return "{\"ok\":true,\"foreground\":\"" + service.getForegroundPackage() + "\"}";
    }

    private String globalAction(int action) {
        boolean ok = service.performGlobalAction(action);
        return "{\"ok\":" + ok + "}";
    }

    private String mediaKey(int keyCode) {
        AudioManager am = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "{\"error\":\"no_audio_manager\"}";
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        return "{\"ok\":true}";
    }

    private String launch(String pkg) {
        if (pkg == null || pkg.length() == 0) return "{\"error\":\"missing_pkg\"}";
        try {
            Intent intent = service.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) return "{\"error\":\"app_not_found\"}";
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
            return "{\"ok\":true}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String openUrl(String url) {
        if (url == null || url.length() == 0) return "{\"error\":\"missing_url\"}";
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
            return "{\"ok\":true}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
