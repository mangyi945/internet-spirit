package com.hljjs.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把「互联网精神与世长存」里保存过的文件暴露给输入法，
 * 让输入法可以直接把资料插入到任何 App 里。
 *
 * 目录：filesDir/shared/   由 MainActivity 在保存 JSON 时一并写入。
 * 查询：content://com.hljjs.app.shared  → 返回 name / size 两列。
 */
public class SharedProvider extends ContentProvider {

    public static final String AUTH = "com.hljjs.app.shared";
    public static final String COL_NAME = "name";
    public static final String COL_SIZE = "size";

    public static File dir(android.content.Context c) {
        File d = new File(c.getFilesDir(), "shared");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static Uri uriFor(String name) {
        return Uri.parse("content://" + AUTH + "/" + Uri.encode(name));
    }

    /** 输入法调用：列出所有共享文件 */
    public static Cursor listFiles(android.content.Context c) {
        MatrixCursor cur = new MatrixCursor(new String[]{ COL_NAME, COL_SIZE });
        File[] fs = dir(c).listFiles();
        if (fs == null) return cur;
        java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (File f : fs) {
            if (f.isFile()) cur.addRow(new Object[]{ f.getName(), f.length() });
        }
        return cur;
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        return listFiles(getContext());
    }

    @Override public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        String l = n == null ? "" : n.toLowerCase();
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".gif")) return "image/gif";
        if (l.endsWith(".mp3")) return "audio/mpeg";
        if (l.endsWith(".mp4")) return "video/mp4";
        if (l.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }

    @Override public int delete(Uri uri, String s, String[] a) { return 0; }

    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        File f = new File(dir(getContext()), name);
        if (!f.exists()) throw new FileNotFoundException(name);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
