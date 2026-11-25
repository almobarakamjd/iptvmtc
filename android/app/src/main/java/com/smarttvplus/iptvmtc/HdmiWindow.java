package com.smarttvplus.iptvmtc;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * نافذة HDMI المصغّرة: مربع صغير في زاوية الشاشة يعرض مدخل HDMI (عبر TvView) أثناء استعمال
 * myTv+ وPlayer+.
 *
 * - موضعها وتعويمها فوق التطبيقات الأخرى في {@link FloatFrame}.
 * - إن لم يكن في الجهاز مدخل HDMI (المحاكي مثلاً) يُعرض مدخل افتراضي {@link #DEMO_ID} بصورة
 *   افتراضية (أشرطة ألوان)، لتجربة الموضع والحجم بلا عتاد. والصورة نفسها تظهر فوق المدخل الحقيقي
 *   ما دام بلا إشارة.
 * - صوت المدخل مسموع ما دام لا شيء يعمل في المشغّل الكبير، ويُكتم عند تشغيله ({@link #setMuted}).
 */
final class HdmiWindow {

    static final String DEMO_ID = "demo";
    private static final String TAG = "HdmiWindow";
    private static HdmiWindow instance;
    /** أطفأها المستخدم من قائمة Player+: تقرؤها الواجهة عند العودة فتحفظ الإطفاء */
    static volatile boolean offByPlayer;

    private final Context app;
    private FloatFrame frame;
    private TvView tv;
    private boolean muted = true;    // يُكتم ما دام المشغّل الكبير يعمل، وإلا فصوت المدخل مسموع
    private String shownAs;   // توقيع ما هو معروض الآن، لتجاهل طلب إظهار مطابق (عند كل عودة للتطبيق)

    private HdmiWindow(Context c) { app = c.getApplicationContext(); }

    static synchronized HdmiWindow get(Context c) {
        if (instance == null) instance = new HdmiWindow(c);
        return instance;
    }

    /** مداخل العبور (HDMI/AV…) المسجّلة في النظام، أو المدخل الافتراضي إن لم يوجد شيء */
    String inputsJson() {
        JSONArray arr = new JSONArray();
        try {
            TvInputManager m = (TvInputManager) app.getSystemService(Context.TV_INPUT_SERVICE);
            List<TvInputInfo> list = m != null ? m.getTvInputList() : null;
            if (list != null) {
                for (TvInputInfo info : list) {
                    if (!info.isPassthroughInput()) continue;
                    JSONObject o = new JSONObject();
                    int state = -1;
                    try { state = m.getInputState(info.getId()); } catch (Throwable ignored) {}
                    RemoteLog.log("hdmi", "input id=" + info.getId() + " type=" + info.getType()
                            + " state=" + state + " parent=" + info.getParentId() + " hidden=" + info.isHidden(app));
                    o.put("id", info.getId());
                    CharSequence label = info.loadLabel(app);
                    o.put("label", label != null ? label.toString() : info.getId());
                    arr.put(o);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "listing tv inputs failed", t);
        }
        if (arr.length() == 0) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", DEMO_ID);
                o.put("label", "HDMI (صورة افتراضية)");
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    boolean isShown() { return frame != null; }

    /** الموضع والحجم كما في {@link FloatFrame#attach} */
    void show(Activity activity, String inputId, int corner, int sizePct) {
        String sig = inputId + "|" + corner + "|" + sizePct + "|" + FloatFrame.canFloat();
        if (frame != null && sig.equals(shownAs)) return;
        hide();
        RemoteLog.log("hdmi", "show " + sig + " muted=" + muted);
        FloatFrame f = new FloatFrame(activity);
        Context ctx = f.ctx;

        boolean demo = DEMO_ID.equals(inputId) || inputId == null || inputId.length() == 0;
        final Placeholder ph = new Placeholder(ctx, demo ? "صورة افتراضية" : "لا إشارة");
        if (!demo) {
            try {
                tv = new TvView(ctx);
                tv.setCallback(new TvView.TvInputCallback() {
                    @Override public void onVideoAvailable(String id) {
                        RemoteLog.log("hdmi", "videoAvailable " + id);
                        ph.setVisibility(View.GONE);
                    }
                    @Override public void onVideoUnavailable(String id, int reason) {
                        RemoteLog.log("hdmi", "videoUnavailable " + id + " reason=" + reason);
                        ph.setVisibility(View.VISIBLE);
                    }
                    @Override public void onConnectionFailed(String id) {
                        RemoteLog.log("hdmi", "connectionFailed " + id);
                        ph.setVisibility(View.VISIBLE);
                    }
                    @Override public void onDisconnected(String id) {
                        RemoteLog.log("hdmi", "disconnected " + id);
                        ph.setVisibility(View.VISIBLE);
                    }
                    @Override public void onVideoSizeChanged(String id, int w, int h) {
                        RemoteLog.log("hdmi", "videoSize " + w + "x" + h);
                    }
                });
                tv.setStreamVolume(muted ? 0f : 1f);
                f.view.addView(tv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                tv.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId));
            } catch (Throwable t) {
                Log.w(TAG, "tune failed: " + inputId, t);
                RemoteLog.log("hdmi", "tune failed: " + t);
                tv = null;
            }
        }
        // الصورة الافتراضية فوق سطح الفيديو: تُخفى عند وصول الإشارة وتعود عند انقطاعها
        f.view.addView(ph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        try {
            f.attach(activity, corner, sizePct);
            frame = f;
            shownAs = sig;
        } catch (Throwable t) {
            Log.w(TAG, "show failed", t);
            RemoteLog.log("hdmi", "attach failed: " + t);
            releaseTv();
        }
    }

    void setBig(boolean big) {
        RemoteLog.log("hdmi", "setBig " + big + " shown=" + (frame != null));
        if (frame != null) frame.setFull(big);
    }

    void setMuted(boolean m) {
        if (m != muted) RemoteLog.log("hdmi", "setMuted " + m + " tv=" + (tv != null));
        muted = m;
        if (tv != null) try { tv.setStreamVolume(m ? 0f : 1f); } catch (Throwable ignored) {}
    }

    void hide() {
        if (frame != null) RemoteLog.log("hdmi", "hide");
        releaseTv();
        if (frame != null) frame.detach();
        frame = null;
        shownAs = null;
    }

    private void releaseTv() {
        if (tv == null) return;
        try { tv.reset(); } catch (Throwable ignored) {}
        tv = null;
    }

    /** الصورة الافتراضية: أشرطة ألوان الاختبار وفوقها اسم المدخل وحالته */
    private static final class Placeholder extends View {
        private static final int[] BARS = {
                0xFFC0C0C0, 0xFFC0C000, 0xFF00C0C0, 0xFF00C000, 0xFFC000C0, 0xFFC00000, 0xFF0000C0
        };
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF plate = new RectF();
        private final String note;

        Placeholder(Context c, String note) {
            super(c);
            this.note = note;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            paint.setStyle(Paint.Style.FILL);
            float bw = w / (float) BARS.length;
            for (int i = 0; i < BARS.length; i++) {
                paint.setColor(BARS[i]);
                canvas.drawRect(i * bw, 0, (i + 1) * bw, h * 0.72f, paint);
            }
            paint.setColor(0xFF101010);
            canvas.drawRect(0, h * 0.72f, w, h, paint);

            float title = h * 0.20f;
            plate.set(w * 0.22f, h * 0.24f, w * 0.78f, h * 0.60f);
            paint.setColor(0xD9000000);
            canvas.drawRoundRect(plate, h * 0.04f, h * 0.04f, paint);

            paint.setColor(0xFFFFFFFF);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(title);
            canvas.drawText("HDMI", w / 2f, plate.centerY() + title * 0.35f, paint);

            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(h * 0.10f);
            canvas.drawText(note, w / 2f, h * 0.72f + (h * 0.28f) / 2f + h * 0.035f, paint);
        }
    }
}
