package com.smarttvplus.iptvmtc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
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
    private UpdateManager updates;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FloatFrame.app = getApplicationContext();
        current = this;
        AiDoctor.init(this);
        watchCrashes();
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(homeWatcher, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(homeWatcher, f);
        } catch (Exception e) {
            RemoteLog.log("app", "homeWatcher failed: " + e);
        }
        RemoteLog.init(this);

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
                if (cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    String src = String.valueOf(cm.sourceId());
                    RemoteLog.log("jserr", cm.message() + " @ " + src.substring(src.lastIndexOf('/') + 1) + ":" + cm.lineNumber());
                }
                return true;
            }
        });
        web.setBackgroundColor(0xFF000000);
        web.addJavascriptInterface(new AndroidBridge(), "AndroidExit");
        web.addJavascriptInterface(new OpenBridge(), "AndroidOpen");
        web.addJavascriptInterface(new StorageBridge(), "AndroidStorage");
        web.addJavascriptInterface(new SystemBridge(), "AndroidSystem");
        web.addJavascriptInterface(new HdmiBridge(), "AndroidHdmi");
        web.addJavascriptInterface(new AiBridge(), "AndroidAi");

        startDiagnosticPingServer(); // تشخيص مؤقت: نتأكد هل المشكلة خاصة بخدمة الإتاحة أم عامة بالشبكة/التلفاز

        setContentView(web);
        hideSystemUi();
        web.requestFocus();
        web.loadUrl("file:///android_asset/index.html");

        // تثبيت Player+ إن لم يكن مثبّتاً، وتحديث التطبيقين — بعد ظهور الواجهة بقليل
        updates = new UpdateManager(this);
        web.postDelayed(() -> updates.check(false), 4000);
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
           title: اسم الفيلم/الحلقة — بدونه بعض المشغّلات تعرض رقم القناة/الفيلم المستخرج من الرابط
           نفسه بدل اسمه الحقيقي. المفتاح "title" (نص بسيط) هو الموثّق فعلياً في توثيق VLC وMX
           Player الرسمي لعرض اسم الفيديو — وليس android.intent.extra.TITLE كما ظُنَّ سابقاً (تأكّد
           بالتجربة الفعلية أن MX استمر بعرض الرقم بعد استخدام EXTRA_TITLE وحده)، لذا نرسل الاثنين
           معاً الآن لتغطية أوسع عدد من المشغّلات. */
        @JavascriptInterface
        public void playVideo(final String u, final String pkg, final String title) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(u), "video/*");
                    String targetPkg = pkg;
                    if ((targetPkg == null || targetPkg.length() == 0) && isInstalled("com.oqod.movie_player")) {
                        targetPkg = "com.oqod.movie_player";
                    }
                    if (targetPkg != null && targetPkg.length() > 0) intent.setPackage(targetPkg);
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

        /* مشغّل myTv+ الخاص (مشروع movie_player): يستقبل JSON كامل — قائمة القنوات/الحلقات، بيانات
           الإعادة، ورقم TMDB للترجمة. يعيد false إن لم يكن مثبّتاً ليرجع app.js لطريقة VLC/MX */
        @JavascriptInterface
        public boolean playInPlayer(String payloadJson) {
            Intent intent = new Intent("com.oqod.movie_player.PLAY");
            intent.setPackage("com.oqod.movie_player");
            intent.putExtra("com.oqod.movie_player.PAYLOAD", payloadJson);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
                return true;
            } catch (ActivityNotFoundException e) {
                return false;
            } catch (RuntimeException e) {
                // حجم البيانات تجاوز حد أندرويد (TransactionTooLarge) أو خطأ غير متوقع
                Log.w("myTvPlus", "playInPlayer failed", e);
                return false;
            }
        }

        @JavascriptInterface
        public void saveTitleMeta(String streamId, String metaJson) {
            if (streamId == null || metaJson == null) return;
            MetaProvider.put(MainActivity.this, streamId, metaJson);
        }

        @JavascriptInterface
        public boolean isInstalled(String pkg) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        private void addTitleExtras(Intent intent, String title) {
            if (title == null || title.length() == 0) return;
            try {
                intent.putExtra("title", title);          // المفتاح الذي يقرأه VLC وMX Player فعلياً
                intent.putExtra(Intent.EXTRA_TITLE, title); // إضافي لتغطية مشغّلات أخرى قد تعتمده
            } catch (Exception ignored) {}
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

        /** زر "تنزيل وتثبيت مشغّل Player+" في الشاشة الرئيسية */
        @JavascriptInterface
        public void installPlayer() {
            runOnUiThread(() -> { if (updates != null) updates.installPlayerNow(); });
        }

        /** زر "التحقق من التحديثات" في إعدادات myTv+ */
        @JavascriptInterface
        public void checkUpdates() {
            runOnUiThread(() -> { if (updates != null) updates.check(true); });
        }

        /** إصدار Player+ المثبّت (فارغ إن لم يكن مثبّتاً) */
        @JavascriptInterface
        public String playerVersion() {
            try {
                return getPackageManager().getPackageInfo("com.oqod.movie_player", 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                return "";
            }
        }

        @JavascriptInterface
        public String appVersion() {
            return updates != null ? updates.versionName() : "";
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

    /** جسر النوافذ المصغّرة: نافذة HDMI (HdmiWindow) ونافذة القناة (ChannelWindow)، وهل تعومان
        فوق التطبيقات الأخرى (يتطلب تفعيل خدمة الإتاحة) */
    private class HdmiBridge {
        @JavascriptInterface
        public String inputs() {
            return HdmiWindow.get(MainActivity.this).inputsJson();
        }

        @JavascriptInterface
        public boolean canFloat() {
            return FloatFrame.canFloat();
        }

        /** قناة مباشرة في نافذة عائمة (ChannelWindow) */
        @JavascriptInterface
        public void showChannel(final String url, final String title, final int corner, final int sizePct) {
            runOnUiThread(() -> ChannelWindow.get().show(MainActivity.this, url, title, corner, sizePct));
        }

        @JavascriptInterface
        public void hideChannel() {
            runOnUiThread(() -> ChannelWindow.get().hide());
        }

        @JavascriptInterface
        public void show(final String inputId, final int corner, final int sizePct) {
            runOnUiThread(() -> HdmiWindow.get(MainActivity.this).show(MainActivity.this, inputId, corner, sizePct));
        }

        @JavascriptInterface
        public void hide() {
            runOnUiThread(() -> HdmiWindow.get(MainActivity.this).hide());
        }

        @JavascriptInterface
        public void mute(final boolean m) {
            runOnUiThread(() -> HdmiWindow.get(MainActivity.this).setMuted(m));
        }

        /** هل مُنح إذن «الظهور فوق التطبيقات»؟ */
        @JavascriptInterface
        public boolean canOverlay() {
            return FloatFrame.canOverlay();
        }

        /** يفتح شاشة منح إذن «الظهور فوق التطبيقات»؛ false إن لم تكن في الجهاز شاشة له */
        @JavascriptInterface
        public boolean openOverlaySettings() {
            android.net.Uri pkg = android.net.Uri.parse("package:" + getPackageName());
            String[] actions = {
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            };
            for (String a : actions) {
                try {
                    startActivity(new Intent(a, pkg));
                    RemoteLog.log("app", "openOverlaySettings via " + a);
                    return true;
                } catch (Exception e) {
                    RemoteLog.log("app", "openOverlaySettings " + a + " failed: " + e);
                }
            }
            return false;
        }

        /** true مرة واحدة إن أُطفئت النافذة من قائمة Player+، لتحفظ الواجهة الإطفاء */
        @JavascriptInterface
        public boolean takeOff() {
            boolean b = HdmiWindow.offByPlayer;
            HdmiWindow.offByPlayer = false;
            return b;
        }

        /** true مرة واحدة إن شُغّلت النافذة من قائمة Player+، لتحفظ الواجهة التشغيل */
        @JavascriptInterface
        public boolean takeOn() {
            boolean b = HdmiWindow.onByPlayer;
            HdmiWindow.onByPlayer = false;
            return b;
        }

        /** هل التطبيق في الواجهة الآن؟ يستعمله حارس HDMI ليعرف هل أخرجنا التلفاز عند التوليف */
        @JavascriptInterface
        public boolean resumed() {
            return resumed;
        }

        /** نحن من يغادر إلى المشغّل الآن: ليست انتزاعاً من التلفاز */
        @JavascriptInterface
        public void leaving() {
            HdmiWindow.selfLeaveAt = android.os.SystemClock.elapsedRealtime();
        }

        /** سطر من الواجهة إلى سجلّ التشخيص عن بُعد */
        @JavascriptInterface
        public void log(String msg) {
            RemoteLog.log("js", msg);
        }
    }

    /* ---------- المساعد الذكي (AiDoctor) ---------- */

    private static volatile MainActivity current;

    /** جسر شاشة «المساعد الذكي» */
    private class AiBridge {
        @JavascriptInterface
        public String state() { return AiDoctor.stateJson(); }

        /** mode: check (فحص عام) أو ask (سؤال). يعيد نصاً فارغاً إن بدأ، أو سبب الرفض. */
        @JavascriptInterface
        public String start(String mode, String question) { return AiDoctor.start(mode, question); }

        @JavascriptInterface
        public void setAuto(boolean on) { AiDoctor.setAuto(on); }

        @JavascriptInterface
        public void clearNotes() { AiDoctor.clearNotes(); }

        @JavascriptInterface
        public void clearChat() { AiDoctor.clearChat(); }

        /** يفتح التعرف على الكلام (ميكروفون الريموت)، والنص يعود إلى window.__onVoice(text, error) */
        @JavascriptInterface
        public void listen() {
            runOnUiThread(() -> {
                Intent in = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "تكلّم الآن…");
                try {
                    startActivityForResult(in, REQ_VOICE);
                } catch (Exception e) {
                    RemoteLog.log("app", "voice unavailable: " + e);
                    web.evaluateJavascript("if(window.__onVoice)window.__onVoice('','unavailable');", null);
                }
            });
        }

        /** ناتج أداة غير متزامنة (AiTools.media) يعود إلى الخيط المنتظر في callJsAsync */
        @JavascriptInterface
        public void toolResult(String token, String text) {
            java.util.concurrent.ArrayBlockingQueue<String> q = pendingTools.get(token);
            if (q != null) q.offer(text == null ? "" : text);
        }

        /** سطر من الواجهة إلى السجلّ بوسمه: toast لما رآه المستخدم، neterr لفشل الاتصال، jserr لخطأ الواجهة */
        @JavascriptInterface
        public void note(String tag, String msg) { RemoteLog.log(tag, msg); }
    }

    private static final int REQ_VOICE = 2001;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE || web == null) return;
        String text = "";
        if (resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<String> r = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) text = r.get(0);
        }
        web.evaluateJavascript("if(window.__onVoice)window.__onVoice(" + org.json.JSONObject.quote(text) + ",'');", null);
    }

    /** تغيّرت حالة المساعد (بدأ، خطوة جديدة، انتهى): تُبلَّغ الواجهة إن كانت حيّة */
    static void notifyAi() {
        final MainActivity a = current;
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (a.web != null && !a.isDestroyed())
                a.web.evaluateJavascript("if(window.__onAiState)window.__onAiState();", null);
        });
    }

    /**
     * ينفّذ تعبير JS يعيد نصاً، من خيط خلفي، وينتظر ناتجه (حتى 6 ثوانٍ). أدوات المساعد التي تقرأ حالة
     * الواجهة أو تغيّر إعداداتها تمرّ من هنا. يرمي إن كانت الواجهة مغلقة.
     */
    static String callJs(final String expr) throws Exception {
        final MainActivity a = current;
        if (a == null || a.web == null || a.isDestroyed()) throw new Exception("واجهة التطبيق مغلقة الآن");
        final String[] out = new String[1];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        a.runOnUiThread(() -> {
            try {
                a.web.evaluateJavascript("(function(){try{return String(" + expr + ");}catch(e){return 'JSERR '+e;}})()", v -> {
                    out[0] = v;
                    done.countDown();
                });
            } catch (Exception e) {
                done.countDown();
            }
        });
        if (!done.await(6, java.util.concurrent.TimeUnit.SECONDS) || out[0] == null) throw new Exception("الواجهة لم تردّ");
        Object v = new org.json.JSONTokener(out[0]).nextValue();   // الناتج نص JSON بين علامتي تنصيص
        String s = String.valueOf(v);
        if (s.startsWith("JSERR ")) throw new Exception(s);
        return s;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<String>>
            pendingTools = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger toolSeq = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * ينادي دالة واجهة غير متزامنة أول معاملاتها رمز، تردّ لاحقاً بـAndroidAi.toolResult(رمز، نص)،
     * وينتظر ردّها (تحميل قائمة كاملة من السيرفر قد يطول).
     * @param argsJs بقية المعاملات كنص JS جاهز (مقتبَس)
     */
    static String callJsAsync(String fn, String argsJs, int seconds) throws Exception {
        final String token = "t" + toolSeq.incrementAndGet();
        java.util.concurrent.ArrayBlockingQueue<String> q = new java.util.concurrent.ArrayBlockingQueue<>(1);
        pendingTools.put(token, q);
        try {
            callJs("(" + fn + "('" + token + "'," + argsJs + "),'')");
            String r = q.poll(seconds, java.util.concurrent.TimeUnit.SECONDS);
            if (r == null) throw new Exception("الواجهة لم تردّ خلال " + seconds + " ثانية");
            return r;
        } finally {
            pendingTools.remove(token);
        }
    }

    /** انهيار غير ملتقَط: يُكتب في ملف، ويدخل السجلّ عند الفتح التالي ليدرسه المساعد */
    private void watchCrashes() {
        final File f = new File(getFilesDir(), "last_crash.txt");
        try {
            if (f.exists()) {
                byte[] buf = new byte[(int) Math.min(f.length(), 6000)];
                FileInputStream in = new FileInputStream(f);
                int n = in.read(buf);
                in.close();
                f.delete();
                if (n > 0) RemoteLog.log("crash", "previous run crashed:\n" + new String(buf, 0, n, "UTF-8"));
            }
        } catch (Exception ignored) {}
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        if (old instanceof CrashSaver) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashSaver(f, old));
    }

    private static final class CrashSaver implements Thread.UncaughtExceptionHandler {
        private final File file;
        private final Thread.UncaughtExceptionHandler next;

        CrashSaver(File file, Thread.UncaughtExceptionHandler next) { this.file = file; this.next = next; }

        @Override public void uncaughtException(Thread t, Throwable e) {
            try {
                FileOutputStream out = new FileOutputStream(file);
                out.write(("thread=" + t.getName() + "\n" + Log.getStackTraceString(e)).getBytes("UTF-8"));
                out.close();
            } catch (Exception ignored) {}
            if (next != null) next.uncaughtException(t, e);
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
    protected void onPause() {
        resumed = false;
        RemoteLog.log("app", "onPause finishing=" + isFinishing());
        RemoteLog.flush();
        returnIfSnatched();
        super.onPause();
    }

    @Override
    protected void onStop() {
        RemoteLog.log("app", "onStop");
        RemoteLog.dumpOwnLogcat("onStop");
        RemoteLog.flush();
        super.onStop();
    }

    private volatile boolean resumed;

    /**
     * على TCL (سجلّ 2026-11-26): تطبيق التلفاز نفسه (com.tcl.tv/com.tcl.player.TVActivity) يفتح بملء
     * الشاشة فوقنا ما دامت نافذة HDMI تعمل: غالباً بعد التوليف بأقل من ثانيتين، وأحياناً بعده بنصف
     * دقيقة (02:19:32). وحين نعود للواجهة تعود الصورة إلى النافذة وتبقى. فنعود تلقائياً كلما غادرنا
     * الواجهة والنافذة تعمل، إلا إن كانت المغادرة منا (فتح المشغّل أو أي نشاط نبدؤه) أو بزر الرئيسية.
     * إن كانت خدمة الإتاحة تعمل فالحكم دقيق: لا نعود إلا إن كان الذي في الواجهة تطبيق التلفاز.
     * والحدّ 4 عودات في الدقيقة حتى لا ندور.
     */
    private static final String TCL_TV = "com.tcl.tv";
    private long homeAt;
    private final long[] returnTimes = new long[4];
    private int returnIdx;

    private final android.content.BroadcastReceiver homeWatcher = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent in) {
            String reason = in.getStringExtra("reason");
            RemoteLog.log("app", "closeSystemDialogs reason=" + reason);
            if ("homekey".equals(reason) || "recentapps".equals(reason) || "assist".equals(reason)) {
                homeAt = android.os.SystemClock.elapsedRealtime();
            }
        }
    };

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        HdmiWindow.selfLeaveAt = android.os.SystemClock.elapsedRealtime();   // مغادرة منا لا انتزاع
        super.startActivityForResult(intent, requestCode, options);
    }

    @Override
    protected void onUserLeaveHint() {
        RemoteLog.log("app", "onUserLeaveHint");
        super.onUserLeaveHint();
    }

    private void returnIfSnatched() {
        if (isFinishing()) return;
        if (!HdmiWindow.get(this).isRealShown()) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (resumed || isFinishing() || isDestroyed()) return;
            if (!HdmiWindow.get(this).isRealShown()) return;
            long now = android.os.SystemClock.elapsedRealtime();
            RemoteKeyAccessibilityService svc = RemoteKeyAccessibilityService.instance;
            String fg = svc != null ? svc.getForegroundPackage() : "?";
            boolean self = now - HdmiWindow.selfLeaveAt < 5000;
            boolean home = now - homeAt < 5000;
            boolean snatched = svc != null ? TCL_TV.equals(fg) : (!self && !home);
            long oldest = returnTimes[returnIdx];
            boolean tooMany = oldest != 0 && now - oldest < 60000;
            RemoteLog.log("app", "left front: fg=" + fg + " self=" + self + " home=" + home
                    + " sinceTune=" + (now - HdmiWindow.lastTuneAt) + " -> "
                    + (!snatched ? "stay away" : tooMany ? "too many returns" : "return"));
            if (!snatched || tooMany) return;
            returnTimes[returnIdx] = now;
            returnIdx = (returnIdx + 1) % returnTimes.length;
            try {
                Intent i = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                (svc != null ? svc : getApplicationContext()).startActivity(i);
            } catch (Exception e) {
                RemoteLog.log("app", "return failed: " + e);
            }
        }, 900);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        RemoteLog.log("app", "onResume");
        if (updates != null) updates.onResume();
        // يعيد رسم شاشة الإعدادات إن كانت مفتوحة — كي تتحدّث حالة "تبديل القنوات بالريموت"
        // فوراً بعد رجوع المستخدم من شاشة إتاحة أندرويد (تفعيل/تعطيل الخدمة يحصل هناك لا عندنا)
        if (web != null) web.evaluateJavascript("if(window.__onAndroidResume)window.__onAndroidResume();", null);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(homeWatcher); } catch (Exception ignored) {}
        if (current == this) current = null;
        HdmiWindow.get(this).hide();
        ChannelWindow.get().hide();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
