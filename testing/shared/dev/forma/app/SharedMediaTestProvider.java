package dev.forma.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** A remote provider serving generated tones; Java also works in the standalone test process. */
public class SharedMediaTestProvider extends ContentProvider {
    private volatile boolean blocked;
    private volatile String setupToken;
    private volatile boolean omitSize;
    @Override public boolean onCreate() { return true; }
    private synchronized File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (!"tone-one.wav".equals(name) && !"tone-two.wav".equals(name)) throw new IllegalArgumentException("Unknown test tone");
        File root = new File(getContext().getCacheDir(), "shared-test-tones");
        root.mkdirs();
        File file = new File(root, name);
        if (!file.exists()) {
            int samples = 16000;
            ByteBuffer data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
            data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
            for (int i = 0; i < samples; i++) data.putShort((short) (Math.sin(2 * Math.PI * 440 * i / 16000) * 6000));
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(data.array()); }
            catch (IOException error) { throw new IllegalStateException(error); }
        }
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (blocked || !"r".equals(mode)) throw new FileNotFoundException("The sender's access has ended");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File source = file(uri);
        String[] columns = projection == null ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = source.getName();
            if (OpenableColumns.SIZE.equals(columns[i]) && !omitSize) row[i] = source.length();
        }
        cursor.addRow(row);
        return cursor;
    }
    @Override public String getType(Uri uri) { return "audio/wav"; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("reset".equals(method)) blocked = false;
        if ("block".equals(method)) blocked = true;
        if ("setup".equals(method)) setupToken = arg;
        if ("omitSize".equals(method)) omitSize = true;
        if ("includeSize".equals(method)) omitSize = false;
        Bundle result = new Bundle();
        result.putString("setupToken", setupToken);
        return result;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
