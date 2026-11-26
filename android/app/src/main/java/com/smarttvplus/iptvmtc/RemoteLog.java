package com.smarttvplus.iptvmtc;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * سجلّ تشخيص عن بُعد: التلفاز لا يصله adb، فنرفع أحداث التطبيق إلى خادمنا (server/actors-db/log.php)
 * ونقرؤها من اللابتوب (طلب أبي فيصل 2026-11-26).
 *
 * الأسطر تُجمع في الذاكرة وتُرفع دفعةً بعد ثانيتين من أول سطر، وفوراً عند {@link #flush()} (يُنادى في
 * onPause لأن ما يهمّنا غالباً هو ما سبق الخروج من التطبيق مباشرة). الفشل صامت ولا يؤثر في التطبيق.
 */
final class RemoteLog {

    private static final String URL_LOG = "https://amjd.law/apps/ipv/log.php";
    private static final String TOKEN = "mytvplus_log_Kd4vR8sJ2xQm";
    private static final String TAG = "RemoteLog";
    private static final int MAX_PENDING = 600;

    private static final List<JSONObject> pending = new ArrayList<>();
    /** آخر الأسطر محلياً (لا تُمسح عند الرفع): يقرؤها «المساعد الذكي» {@link AiDoctor} */
    private static final int MAX_RECENT = 500;
    private static final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static volatile String device = "?";
    private static volatile String ver = "?";
    private static boolean scheduled;

    private RemoteLog() {}

    static void init(Context c) {
        try {
            String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
            device = Build.MODEL + "-" + (id != null && id.length() > 4 ? id.substring(0, 4) : "0");
            ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        log("app", "start " + Build.MANUFACTURER + " " + Build.MODEL + " device=" + Build.DEVICE
                + " board=" + Build.BOARD + " hw=" + Build.HARDWARE + " sdk=" + Build.VERSION.SDK_INT
                + " rel=" + Build.VERSION.RELEASE + " fp=" + Build.FINGERPRINT);
    }

    static void log(String tag, String msg) {
        Log.d(TAG, tag + ": " + msg);
        if (!"ai".equals(tag)) {
            String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                    + " " + tag + ": " + (msg.length() > 1500 ? msg.substring(0, 1500) + "…" : msg);
            synchronized (recent) {
                if (recent.size() >= MAX_RECENT) recent.removeFirst();
                recent.addLast(line);
            }
        }
        AiDoctor.observe(tag, msg);
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("tag", tag);
            o.put("msg", msg);
            synchronized (pending) {
                if (pending.size() >= MAX_PENDING) pending.remove(0);
                pending.add(o);
                if (scheduled) return;
                scheduled = true;
            }
            io.execute(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                send();
            });
        } catch (Exception ignored) {}
    }

    /** آخر n سطراً من السجلّ المحلي (الأقدم أولاً)، مصفّاة بنص إن أُعطي */
    static String recent(int n, String contains) {
        List<String> out = new ArrayList<>();
        synchronized (recent) {
            for (String l : recent) if (contains == null || contains.length() == 0 || l.contains(contains)) out.add(l);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, out.size() - n); i < out.size(); i++) sb.append(out.get(i)).append('\n');
        return sb.length() > 0 ? sb.toString() : "(لا أسطر)";
    }

    /** رفع فوري لما تجمّع (عند مغادرة الواجهة) */
    static void flush() {
        io.execute(RemoteLog::send);
    }

    /** آخر أسطر logcat لعمليتنا نفسها (أندرويد لا يسمح بغيرها): فيها رسائل TvView وTvInputManager */
    static void dumpOwnLogcat(final String why) {
        io.execute(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "400", "-v", "time"});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                int n = 0;
                while ((line = r.readLine()) != null) {
                    if (line.contains(TAG) || line.contains("WebConsole")) continue;
                    String l = line.toLowerCase();
                    if (!(l.contains("tv") || l.contains("hdmi") || l.contains("input") || l.contains("audio")
                            || l.contains(" e ") || l.contains("exception"))) continue;
                    sb.append(line).append('\n');
                    if (++n >= 80) break;
                }
                r.close();
                if (sb.length() > 0) log("logcat", why + "\n" + sb);
            } catch (Exception e) {
                log("logcat", why + " failed: " + e);
            }
        });
    }

    private static void send() {
        JSONArray lines = new JSONArray();
        synchronized (pending) {
            scheduled = false;
            if (pending.isEmpty()) return;
            for (JSONObject o : pending) lines.put(o);
            pending.clear();
        }
        try {
            JSONObject body = new JSONObject();
            body.put("token", TOKEN);
            body.put("device", device);
            body.put("ver", ver);
            body.put("lines", lines);
            HttpURLConnection c = (HttpURLConnection) new URL(URL_LOG).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            c.getResponseCode();
            c.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "send failed: " + e);
        }
    }
}
