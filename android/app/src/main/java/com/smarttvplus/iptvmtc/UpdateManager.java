package com.smarttvplus.iptvmtc;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * التحديث الذاتي لـmyTv+ وتثبيت/تحديث Player+ من نفس رابط الإصدارات على GitHub.
 *
 * ملف versions.json بجانب ملفات APK في الإصدار v1.0.0:
 *   {"mytv":   {"versionCode":2,"versionName":"1.1.0","url":"…/SmartTVPlus.apk","notes":"…"},
 *    "player": {"versionCode":2,"versionName":"1.1.0","url":"…/PlayerPlus.apk","notes":"…"}}
 *
 * أندرويد لا يسمح بالتثبيت الصامت لتطبيق عادي: كل تثبيت يحتاج ضغطة "تثبيت" من المستخدم،
 * ومرة واحدة فقط يسمح لـmyTv+ بتثبيت التطبيقات من إعدادات التلفاز.
 */
public class UpdateManager {
    private static final String TAG = "UpdateManager";
    public static final String BASE = "https://github.com/almobarakamjd/iptvmtc/releases/download/v1.0.0/";
    public static final String VERSIONS_URL = BASE + "versions.json";
    public static final String PLAYER_PKG = "com.oqod.movie_player";
    static final String ACTION_INSTALL_RESULT = "com.smarttvplus.iptvmtc.INSTALL_RESULT";

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private boolean busy;
    /** تثبيت مؤجَّل حتى يرجع المستخدم من شاشة السماح بتثبيت التطبيقات */
    private Runnable pendingAfterPermission;

