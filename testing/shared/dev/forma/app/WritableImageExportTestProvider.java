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

/** Instrumentation-only writable documents. All paths resolve to disposable generated fixture files. */
public class WritableImageExportTestProvider extends ContentProvider {
    private int writes;
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) {
        String name=uri.getLastPathSegment();
        if (!"original.png".equals(name) && !"alias.png".equals(name) && !"empty.png".equals(name) && !"unreadable.png".equals(name))
            throw new IllegalArgumentException("Unknown disposable document");
        File root=new File(getContext().getCacheDir(),"writable-image-export-test");root.mkdirs();
        File file=new File(root,"alias.png".equals(name)?"original.png":name);
        if (!file.exists()) {
            try (FileOutputStream output=new FileOutputStream(file)) {
                if ("original.png".equals(file.getName())) throw new IllegalStateException("Seed the generated PNG first");
            } catch(Exception error) { throw new IllegalStateException(error); }
        }
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if("r".equals(mode) && "unreadable.png".equals(uri.getLastPathSegment())) throw new FileNotFoundException("No readable empty-state evidence");
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
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Bundle call(String method,String argument,Bundle extras) {
        File root=new File(getContext().getCacheDir(),"writable-image-export-test");
        if("reset".equals(method)) { File[] files=root.listFiles();if(files!=null) for(File file:files) file.delete();writes=0; }
        if("seed".equals(method)) {
            root.mkdirs();byte[] data=extras.getByteArray("png");
            try(FileOutputStream output=new FileOutputStream(new File(root,"original.png"))) { output.write(data); }
            catch(Exception error) { throw new IllegalStateException(error); }
        }
        Bundle result=new Bundle();result.putInt("writes",writes);return result;
    }
    @Override public Uri insert(Uri uri,ContentValues values) { return null; }
    @Override public int delete(Uri uri,String selection,String[] args) { return file(uri).delete()?1:0; }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { return 0; }
}
