package com.smarttvplus.iptvmtc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class MainActivity extends Activity {

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ensureStoragePermission();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);               // localStorage للاشتراكات
        s.setMediaPlaybackRequiresUserGesture(false); // تشغيل البث دون لمس
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); // روابط http
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        WebView.setWebContentsDebuggingEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d("WebConsole", cm.message() + " -- " + cm.sourceId() + ":" + cm.lineNumber());
                return true;
            }
        });
        web.setBackgroundColor(0xFF000000);
        web.addJavascriptInterface(new AndroidBridge(), "AndroidExit");
        web.addJavascriptInterface(new OpenBridge(), "AndroidOpen");
        web.addJavascriptInterface(new StorageBridge(), "AndroidStorage");
        web.addJavascriptInterface(new SystemBridge(), "AndroidSystem");

        startDiagnosticPingServer(); // تشخيص مؤقت: نتأكد هل المشكلة خاصة بخدمة الإتاحة أم عامة بالشبكة/التلفاز

        setContentView(web);
        hideSystemUi();
        web.requestFocus();
        web.loadUrl("file:///android_asset/index.html");
    }

    /* إذن تخزين خارجي — لازم لحفظ رمز النسخة الاحتياطية بملف يبقى حتى بعد حذف/إعادة تثبيت
       التطبيق (خارج مجلده الخاص الذي يُمسح تماماً عند الحذف) */
    private void ensureStoragePermission() {
        // قبل أندرويد 6 (API23) كل الأذونات تُمنح وقت التثبيت تلقائياً — لا حاجة لطلبها هنا
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, 1001);
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /* تشخيص مؤقت (يُحذف لاحقاً): خادم HTTP مصغّر جداً على منفذ 8643 يعمل من نشاط التطبيق نفسه
       (بخلاف RemoteControlServer الذي يعمل من خدمة الإتاحة على منفذ 8642) — إن نجح الوصول لهذا
       بينما فشل الآخر، فالمشكلة خاصة بسياق خدمة الإتاحة تحديداً. إن فشل هو أيضاً، فالمشكلة عامة
       (جدار حماية بالراوتر أو بنظام التلفاز نفسه) وليست بكود RemoteControlServer. */
    private void startDiagnosticPingServer() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.net.ServerSocket ss = new java.net.ServerSocket(8643);
                    while (true) {
                        java.net.Socket sock = ss.accept();
                        String body = "pong-from-activity";
                        byte[] b = body.getBytes("UTF-8");
                        java.io.OutputStream out = sock.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                                + b.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                        out.write(b);
                        out.flush();
                        sock.close();
                    }
                } catch (Exception e) { /* تجاهل — هذا كود تشخيص مؤقت فقط */ }
            }
        }).start();
    }

    /** جسر بسيط يسمح لصفحة الويب بإغلاق التطبيق فعلياً (بعد تأكيد المستخدم) */
    private class AndroidBridge {
        @JavascriptInterface
        public void exit() {
            runOnUiThread(new Runnable() {
                @Override public void run() { finishAndRemoveTask(); }
            });
        }
    }

    /** جسر فتح روابط/فيديو خارجياً: التشغيل الفعلي يتم بمشغل مثبَّت أصلاً على الجهاز
        (VLC أو MX Player عادة موجودان على أي تلفزيون/بوكس) بدل تضمين محرك فيديو داخل التطبيق —
        أبسط وأثبت من أي محرك مُضمَّن على أجهزة برام محدودة (1GB) */
    private class OpenBridge {
        @JavascriptInterface
        public void url(final String u) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }
                    catch (ActivityNotFoundException e) { /* لا يوجد تطبيق يفتح الرابط */ }
                }
            });
        }

        /* pkg: اسم حزمة المشغّل المفضل (مثلاً org.videolan.vlc) أو نص فارغ = اسأل دائماً (نافذة اختيار أندرويد)
           title: اسم الفيلم/الحلقة — بدونه بعض المشغّلات (منها مشغّل التلفاز الافتراضي) تعرض رقم
           القناة/الفيلم المستخرج من الرابط نفسه بدل اسمه الحقيقي. نرسله بأكثر من مفتاح (title و
           android.intent.extra.TITLE) لتغطية أوسع عدد من المشغّلات المختلفة. */
        @JavascriptInterface
        public void playVideo(final String u, final String pkg, final String title) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(u), "video/*");
                    if (pkg != null && pkg.length() > 0) intent.setPackage(pkg);
                    addTitleExtras(intent, title);
                    try {
                        startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        // المشغّل المفضل غير مثبَّت أو رفض النوع — نرجع لنافذة الاختيار العادية
                        try {
                            Intent generic = new Intent(Intent.ACTION_VIEW);
                            generic.setDataAndType(Uri.parse(u), "video/*");
                            addTitleExtras(generic, title);
                            startActivity(generic);
                        } catch (ActivityNotFoundException e2) {
                            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }
                            catch (ActivityNotFoundException e3) { /* لا يوجد مشغل مثبَّت إطلاقاً */ }
                        }
                    }
                }
            });
        }

        /* android.intent.extra.TITLE فقط — هو المفتاح الموثّق والمعروف الذي تفهمه VLC وMX Player
           ومشغّل أندرويد الافتراضي لعرض اسم الفيديو. تجنّبنا إضافة مفتاح "title" العام إضافياً
           لأنه غير موثّق رسمياً وقد يتعارض مع معالجة داخلية مختلفة لدى بعض المشغّلات. */
        private void addTitleExtras(Intent intent, String title) {
            if (title == null || title.length() == 0) return;
            try { intent.putExtra(Intent.EXTRA_TITLE, title); } catch (Exception ignored) {}
        }

        /* يكتب قائمة تشغيل M3U مؤقّتة (كل قنوات نفس التصنيف/القائمة المخصّصة) ويفتحها بمشغّل خارجي —
           هذا يخلي أزرار "التالي/السابق" بالريموت تنقل بين القنوات داخل ذلك المشغّل نفسه */
        @JavascriptInterface
        public void playPlaylist(final String m3uContent, final String pkg) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        File f = new File(getCacheDir(), "playlist.m3u");
                        FileOutputStream out = new FileOutputStream(f);
                        out.write(m3uContent.getBytes("UTF-8"));
                        out.close();

                        Uri uri = Uri.parse("content://com.smarttvplus.iptvmtc.playlist/playlist.m3u");
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(uri, "audio/x-mpegurl");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        if (pkg != null && pkg.length() > 0) intent.setPackage(pkg);
                        try {
                            startActivity(intent);
                        } catch (ActivityNotFoundException e) {
                            Intent generic = new Intent(Intent.ACTION_VIEW);
                            generic.setDataAndType(uri, "audio/x-mpegurl");
                            generic.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            try { startActivity(generic); }
                            catch (ActivityNotFoundException e2) { /* لا مشغّل يدعم قوائم m3u */ }
                        }
                    } catch (Exception e) { /* تعذرت كتابة ملف القائمة */ }
                }
            });
        }
    }

    /** جسر ملف صغير خارج مجلد التطبيق (على ذاكرة الجهاز المشتركة) يحفظ رمز النسخة الاحتياطية
        السحابية — يبقى موجوداً حتى لو حُذف التطبيق وأُعيد تثبيته من جديد، فيجد النسخة تلقائياً */
    private class StorageBridge {
        private File backupIdFile() {
            return new File(Environment.getExternalStorageDirectory(), "myTvPlus_backup_id.txt");
        }

        @JavascriptInterface
        public String readBackupId() {
            try {
                File f = backupIdFile();
                if (!f.exists()) return "";
                byte[] buf = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                int n = in.read(buf);
                in.close();
                return new String(buf, 0, Math.max(0, n), "UTF-8").trim();
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public boolean writeBackupId(String id) {
            try {
                FileOutputStream out = new FileOutputStream(backupIdFile());
                out.write(id.getBytes("UTF-8"));
                out.close();
                return true;
            } catch (Exception e) { return false; }
        }
    }

    /** جسر يخص خدمة "تبديل القنوات بالريموت" (RemoteKeyAccessibilityService) — تفعيلها الفعلي
        يتم يدوياً من المستخدم بشاشة إتاحة أندرويد (لا يوجد API يفعّلها برمجياً لأسباب أمنية)،
        هذا الجسر فقط يفتح تلك الشاشة مباشرة ويتحقق من حالة التفعيل الحالية لعرضها بواجهتنا */
    private class SystemBridge {
        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (ActivityNotFoundException e) { /* غير متاح على هذا الجهاز */ }
                }
            });
        }

        @JavascriptInterface
        public boolean isRemoteZapEnabled() {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;
            String me = getPackageName() + "/" + RemoteKeyAccessibilityService.class.getName();
            for (String s : enabled.split(":")) if (s.equalsIgnoreCase(me)) return true;
            return false;
        }
    }

    /** يرسل ضغطة زر إلى الويب برقم مفاتيح سامسونج الذي يفهمه app.js */
    private void sendJsKey(int keyCode) {
        String js = "(function(){var e=new KeyboardEvent('keydown',{bubbles:true});"
                + "Object.defineProperty(e,'keyCode',{get:function(){return " + keyCode + ";}});"
                + "document.dispatchEvent(e);})()";
        web.evaluateJavascript(js, null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            switch (ev.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    sendJsKey(10009);   // زر الرجوع بمنطق التطبيق (شاشة للخلف / خروج)
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    sendJsKey(10252);   // تشغيل/إيقاف
                    return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    sendJsKey(417);     // تقديم
                    return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    sendJsKey(412);     // إرجاع
                    return true;
                case KeyEvent.KEYCODE_PROG_RED:
                    sendJsKey(403);     // الزر الأحمر: مفضلة/حذف من المشاهدات الأخيرة أو القوائم
                    return true;
                case 70: // بعض الريموتات ترسل رمز 'F' الخام بدل KEYCODE_PROG_RED القياسي
                    sendJsKey(403);
                    return true;
            }
        }
        return super.dispatchKeyEvent(ev); // الأسهم وموافق والأرقام تصل للويب تلقائياً
    }

    @Override
    public void onBackPressed() {
        sendJsKey(10009);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // يعيد رسم شاشة الإعدادات إن كانت مفتوحة — كي تتحدّث حالة "تبديل القنوات بالريموت"
        // فوراً بعد رجوع المستخدم من شاشة إتاحة أندرويد (تفعيل/تعطيل الخدمة يحصل هناك لا عندنا)
        if (web != null) web.evaluateJavascript("if(window.__onAndroidResume)window.__onAndroidResume();", null);
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
