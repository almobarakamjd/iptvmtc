package com.smarttvplus.iptvmtc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * أوامر نافذة HDMI من Player+ أثناء المشاهدة (قائمته هي التي تملك الريموت حينها):
 * audio_hdmi / audio_main لاختيار الصوت، big / small لتكبير النافذة لملء الشاشة وإعادتها،
 * off لإطفاء النافذة، reset عند خروج المشغّل. النتيجة: 1 ظاهرة وعائمة فوق المشغّل، 2 تعمل بلا تعويم، 3 مطفأة (و0 إن لم يصل البث).
 */
public class WindowCommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        HdmiWindow h = HdmiWindow.get(context);
        // 1 ظاهرة وعائمة فوق المشغّل، 2 تعمل لكن داخل myTv+ فقط (خدمة الإتاحة غير مفعّلة)، 3 مطفأة
        if (isOrderedBroadcast()) setResultCode(!h.isShown() ? 3 : FloatFrame.canFloat() ? 1 : 2);
        RemoteLog.log("cmd", cmd + " shown=" + h.isShown() + " float=" + FloatFrame.canFloat());
        if (!h.isShown() || cmd == null) return;
        switch (cmd) {
            case "audio_hdmi": h.setMuted(false); break;
            case "audio_main": h.setMuted(true); break;
            case "big": h.setBig(true); h.setMuted(false); break;
            case "small": h.setBig(false); h.setMuted(true); break;
            case "reset": h.setBig(false); break;
            case "off": h.hide(); HdmiWindow.offByPlayer = true; break;
        }
    }
}