    public UpdateManager(Activity act) {
        this.act = act;
        this.prefs = act.getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    /** نسخة التطوير فقط: رابط بديل لملف الإصدارات لاختبار التحديث على المحاكي دون رفع شيء */
    private String versionsUrl() {
        boolean debuggable = (act.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        String override = act.getIntent() != null ? act.getIntent().getStringExtra("updates_url") : null;
        return debuggable && override != null && override.length() > 0 ? override : VERSIONS_URL;
    }

    // ------------------------------------------------------------------
    public void check(final boolean manual) {
        if (busy) return;
        busy = true;
        if (manual) toast("جارٍ التحقق من التحديثات…");
        new Thread(() -> {
            JSONObject v = null;
            String err = null;
            try {
                // منع النسخة المخزنة مؤقتاً — نريد آخر ملف فعلاً
                v = new JSONObject(new String(download(versionsUrl() + "?t=" + System.currentTimeMillis(), 200_000, null), "UTF-8"));
            } catch (Exception e) {
                err = e.getMessage();
                Log.w(TAG, "check failed", e);
            }
            final JSONObject versions = v;
            final String error = err;
            ui.post(() -> {
                busy = false;
                if (act.isFinishing()) return;
                if (versions == null) {
                    if (manual) toast("تعذر التحقق من التحديثات: " + error);
                    return;
                }
                decide(versions, manual);
            });
        }).start();
    }

    /** تثبيت المشغّل فوراً بطلب المستخدم (بند الشاشة الرئيسية) — بلا أسئلة تأجيل */
    public void installPlayerNow() {
        if (busy) return;
        busy = true;
        toast("جارٍ الاتصال بخادم الإصدارات…");
        new Thread(() -> {
            String url = null, err = null;
            try {
                JSONObject v = new JSONObject(new String(download(versionsUrl() + "?t=" + System.currentTimeMillis(), 200_000, null), "UTF-8"));
                JSONObject player = v.optJSONObject("player");
                url = player != null ? player.optString("url", "") : "";
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
                Log.w(TAG, "installPlayerNow", e);
            }
            final String u = url, error = err;
            ui.post(() -> {
                busy = false;
                if (act.isFinishing()) return;
                if (u == null || u.length() == 0) {
                    toast("تعذر الوصول لخادم الإصدارات: " + error);
                    return;
                }
                downloadAndInstall(u, "PlayerPlus.apk", "Player+");
            });
        }).start();
    }

    private void decide(JSONObject versions, boolean manual) {
        JSONObject player = versions.optJSONObject("player");
        JSONObject mytv = versions.optJSONObject("mytv");
        long playerInstalled = installedVersion(PLAYER_PKG);
        long myVersion = installedVersion(act.getPackageName());

        // ١) المشغّل غير مثبّت أصلاً — الأهم
        if (player != null && playerInstalled < 0 && player.optString("url").length() > 0) {
            if (manual || !skipped("player-missing", player.optLong("versionCode"))) {
                ask("تثبيت Player+",
                        "مشغّل Player+ غير مثبّت على هذا الجهاز.\nهو مشغّل myTv+ الخاص (إعادة البث، التسجيل، الترجمة العربية).\n\nتثبيته الآن؟",
                        "تثبيت", () -> downloadAndInstall(player.optString("url"), "PlayerPlus.apk", "Player+"),
                        () -> skip("player-missing", player.optLong("versionCode")));
                return;
            }
        }
        // ٢) تحديث المشغّل
        if (player != null && playerInstalled >= 0 && player.optLong("versionCode") > playerInstalled) {
            if (manual || !skipped("player", player.optLong("versionCode"))) {
                ask("تحديث Player+",
                        "يوجد إصدار جديد من Player+ (" + player.optString("versionName") + ").\n" + player.optString("notes"),
                        "تحديث", () -> downloadAndInstall(player.optString("url"), "PlayerPlus.apk", "Player+"),
                        () -> skip("player", player.optLong("versionCode")));
                return;
            }
        }
        // ٣) تحديث myTv+ نفسه (أخيراً — تثبيته يغلق التطبيق)
        if (mytv != null && mytv.optLong("versionCode") > myVersion) {
            if (manual || !skipped("mytv", mytv.optLong("versionCode"))) {
                ask("تحديث myTv+",
                        "يوجد إصدار جديد من myTv+ (" + mytv.optString("versionName") + ").\n" + mytv.optString("notes"),
                        "تحديث", () -> downloadAndInstall(mytv.optString("url"), "SmartTVPlus.apk", "myTv+"),
                        () -> skip("mytv", mytv.optLong("versionCode")));
                return;
            }
        }
        if (manual) toast("لديك آخر إصدار من myTv+ و Player+ ✓");
    }

    // ------------------------------------------------------------------
    private void downloadAndInstall(final String url, final String fileName, final String label) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            pendingAfterPermission = () -> downloadAndInstall(url, fileName, label);
            new AlertDialog.Builder(act)
                    .setTitle("خطوة لمرة واحدة")
                    .setMessage("اسمح لـ myTv+ بتثبيت التطبيقات من الشاشة التالية (فعّل الخيار)، ثم ارجع بزر الرجوع وسيكمل التثبيت تلقائياً.")
                    .setPositiveButton("فتح الإعدادات", (d, w) -> {
                        try {
                            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        } catch (Exception e) {
                            act.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                        }
                    })
                    .setNegativeButton("إلغاء", (d, w) -> pendingAfterPermission = null)
                    .show();
            return;
        }

        final TextView progress = new TextView(act);
        int pad = Math.round(24 * act.getResources().getDisplayMetrics().density);
        progress.setPadding(pad, pad, pad, pad);
        progress.setTextSize(20);
        progress.setText("جارٍ تنزيل " + label + "…");
        final AlertDialog dlg = new AlertDialog.Builder(act).setTitle("تنزيل " + label).setView(progress)
                .setCancelable(false).show();
        new Thread(() -> {
            try {
                File dir = new File(act.getCacheDir(), "updates");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File apk = new File(dir, fileName);
                byte[] data = download(url, 150_000_000, pct -> ui.post(() -> progress.setText("جارٍ تنزيل " + label + "… " + pct + "%")));
                try (FileOutputStream o = new FileOutputStream(apk)) { o.write(data); }
                ui.post(() -> progress.setText("جارٍ فتح شاشة التثبيت…"));
                install(apk);
                ui.post(dlg::dismiss);
            } catch (Exception e) {
                Log.w(TAG, "install failed", e);
                ui.post(() -> {
                    dlg.dismiss();
                    toast("تعذر التنزيل: " + e.getMessage());
                });
            }
        }).start();
    }

