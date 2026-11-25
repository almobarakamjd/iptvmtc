package com.smarttvplus.iptvmtc;

import android.app.Activity;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * نافذة القناة العائمة: قناة مباشرة من الاشتراك تعمل في نافذة مصغّرة بزاوية الشاشة، وتبقى فوق
 * التطبيقات الأخرى (stc tv مثلاً) إن كانت خدمة الإتاحة مفعّلة (انظر {@link FloatFrame}).
 *
 * - المشغّل هو MediaPlayer النظام (رابط القناة HLS) حتى لا نضيف مكتبة إلى myTv+.
 * - الصوت مكتوم: النافذة للمتابعة بالنظر، والصوت لما يملأ الشاشة.
 * - تحجز اتصال الاشتراك (اتصال واحد لكل اشتراكاتنا)، فتُغلق قبل تشغيل أي شيء آخر منه.
 */
final class ChannelWindow {

    private static final String TAG = "ChannelWindow";
    private static final long RETRY_MS = 5000;
    private static ChannelWindow instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FloatFrame frame;
    private MediaPlayer mp;
    private SurfaceHolder holder;
    private TextView status;
    private String url;
    private String shownAs;   // توقيع المعروض الآن، لتجاهل طلب إظهار مطابق عند كل عودة للتطبيق

    private final Runnable retry = this::startPlayback;

    static synchronized ChannelWindow get() {
        if (instance == null) instance = new ChannelWindow();
        return instance;
    }

    boolean isShown() { return frame != null; }

    void show(Activity activity, String streamUrl, String title, int corner, int sizePct) {
        String sig = streamUrl + "|" + corner + "|" + sizePct + "|" + FloatFrame.canFloat();
        if (frame != null && sig.equals(shownAs)) return;
        hide();
        url = streamUrl;
        FloatFrame f = new FloatFrame(activity);
        Context ctx = f.ctx;

        SurfaceView surface = new SurfaceView(ctx);
        f.view.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(ctx);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setText("⏳ " + (title == null ? "" : title));
        f.view.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { holder = h; startPlayback(); }
            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int ht) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { holder = null; releasePlayer(); }
        });

        try {
            frame = f;
            f.attach(activity, corner, sizePct);
            shownAs = sig;
        } catch (RuntimeException e) {
            Log.w(TAG, "show failed", e);
            frame = null;
        }
    }

    void hide() {
        ui.removeCallbacks(retry);
        releasePlayer();
        if (frame != null) frame.detach();
        frame = null;
        holder = null;
        status = null;
        shownAs = null;
    }

    private void startPlayback() {
        ui.removeCallbacks(retry);
        if (holder == null || url == null || frame == null) return;
        releasePlayer();
        try {
            MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setDisplay(holder);
            p.setDataSource(frame.ctx, Uri.parse(url));
            p.setVolume(0f, 0f);
            p.setOnPreparedListener(player -> {
                if (player == mp) player.start();
            });
            p.setOnInfoListener((player, what, extra) -> {
                if (player == mp && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && status != null) {
                    status.setText("");
                }
                return false;
            });
            p.setOnErrorListener((player, what, extra) -> {
                Log.w(TAG, "playback error " + what + "/" + extra);
                if (player == mp) failed();
                return true;
            });
            p.setOnCompletionListener(player -> { if (player == mp) failed(); });
            p.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "start failed", e);
            failed();
        }
    }

    /** انقطاع أو خطأ: نعيد المحاولة ما دامت النافذة ظاهرة (البث المباشر ينقطع ويعود) */
    private void failed() {
        releasePlayer();
        if (frame == null) return;
        if (status != null) status.setText("⚠️ تعذّر التشغيل، إعادة المحاولة…");
        ui.postDelayed(retry, RETRY_MS);
    }

    private void releasePlayer() {
        MediaPlayer p = mp;
        mp = null;
        if (p == null) return;
        try { p.reset(); } catch (RuntimeException ignored) {}
        try { p.release(); } catch (RuntimeException ignored) {}
    }
}
