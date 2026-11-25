package com.smarttvplus.iptvmtc;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * إطار نافذة مصغّرة في زاوية الشاشة، تشترك فيه نافذة HDMI ({@link HdmiWindow}) ونافذة القناة
 * ({@link ChannelWindow}).
 *
 * إن كانت خدمة الإتاحة {@link RemoteKeyAccessibilityService} مفعّلة فالإطار عائم على مستوى النظام
 * (TYPE_ACCESSIBILITY_OVERLAY) فيبقى فوق كل التطبيقات (Player+، stc tv…)، وإلا فهو داخل نشاط
 * myTv+ وحده. وإن لم تعمل الخدمة (على TCL بأندرويد 12 تُفعَّل ولا تشتغل) ومُنح إذن «الظهور فوق
 * التطبيقات» فالإطار عائم به (TYPE_APPLICATION_OVERLAY). Android TV 9 بلا شاشة لمنح هذا الإذن،
 * فهناك تبقى الخدمة هي الطريق.
 */
final class FloatFrame {

    /** السياق الذي تُنشأ به محتويات الإطار: الخدمة إن كان عائماً وإلا النشاط */
    final Context ctx;
    final FrameLayout view;
    private final RemoteKeyAccessibilityService service;
    private WindowManager wm;
    private boolean attached;
    private boolean full;
    private int w, h, margin;

    /** سياق التطبيق، يُضبط عند بدء النشاط أو وصول أمر من Player+ */
    static volatile Context app;
    /** عائم بإذن «الظهور فوق التطبيقات» لا بخدمة الإتاحة */
    private final boolean overlay;

    FloatFrame(Activity activity) {
        if (app == null) app = activity.getApplicationContext();
        service = RemoteKeyAccessibilityService.instance;
        overlay = service == null && canOverlay();
        ctx = service != null ? service : overlay ? app : activity;
        view = new FrameLayout(ctx);
        GradientDrawable border = new GradientDrawable();
        border.setColor(0xFF000000);
        border.setStroke(3, 0xFFFFFFFF);
        view.setBackground(border);
        view.setPadding(3, 3, 3, 3);
    }

    static boolean canFloat() {
        return RemoteKeyAccessibilityService.instance != null || canOverlay();
    }

    /** إذن «الظهور فوق التطبيقات»: على TCL (أندرويد 12) خدمة الإتاحة لا تشتغل، وهذا الإذن بديلها */
    static boolean canOverlay() {
        try {
            return app != null && Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(app);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @param corner  0 أعلى اليمين، 1 أعلى اليسار، 2 أسفل اليمين، 3 أسفل اليسار
     * @param sizePct عرض النافذة نسبةً من عرض الشاشة (50 = ربع الشاشة)
     */
    void attach(Activity activity, int corner, int sizePct) {
        DisplayMetrics dm = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int pct = Math.max(15, Math.min(60, sizePct));
        w = dm.widthPixels * pct / 100;
        h = w * 9 / 16;
        margin = dm.widthPixels / 60;
        int gravity = (corner == 1 || corner == 3 ? Gravity.LEFT : Gravity.RIGHT)
                | (corner >= 2 ? Gravity.BOTTOM : Gravity.TOP);
        if (service != null || overlay) {
            int type = service != null ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    : Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    w, h,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            // أندرويد 12+ يجعل نافذة التطبيق العائمة غير القابلة للمس شبه شفافة (حد 0.8)،
                            // فنتركها قابلة للمس حين تعوم بالإذن؛ التلفاز بلا لمس أصلاً
                            | (service != null ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0)
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = gravity;
            lp.x = margin;
            lp.y = margin;
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            wm.addView(view, lp);
        } else {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
            lp.setMargins(margin, margin, margin, margin);
            activity.addContentView(view, lp);
        }
        attached = true;
    }

    /** ملء الشاشة أو العودة إلى الزاوية (يأمر به Player+ عبر {@link WindowCommandReceiver}) */
    boolean isFloating() { return wm != null; }

    void setFull(boolean f) {
        if (!attached || full == f) return;
        full = f;
        int m = f ? 0 : margin;
        try {
            if (wm != null) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) view.getLayoutParams();
                lp.width = f ? ViewGroup.LayoutParams.MATCH_PARENT : w;
                lp.height = f ? ViewGroup.LayoutParams.MATCH_PARENT : h;
                lp.x = m;
                lp.y = m;
                wm.updateViewLayout(view, lp);
            } else {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
                lp.width = f ? ViewGroup.LayoutParams.MATCH_PARENT : w;
                lp.height = f ? ViewGroup.LayoutParams.MATCH_PARENT : h;
                lp.setMargins(m, m, m, m);
                view.setLayoutParams(lp);
            }
        } catch (RuntimeException ignored) {}
    }

    void detach() {
        if (!attached) return;
        attached = false;
        try {
            if (wm != null) wm.removeView(view);
            else if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        } catch (RuntimeException ignored) {
            // الخدمة أو النشاط زالا قبلنا فزالت النافذة معهما
        }
    }
}
