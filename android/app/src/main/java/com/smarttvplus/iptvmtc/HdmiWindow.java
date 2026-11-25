package com.smarttvplus.iptvmtc;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * نافذة HDMI المصغّرة: مربع صغير في زاوية الشاشة يعرض مدخل HDMI (عبر TvView) أثناء استعمال
 * myTv+ وPlayer+.
 *
 * - إن كانت خدمة الإتاحة {@link RemoteKeyAccessibilityService} مفعّلة فالنافذة عائمة على مستوى
 *   النظام (TYPE_ACCESSIBILITY_OVERLAY) فتبقى فوق Player+ أيضاً، وإلا فهي داخل نشاط myTv+ وحده.
 *   لم نعتمد إذن «الظهور فوق التطبيقات» لأن Android TV 9 بلا شاشة لمنحه (جُرِّب على المحاكي).
 * - إن لم يكن في الجهاز مدخل HDMI (المحاكي مثلاً) يُعرض مدخل افتراضي {@link #DEMO_ID} بصورة
 *   افتراضية (أشرطة ألوان)، لتجربة الموضع والحجم بلا عتاد. والصورة نفسها تظهر فوق المدخل الحقيقي
 *   ما دام بلا إشارة.
 * - صوت المدخل مكتوم دائماً حتى لا يختلط بصوت ما يُشاهَد.
 */
final class HdmiWindow {

    static final String DEMO_ID = "demo";
    private static final String TAG = "HdmiWindow";
    private static HdmiWindow instance;

    private final Context app;
    private FrameLayout box;
    private TvView tv;
    private WindowManager floatWm;   // غير null حين تكون النافذة عائمة (مضافة عبر خدمة الإتاحة)
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

    boolean canFloat() {
        return RemoteKeyAccessibilityService.instance != null;
    }

    boolean isShown() { return box != null; }

    /**
     * @param corner  0 أعلى اليمين، 1 أعلى اليسار، 2 أسفل اليمين، 3 أسفل اليسار
     * @param sizePct عرض النافذة نسبةً من عرض الشاشة (50 = ربع الشاشة)
     */
    void show(Activity activity, String inputId, int corner, int sizePct) {
        String sig = inputId + "|" + corner + "|" + sizePct + "|" + canFloat();
        if (box != null && sig.equals(shownAs)) return;
        hide();
        DisplayMetrics dm = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int pct = Math.max(15, Math.min(60, sizePct));
        int w = dm.widthPixels * pct / 100;
        int h = w * 9 / 16;
        int margin = dm.widthPixels / 60;
        int gravity = (corner == 1 || corner == 3 ? Gravity.LEFT : Gravity.RIGHT)
                | (corner >= 2 ? Gravity.BOTTOM : Gravity.TOP);

        RemoteKeyAccessibilityService service = RemoteKeyAccessibilityService.instance;
        Context ctx = service != null ? service : activity;
        FrameLayout f = new FrameLayout(ctx);
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(0xFF000000);
        frame.setStroke(3, 0xFFFFFFFF);
        f.setBackground(frame);
        f.setPadding(3, 3, 3, 3);

        boolean demo = DEMO_ID.equals(inputId) || inputId == null || inputId.length() == 0;
        final Placeholder ph = new Placeholder(ctx, demo ? "صورة افتراضية" : "لا إشارة");
        if (!demo) {
            try {
                tv = new TvView(ctx);
                tv.setCallback(new TvView.TvInputCallback() {
                    @Override public void onVideoAvailable(String id) { ph.setVisibility(View.GONE); }
                    @Override public void onVideoUnavailable(String id, int reason) { ph.setVisibility(View.VISIBLE); }
                    @Override public void onConnectionFailed(String id) { ph.setVisibility(View.VISIBLE); }
                    @Override public void onDisconnected(String id) { ph.setVisibility(View.VISIBLE); }
                });
                tv.setStreamVolume(0f);
                f.addView(tv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                tv.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId));
            } catch (Throwable t) {
                Log.w(TAG, "tune failed: " + inputId, t);
                tv = null;
            }
        }
        // الصورة الافتراضية فوق سطح الفيديو: تُخفى عند وصول الإشارة وتعود عند انقطاعها
        f.addView(ph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        try {
            if (service != null) {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        w, h,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = gravity;
                lp.x = margin;
                lp.y = margin;
                WindowManager wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
                wm.addView(f, lp);
                floatWm = wm;
            } else {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
                lp.setMargins(margin, margin, margin, margin);
                activity.addContentView(f, lp);
                floatWm = null;
            }
            box = f;
            shownAs = sig;
        } catch (Throwable t) {
            Log.w(TAG, "show failed", t);
            releaseTv();
        }
    }

    void hide() {
        if (box == null) return;
        releaseTv();
        try {
            if (floatWm != null) {
                floatWm.removeView(box);
            } else if (box.getParent() instanceof ViewGroup) {
                ((ViewGroup) box.getParent()).removeView(box);
            }
        } catch (Throwable t) {
            Log.w(TAG, "hide failed", t);
        }
        box = null;
        floatWm = null;
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
