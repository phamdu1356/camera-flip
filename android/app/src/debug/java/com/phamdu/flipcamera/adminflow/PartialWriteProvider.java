package com.phamdu.flipcamera.adminflow;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Debug-only provider fixture that leaves a partial target, then rejects the output stream. */
public final class PartialWriteProvider extends ContentProvider {
    public static final String PARTIAL_FILE = "partial-export.bin";
    public static final String OPEN_MARKER = "partial-export-opened";
    public static final String DELETE_MARKER = "partial-export-deleted";
    public static final String PARTIAL_WRITTEN_MARKER = "partial-export-written";
    private static final String TAG = "PartialWriteProvider";

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return removeDestination() ? 1 : 0;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("android:deleteDocument".equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean("result", removeDestination());
            return result;
        }
        return super.call(method, arg, extras);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        final File cache = contextOrThrow().getCacheDir();
        Log.i(TAG, "openFile called for " + uri + " mode=" + mode);
        touch(new File(cache, OPEN_MARKER));
        try {
            try (FileOutputStream partial = new FileOutputStream(new File(cache, PARTIAL_FILE))) {
                partial.write(new byte[] {1, 2, 3, 4});
            }
        } catch (IOException failure) {
            FileNotFoundException wrapped = new FileNotFoundException(failure.getMessage());
            wrapped.initCause(failure);
            throw wrapped;
        }
        touch(new File(cache, PARTIAL_WRITTEN_MARKER));
        throw new FileNotFoundException("Injected failure after partial destination was created");
    }

    private android.content.Context contextOrThrow() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider is not attached");
        return context;
    }

    private boolean removeDestination() {
        File cache = contextOrThrow().getCacheDir();
        Log.i(TAG, "delete called");
        touch(new File(cache, DELETE_MARKER));
        File partial = new File(cache, PARTIAL_FILE);
        return !partial.exists() || partial.delete();
    }

    private static void touch(File file) {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(1);
        } catch (IOException ignored) {
            // A missing marker causes the instrumentation assertion to fail.
        }
    }
}
