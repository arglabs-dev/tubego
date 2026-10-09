package dev.arglabs.tubego;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.system.*;
import org.json.JSONObject;
import java.io.*;
import java.security.SecureRandom;
import java.util.*;

/** Read grants name one verified local file; every proxy read rechecks account ownership. */
public final class PrivateMediaContentProvider extends ContentProvider {
    public static final String AUTHORITY="dev.arglabs.tubego.private-media";
    private static final Map<String,Grant> grants=new LinkedHashMap<>();
    private static final class Grant {
        final String origin,user,device,id,sha,title,mime,sessionToken;final long size;
        Grant(String origin,JSONObject session,TransferRecord r) throws Exception {
            this.origin=origin;sessionToken=session.getString("token");user=session.getString("user_id");device=session.getString("device_id");id=r.id;
            sha=r.sha256;title=r.title;mime=OfflineMediaAccess.mime(r);size=r.size;
        }
    }
    public static Uri create(Context context,String origin,String id) throws Exception {
        JSONObject session; TransferRecord record;
        synchronized(SessionStore.class){
            session=approved(context,origin);
            record=OfflineMediaAccess.record(LocalLibraryStorage.root(context,origin,session.getString("user_id"),session.getString("device_id")),id);
        }
        OfflineMediaAccess.verify(record); // Hashing does not block session cleanup.
        synchronized(SessionStore.class){
            Grant grant=new Grant(origin,session,record);validate(context,grant);
            byte[] random=new byte[32];new SecureRandom().nextBytes(random);
            StringBuilder token=new StringBuilder();for(byte b:random)token.append(String.format(Locale.ROOT,"%02x",b&255));
            synchronized(grants){if(grants.size()>=128)grants.remove(grants.keySet().iterator().next());grants.put(token.toString(),grant);}
            return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("media").appendPath(token.toString()).build();
        }
    }
    private static JSONObject approved(Context context,String origin) throws Exception {
        JSONObject session=new SessionStore(context,origin).read();
        if(session==null || !"approved".equals(session.optString("status")) || session.optString("token").isEmpty())
            throw new SecurityException("Sesión no disponible");
        return session;
    }
    private static TransferRecord validate(Context context,Grant grant) throws Exception {
        JSONObject session=approved(context,grant.origin);
        if(!grant.sessionToken.equals(session.optString("token")) || !grant.user.equals(session.optString("user_id")) || !grant.device.equals(session.optString("device_id")))
            throw new SecurityException("La cuenta de esta biblioteca cambió");
        TransferRecord r=OfflineMediaAccess.record(LocalLibraryStorage.root(context,grant.origin,grant.user,grant.device),grant.id);
        if(!r.sha256.equals(grant.sha) || r.size!=grant.size || !OfflineMediaAccess.available(r))
            throw new SecurityException("Archivo no disponible");
        return r;
    }
    private Grant grant(Uri uri) {
        if(!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()) || uri.getQuery()!=null || uri.getFragment()!=null
            || uri.getPathSegments().size()!=2 || !"media".equals(uri.getPathSegments().get(0)))throw new SecurityException("URI inválido");
        String token=uri.getPathSegments().get(1);if(!token.matches("[0-9a-f]{64}"))throw new SecurityException("URI inválido");
        synchronized(grants){Grant grant=grants.get(token);if(grant==null)throw new SecurityException("Permiso no disponible; abre el archivo otra vez desde Tubego");return grant;}
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){Grant g=grant(uri);try{synchronized(SessionStore.class){validate(getContext(),g);}}catch(Exception e){throw new SecurityException("Archivo no disponible",e);}return g.mime;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        Grant g=grant(uri);getType(uri);
        String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))row[i]=g.title+(g.mime.startsWith("audio")?".mp3":".mp4");else if(OpenableColumns.SIZE.equals(columns[i]))row[i]=g.size;}
        cursor.addRow(row);return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new SecurityException("Solo lectura");
        Grant g=grant(uri);final RandomAccessFile file;final RandomAccessFile[] opened=new RandomAccessFile[1];final HandlerThread thread=new HandlerThread("Tubego local reader");
        try {
            TransferRecord r; synchronized(SessionStore.class){r=validate(getContext(),g);}
            OfflineMediaAccess.verify(r);
            synchronized(SessionStore.class){r=validate(getContext(),g);file=new RandomAccessFile(r.media(),"r");opened[0]=file;}
            thread.start();
            return getContext().getSystemService(StorageManager.class).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,new ProxyFileDescriptorCallback(){
                @Override public long onGetSize() throws ErrnoException {synchronized(SessionStore.class){check();return g.size;}}
                private void check() throws ErrnoException {try{validate(getContext(),g);}catch(Exception e){throw new ErrnoException("media revoked",OsConstants.EACCES);}}
                @Override public int onRead(long offset,int size,byte[] data) throws ErrnoException {
                    synchronized(SessionStore.class){check();try {file.seek(offset);int count=file.read(data,0,(int)Math.min(size,Math.max(0,g.size-offset)));return Math.max(0,count);}catch(IOException e){throw new ErrnoException("read",OsConstants.EIO);}}
                }
                @Override public void onRelease(){try{file.close();}catch(IOException ignored){}thread.quitSafely();}
            },new Handler(thread.getLooper()));
        } catch(Exception e){if(opened[0]!=null)try{opened[0].close();}catch(IOException ignored){}thread.quitSafely();FileNotFoundException failure=new FileNotFoundException("Archivo local no disponible");failure.initCause(e);throw failure;}
    }
    @Override public Uri insert(Uri u,ContentValues v){throw new SecurityException("Solo lectura");}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new SecurityException("Solo lectura");}
    @Override public int delete(Uri u,String s,String[] a){throw new SecurityException("Solo lectura");}
}
