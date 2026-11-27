package com.mina.expenses;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Hands the camera app a file in our cache to write an invoice photo into (no androidx needed). */
public class CamProvider extends ContentProvider {
    static final String AUTH = "com.mina.expenses.cam";

    static File dir(android.content.Context c) {
        File d = new File(c.getCacheDir(), "cam");
        d.mkdirs();
        return d;
    }

    private File file(Uri u) throws FileNotFoundException {
        String n = u.getLastPathSegment();
        if (n == null || !n.matches("[A-Za-z0-9_.-]+")) throw new FileNotFoundException();
        return new File(dir(getContext()), n);
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String getType(Uri uri) { return "image/jpeg"; }

    @Override
    public Cursor query(Uri uri, String[] proj, String sel, String[] args, String sort) {
        try {
            File f = file(uri);
            MatrixCursor c = new MatrixCursor(new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
            c.addRow(new Object[]{ f.getName(), f.length() });
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
