package com.smarttvplus.iptvmtc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** خدمة إتاحة (Accessibility Service) اختيارية — المستخدم يفعّلها يدوياً من إعدادات أندرويد.
    غرضان لها:
    1) بينما مشغّل خارجي (VLC/MX) هو صاحب التركيز على الشاشة أثناء عرض قناة مباشرة، نعترض ضغطتي
       سهم فوق/تحت بالريموت (اللي عادة ما يفهمهما ذلك المشغّل كتبديل قناة) ونحوّلهما لمفتاح وسائط
       قياسي (التالي/السابق) يرسله أندرويد تلقائياً لجلسة الوسائط (MediaSession) النشطة حالياً —
       وهي جلسة VLC/MX نفسه بما إنه هو من يشغّل الصوت فعلياً، فينتقل للقناة التالية/السابقة بقائمة
       التشغيل التي سبق وسلّمناها له (انظر playPlaylist بـMainActivity وplayer.channelList بـapp.js).
       لا نتدخل بأي زر آخر ولا بأي تطبيق غير المشغّلات المعروفة أدناه.
    2) تشغّل خادم HTTP محلي مصغّر (RemoteControlServer) يسمح بالتحكم بالتلفاز عن بُعد من جهاز آخر
       بنفس شبكة الواي فاي (رئيسية/رجوع/وسائط/فتح تطبيق) — بديل لـADB لا يحتاج كابل ولا خيارات
       مطوّر، فقط تفعيل هذه الخدمة نفسها. */
public class RemoteKeyAccessibilityService extends AccessibilityService {

    private static final String[] PLAYER_PACKAGES = {
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro"
    };

    private String foregroundPackage = "";
    private RemoteControlServer remoteControlServer;

    @Override
    protected void onServiceConnected() {
        // بعض إصدارات أندرويد تحتاج تفعيل العلم برمجياً بالإضافة لملف الإعداد كي تصل ضغطات المفاتيح فعلياً
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }

        remoteControlServer = new RemoteControlServer(this);
        remoteControlServer.start();
    }

    @Override
    public void onDestroy() {
        if (remoteControlServer != null) remoteControlServer.stop();
        super.onDestroy();
    }

    String getForegroundPackage() {
        return foregroundPackage;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null) {
            foregroundPackage = event.getPackageName().toString();
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // نتصرّف عند الضغط فقط (ACTION_DOWN) — تجاهل ACTION_UP كي لا يُنفَّذ التبديل مرتين
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        if (!isPlayerForeground()) return false; // اترك السهم يعمل بسلوكه الطبيعي بأي شاشة أخرى (مثلاً تطبيقنا نفسه)

        sendMediaKey(code == KeyEvent.KEYCODE_DPAD_UP
                ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        return true; // امتصاص الضغطة كي لا يتصرّف بها المشغّل نفسه أيضاً (مثلاً تحريك شريط التقدّم)
    }

    private boolean isPlayerForeground() {
        for (String p : PLAYER_PACKAGES) if (p.equals(foregroundPackage)) return true;
        return false;
    }

    private void sendMediaKey(int keyCode) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    @Override
    public void onInterrupt() {}
}
