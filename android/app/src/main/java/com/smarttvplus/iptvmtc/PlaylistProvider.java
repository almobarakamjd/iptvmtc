package com.smarttvplus.iptvmtc;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** موفّر ملفات صغير بديل عن androidx FileProvider (بلا أي مكتبة خارجية) — همّه الوحيد
    تسليم ملف قائمة التشغيل M3U المؤقّت (من مجلد cache الخاص بالتطبيق) لتطبيق آخر
    (VLC/MX Player) عبر content:// حتى يقدر يقرأه رغم أنه خارج صندوقه الخاص. */
public class PlaylistProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = new File(getContext().getCacheDir(), uri.getLastPathSegment());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "audio/x-mpegurl"; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
