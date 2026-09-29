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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Instrumentation-only writable documents. All paths resolve to disposable generated fixture files. */
public class WritableExportTestProvider extends ContentProvider {
    private int writes;
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) {
        String name=uri.getLastPathSegment();
        if (!"original.wav".equals(name) && !"alias.wav".equals(name) && !"empty.wav".equals(name) && !"unreadable.wav".equals(name))
            throw new IllegalArgumentException("Unknown disposable document");
        File root=new File(getContext().getCacheDir(),"writable-export-test");root.mkdirs();
        File file=new File(root,"alias.wav".equals(name)?"original.wav":name);
        if (!file.exists()) {
            try (FileOutputStream output=new FileOutputStream(file)) {
                if ("original.wav".equals(file.getName())) {
                    int samples=16000;
                    ByteBuffer data=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);
                    data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+samples*2)
                        .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                        .putShort((short)1).putShort((short)1).putInt(16000).putInt(32000)
                        .putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples*2);
                    for(int i=0;i<samples;i++) data.putShort((short)(Math.sin(2*Math.PI*440*i/16000)*6000));
                    output.write(data.array());
                }
            } catch(Exception error) { throw new IllegalStateException(error); }
        }
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if("r".equals(mode) && "unreadable.wav".equals(uri.getLastPathSegment())) throw new FileNotFoundException("No readable empty-state evidence");
        if(!"r".equals(mode)) writes++;
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
        File file=file(uri);
        String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++) {
            if(OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i]=file.getName();
            if(OpenableColumns.SIZE.equals(columns[i])) row[i]=file.length();
        }
        cursor.addRow(row);return cursor;
    }
    @Override public String getType(Uri uri) { return "audio/wav"; }
    @Override public Bundle call(String method,String argument,Bundle extras) {
        File root=new File(getContext().getCacheDir(),"writable-export-test");
        if("reset".equals(method)) { File[] files=root.listFiles();if(files!=null) for(File file:files) file.delete();writes=0; }
        Bundle result=new Bundle();result.putInt("writes",writes);return result;
    }
    @Override public Uri insert(Uri uri,ContentValues values) { return null; }
    @Override public int delete(Uri uri,String selection,String[] args) { return file(uri).delete()?1:0; }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { return 0; }
}