    /** يُستدعى من onResume — يكمل التثبيت بعد رجوع المستخدم من شاشة السماح */
    public void onResume() {
        Runnable r = pendingAfterPermission;
        if (r != null && (Build.VERSION.SDK_INT < 26 || act.getPackageManager().canRequestPackageInstalls())) {
            pendingAfterPermission = null;
            ui.postDelayed(r, 400);
        }
    }

    private void install(File apk) throws Exception {
        PackageInstaller installer = act.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("app", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent cb = new Intent(act, InstallResultReceiver.class).setAction(ACTION_INSTALL_RESULT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= 0x02000000; // FLAG_MUTABLE: النظام يضيف نتيجة التثبيت
            PendingIntent pi = PendingIntent.getBroadcast(act, sessionId, cb, flags);
            session.commit(pi.getIntentSender());
        }
    }

    // ------------------------------------------------------------------
    interface ProgressCb { void pct(int p); }

    /** تنزيل مع إعادة المحاولة واستكمال من حيث انقطع (Range) — شبكة التلفاز اللاسلكية قد تنقطع */
    static byte[] download(String url, int maxBytes, ProgressCb cb) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        long total = -1;
        Exception last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                if (fetchInto(url, bo, maxBytes, cb, total)) return bo.toByteArray();
            } catch (java.io.IOException e) {
                last = e;
                Log.w(TAG, "download attempt " + attempt + " stopped at " + bo.size() + " bytes: " + e.getMessage());
                Thread.sleep(1500L * (attempt + 1));
            }
        }
        throw last != null ? last : new Exception("تعذر التنزيل");
    }

    /** @return true عند اكتمال الملف */
    private static boolean fetchInto(String url, ByteArrayOutputStream bo, int maxBytes, ProgressCb cb, long knownTotal) throws Exception {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false); // GitHub يحوّل إلى نطاق آخر — نتبع يدوياً
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "myTvPlus-Updater");
            if (bo.size() > 0) c.setRequestProperty("Range", "bytes=" + bo.size() + "-");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                current = new URL(new URL(current), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            long total;
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range"); // bytes 100-999/1000
                total = cr != null && cr.contains("/") ? Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim()) : -1;
            } else if (code == 200) {
                bo.reset(); // السيرفر لا يدعم الاستكمال — نبدأ من الصفر
                total = c.getContentLengthLong();
            } else {
                throw new Exception("HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[65536];
                int n, lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > maxBytes) throw new Exception("الملف أكبر من المتوقع");
                    if (cb != null && total > 0) {
                        int pct = (int) (bo.size() * 100 / total);
                        if (pct != lastPct) { lastPct = pct; cb.pct(pct); }
                    }
                }
            }
            if (total > 0 && bo.size() < total) throw new java.io.IOException("انقطع التنزيل");
            return true;
        }
        throw new Exception("تحويلات كثيرة");
    }

    private long installedVersion(String pkg) {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    public String versionName() {
        try { return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    /** "لاحقاً" تؤجل نفس الإصدار يوماً كاملاً */
    private boolean skipped(String key, long code) {
        return System.currentTimeMillis() - prefs.getLong("skip:" + key + ":" + code, 0) < 24 * 3600_000L;
    }

    private void skip(String key, long code) {
        prefs.edit().putLong("skip:" + key + ":" + code, System.currentTimeMillis()).apply();
    }

    private void ask(String title, String msg, String yes, Runnable onYes, Runnable onLater) {
        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(yes, (x, w) -> onYes.run())
                .setNegativeButton("لاحقاً", (x, w) -> onLater.run())
                .create();
        d.show();
        if (d.getButton(AlertDialog.BUTTON_POSITIVE) != null) d.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_LONG).show();
    }
}
