package com.example.thinkv2.backup;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

/** Test APK process has no Kotlin runtime: this SAF fault fixture uses platform Java only. */
@android.annotation.TargetApi(26)
public final class BackupTestDocumentsProvider extends DocumentsProvider {
    private void event(String key) { SharedPreferences p=getContext().getSharedPreferences("synthetic-events",0);p.edit().putInt(key,p.getInt(key,0)+1).commit(); }
    private SharedPreferences documents() { return getContext().getSharedPreferences("synthetic-documents",0); }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns=projection!=null ? projection : new String[]{Root.COLUMN_ROOT_ID,Root.COLUMN_TITLE,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_FLAGS,Root.COLUMN_MIME_TYPES,Root.COLUMN_AVAILABLE_BYTES};
        Map<String,Object> values=new HashMap<>();
        values.put(Root.COLUMN_ROOT_ID,"fault");values.put(Root.COLUMN_TITLE,"合成空间不足");values.put(Root.COLUMN_DOCUMENT_ID,"root");
        values.put(Root.COLUMN_FLAGS,Root.FLAG_SUPPORTS_CREATE);values.put(Root.COLUMN_MIME_TYPES,"application/json");values.put(Root.COLUMN_AVAILABLE_BYTES,0L);
        MatrixCursor cursor=new MatrixCursor(columns);add(cursor,columns,values);return cursor;
    }
    private void add(MatrixCursor cursor,String[] columns,Map<String,Object> values) {
        Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++) row[i]=values.get(columns[i]);cursor.addRow(row);
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException { return rows(projection,Collections.singletonList(id)); }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sort) throws FileNotFoundException { return rows(projection,new ArrayList<>(documents().getAll().keySet())); }
    private Cursor rows(String[] projection,List<String> ids) throws FileNotFoundException {
        String[] columns=projection!=null ? projection : new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE};
        MatrixCursor cursor=new MatrixCursor(columns);
        for(String id:ids) {
            boolean root=id.equals("root");String name=root ? "合成空间不足" : documents().getString(id,null);
            if(name==null) throw new FileNotFoundException("missing synthetic document");
            Map<String,Object> values=new HashMap<>();values.put(Document.COLUMN_DOCUMENT_ID,id);values.put(Document.COLUMN_DISPLAY_NAME,name);
            values.put(Document.COLUMN_MIME_TYPE,root ? Document.MIME_TYPE_DIR : "application/json");
            values.put(Document.COLUMN_FLAGS,root ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE|Document.FLAG_SUPPORTS_RENAME|Document.FLAG_SUPPORTS_DELETE);
            values.put(Document.COLUMN_SIZE,0L);add(cursor,columns,values);
        }
        return cursor;
    }
    @Override public String createDocument(String parent,String mime,String name) throws FileNotFoundException {
        if(!parent.equals("root")) throw new FileNotFoundException("invalid synthetic parent");
        String id=UUID.randomUUID().toString();documents().edit().putString(id,name).commit();return id;
    }
    @Override public String renameDocument(String id,String name) { documents().edit().putString(id,name).commit();event("rename_same_id");return null; }
    @Override public void deleteDocument(String id) { documents().edit().remove(id).commit(); }
    @Override public boolean isChildDocument(String parent,String id) { return parent.equals("root") && documents().contains(id); }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        if(!documents().contains(id)) throw new FileNotFoundException("missing synthetic document");
        event("open");
        try {
            return getContext().getSystemService(StorageManager.class).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_WRITE,new ProxyFileDescriptorCallback() {
                @Override public long onGetSize() { return 0L; }
                @Override public int onRead(long offset,int size,byte[] data) { return 0; }
                @Override public int onWrite(long offset,int size,byte[] data) throws ErrnoException { event("ENOSPC");throw new ErrnoException("synthetic-space",OsConstants.ENOSPC); }
                @Override public void onFsync() throws ErrnoException { event("ENOSPC");throw new ErrnoException("synthetic-space",OsConstants.ENOSPC); }
                @Override public void onRelease() {}
            },new Handler(Looper.getMainLooper()));
        } catch(IOException e) { throw new FileNotFoundException("synthetic proxy unavailable"); }
    }
}
