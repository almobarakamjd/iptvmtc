package com.smarttvplus.iptvmtc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * أوامر نافذة HDMI من Player+ أثناء المشاهدة (قائمته هي التي تملك الريموت حينها):
 * audio_hdmi / audio_main لاختيار الصوت، big / small لتكبير النافذة لملء الشاشة وإعادتها،
 * channel (مع streamId) يبلّغ القناة المعروضة الآن بلا أثر على النافذة، off لإطفاء النافذة، reset عند خروج المشغّل، toggle لتشغيلها أو إطفائها (نتيجته: 11 شُغّلت، 12 أُطفئت،
 * 2 لا إذن تعويم فلن تظهر فوق المشغّل، 4 لم يُختر مدخل بعد، 5 تعذّر التشغيل). نتيجة البقية: 1 ظاهرة وعائمة فوق المشغّل، 2 تعمل بلا تعويم، 3 مطفأة (و0 إن لم يصل البث).
 */
public class WindowCommandReceiver extends BroadcastReceiver {
    /** معرّف القناة التي يعرضها Player+ الآن (أمر channel عند كل تبديل)، ليحسب المساعد «التالية» منها */
    static volatile String playerChannel = "";

    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        if ("channel".equals(cmd)) {
            String id = intent.getStringExtra("streamId");
            playerChannel = id == null ? "" : id;
            return;
        }
        if (FloatFrame.app == null) FloatFrame.app = context.getApplicationContext();
        HdmiWindow h = HdmiWindow.get(context);
        if ("toggle".equals(cmd)) {
            int r;
            if (h.isShown()) {
                h.hide();
                HdmiWindow.offByPlayer = true;
                HdmiWindow.onByPlayer = false;
                r = 12;
            } else if (!FloatFrame.canFloat()) {
                r = 2;
            } else if (!h.hasLast()) {
                r = 4;
            } else {
                h.setMuted(true);   // المشغّل يعمل: صوته هو المسموع حتى يختار المستخدم صوت النافذة
                h.showLast();
                HdmiWindow.onByPlayer = h.isShown();
                HdmiWindow.offByPlayer = false;
                r = h.isShown() ? 11 : 5;
            }
            RemoteLog.log("cmd", "toggle -> " + r);
            if (isOrderedBroadcast()) setResultCode(r);
            return;
        }
        // 1 ظاهرة وعائمة فوق المشغّل، 2 تعمل لكن داخل myTv+ فقط (خدمة الإتاحة غير مفعّلة)، 3 مطفأة
        if (isOrderedBroadcast()) setResultCode(!h.isShown() ? 3 : h.isFloating() ? 1 : 2);
        RemoteLog.log("cmd", cmd + " shown=" + h.isShown() + " floating=" + h.isFloating());
        // نافذة داخل myTv+ لا يراها المستخدم من المشغّل: تكبيرها أو فتح صوتها هناك خطأ (1.7.9: عادت
        // مكبَّرة لملء الشاشة بعد الرجوع، وصوت المدخل اختلط بالفيلم)
        if (cmd == null || !h.isFloating()) return;
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
