package com.smarttvplus.iptvmtc;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * يجيب Player+ عن سؤال: "ما اسم هذا الفيلم؟"
 *
 * سبب وجوده: إن فُتح المشغّل بالطريقة العادية (رابط فيديو فقط — مثلاً من نافذة اختيار أندرويد
 * قبل ضبط المشغّل المفضّل) فلا يصله اسم العمل، فيعرض رقم الملف من الرابط. عندها يسأل المشغّل
 * هذا الموفّر برقم البث (streamId) فيعيد له الاسم والسنة وTMDB والمدة… ليعرض الاسم الصحيح
 * ويبحث عن الترجمة.
 *
 *   content://com.smarttvplus.iptvmtc.meta/item?streamId=2099842
 *   content://com.smarttvplus.iptvmtc.meta/item?url=http://host/movie/u/p/2099842.mkv
 *
 * الرد: صف واحد بعمود "json" فيه بيانات العمل. لا يحوي أي بيانات دخول للاشتراك.
 */
public class MetaProvider extends ContentProvider {
    private static final String TAG = "MetaProvider";
    public static final String AUTHORITY = "com.smarttvplus.iptvmtc.meta";
    private static final String FILE = "titles_meta.json";
    private static final int MAX_ENTRIES = 300;

    /** يُستدعى من الواجهة (app.js) عند كل تشغيل/فتح تفاصيل — يخزّن آخر ما عرفناه عن العمل */
    public static synchronized void put(Context ctx, String key, String metaJson) {
        if (ctx == null || key == null || metaJson == null) return;
        try {
            JSONObject all = read(ctx);
            all.put(key, new JSONObject(metaJson));
            // نُبقي آخر المداخل فقط كي لا يكبر الملف بلا حدّ
            if (all.length() > MAX_ENTRIES) {
                java.util.Iterator<String> it = all.keys();
                int drop = all.length() - MAX_ENTRIES;
                java.util.List<String> old = new java.util.ArrayList<>();
                while (it.hasNext() && old.size() < drop) old.add(it.next());
                for (String k : old) all.remove(k);
            }
            File f = new File(ctx.getFilesDir(), FILE);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(all.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "put failed", e);
        }
    }

    private static JSONObject read(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists()) return new JSONObject();
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < b.length) {
                    int r = in.read(b, off, b.length - off);
                    if (r <= 0) break;
                    off += r;
                }
            }
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        if (ctx == null) return null;
        String streamId = uri.getQueryParameter("streamId");
        if (streamId == null || streamId.length() == 0) streamId = idFromUrl(uri.getQueryParameter("url"));
        if (streamId == null || streamId.length() == 0) return null;

        JSONObject all = read(ctx);
        JSONObject meta = all.optJSONObject(streamId);
        if (meta == null) return null;
        MatrixCursor c = new MatrixCursor(new String[]{"json"});
        c.addRow(new Object[]{meta.toString()});
        return c;
    }

    /** رقم البث من رابط Xtream: …/movie/user/pass/2099842.mkv */
    public static String idFromUrl(String url) {
        if (url == null || url.length() == 0) return null;
        try {
            String last = Uri.parse(url).getLastPathSegment();
            if (last == null) return null;
            int dot = last.lastIndexOf('.');
            if (dot > 0) last = last.substring(0, dot);
            return last.matches("\\d+") ? last : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.smarttvplus.meta"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
