package dev.arglabs.tubego;

import android.content.Context;
import android.net.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Drives durable server snapshots and transfers; all media sockets bind to one network. */
public final class TransferDriver {
    private TransferDriver() {}
    public enum Outcome {DONE,WAIT_WIFI,WAIT_NETWORK,WAIT_SPACE,RETRY,STOPPED}
    public static Outcome run(Context context,String origin,TransferRuntime.Control control) throws Exception {
        synchronized(TransferRuntime.lock(origin)) {
            TransferRuntime.register(origin,control);
            try {return execute(context,origin,control);}finally {TransferRuntime.unregister(origin,control);}
        }
    }
    private static Outcome execute(Context context,String origin,TransferRuntime.Control control) throws Exception {
        SessionLifecycle.flush(context);
        SessionStore store=new SessionStore(context,origin);JSONObject session=store.read();
        if(session==null || control.stopped || !"approved".equals(session.optString("status"))) return Outcome.STOPPED;
        String token=session.getString("token"),user=session.getString("user_id"),device=session.getString("device_id");
        ApiClient api=new ApiClient(origin);
        // Validate authorization before accessing managed files, even with a persisted queue.
        JSONObject account=api.request("GET","/account/status",null,token);
        if(!"approved".equals(account.optString("status"))) return Outcome.STOPPED;
        File root=LocalLibraryStorage.root(context,origin,user,device);
        synchronized(SessionStore.class) {
            if(!sameSession(store,token,control))return Outcome.STOPPED;
            if(!root.isDirectory() && !root.mkdirs()) throw new IOException("No se pudo crear la biblioteca");
        }
        String eventKey=TransferKey.accountPrefix(origin,user,device)+"event_cursor";
        var preferences=context.getSharedPreferences("tubego_transfer_sync",Context.MODE_PRIVATE);
        long eventCursor=preferences.getLong(eventKey,0);String cursor="";List<JSONObject> rows=new ArrayList<>(),events=new ArrayList<>();
        boolean eventsDone=false,deliveriesDone=false;
        while(!eventsDone || !deliveriesDone) {
            if(control.stopped) return Outcome.STOPPED;
            JSONObject snapshot=api.request("GET","/device/sync?limit=25&event_cursor="+eventCursor+"&delivery_cursor="+java.net.URLEncoder.encode(cursor,"UTF-8"),null,token);
            JSONArray items=snapshot.getJSONArray("deliveries"),newEvents=snapshot.getJSONArray("events");
            if(!deliveriesDone) for(int i=0;i<items.length();i++) rows.add(items.getJSONObject(i));
            for(int i=0;i<newEvents.length();i++) events.add(newEvents.getJSONObject(i));
            eventCursor=snapshot.getLong("event_cursor");eventsDone=!snapshot.getBoolean("has_more_events");
            if(!deliveriesDone) {
                deliveriesDone=snapshot.isNull("next_delivery_cursor");
                cursor=deliveriesDone?"\uffff":snapshot.getString("next_delivery_cursor");
            }
        }
        DownloadPermissions grants=AndroidDownloadPermissions.create(context);
        // Process wipe instructions before refreshing/claiming any downloadable item.
        synchronized(SessionStore.class) {
        if(!sameSession(store,token,control))return Outcome.STOPPED;
        for(JSONObject event:events) {
            String kind=event.optString("kind");JSONObject payload=event.optJSONObject("payload");
            if(kind.equals("wipe_device")) {control.stop();LocalLibraryStorage.wipe(context,origin,user,device);store.clear();return Outcome.STOPPED;}
            if(kind.equals("resource.deleted") && payload!=null && payload.has("resource_id")) {
                String deletedId=UUID.fromString(payload.getString("resource_id")).toString();
                // An older event may remain unread after an explicit approved
                // re-download. Current delivery snapshots win over stale events.
                for(JSONObject row:rows)if(UUID.fromString(row.getString("id")).toString().equals(deletedId)
                        && (!row.isNull("local_deleted_at") || row.optString("delivery_status").equals("approval_required"))) {
                    LocalResourceDeletion.apply(root,deletedId);break;
                }
            }
            if((kind.equals("delete_local") || kind.equals("local_deleted")) && payload!=null && payload.has("resource_id")) removeRecord(root,payload.getString("resource_id"));
        }
        }
        List<TransferRecord> queue=new ArrayList<>(),missing=new ArrayList<>();
        synchronized(SessionStore.class) {
        if(!sameSession(store,token,control))return Outcome.STOPPED;
        for(JSONObject row:rows) {
            String id=UUID.fromString(row.getString("id")).toString();
            File manifest=new File(root,id+".properties");
            TransferRecord record=manifest.isFile()?TransferRecord.read(manifest):null;
            if(!row.isNull("local_deleted_at") || row.optString("delivery_status").equals("approval_required")) {
                if(record!=null)grants.revoke(key(origin,user,device,record));
                LocalResourceDeletion.apply(root,id);
                continue;
            }
            if(LocalResourceDeletion.deleted(root,id))continue;
            if(record!=null && "complete".equals(row.optString("delivery_status")) && record.media().isFile()
                && record.sha256.equals(row.optString("sha256")) && record.size==row.optLong("size_bytes",-1)){
                // The confirmation response was lost, but the durable snapshot
                // acknowledges success. Keep the verified file and clear its budget.
                if(record.failures>0)TransferRetry.reset(record);
                grants.revoke(key(origin,user,device,record));
            }
            if(!row.optBoolean("server_available")) {
                if(record!=null && !record.media().isFile()) {record.state="unavailable";record.message="El archivo ya no está en el servidor. Solicítalo nuevamente.";record.save();}
                continue;
            }
            if(row.isNull("sha256") || row.isNull("size_bytes")) continue;
            String digest=row.getString("sha256");long size=row.getLong("size_bytes");
            if(record==null) record=new TransferRecord(root,id,digest,size,row.optString("title","Archivo"),row.optString("created_at",""));
            else record.reconcile(digest,size);
            record.mediaFormat=row.optString("media_format","video");
            if(record.state.equals("cancelled") || record.state.equals("deleted")) continue;
            if(row.optString("delivery_status").equals("complete") && !record.media().isFile()) {
                record.state="missing";record.message="El archivo no está en el teléfono. Solicita la descarga explícitamente.";record.save();missing.add(record);continue;
            }
            record.save();queue.add(record);
        }
        }
        // A lost file without a deliberate-deletion tombstone can restore its delivery.
        // Control traffic stays outside the shared storage/session lock.
        for(TransferRecord record:missing) {
            if(control.stopped)return Outcome.STOPPED;
            JSONObject restored=api.request("POST","/resources/"+record.id+"/deliveries/request",new JSONObject().put("restore_missing",true),token);
            if("pending".equals(restored.optString("status"))) {
                synchronized(SessionStore.class) {
                    if(!sameSession(store,token,control))return Outcome.STOPPED;
                    record.state="pending";record.message="Restaurando un archivo local que falta.";record.save();queue.add(record);
                }
            }
        }
        // Advance the cursor only after durable local processing of the entire snapshot.
        if(!preferences.edit().putLong(eventKey,eventCursor).commit()) throw new IOException("No se pudo guardar sincronización");
        queue.sort(Comparator.comparing((TransferRecord r)->r.createdAt).thenComparing(r->r.id));
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        AndroidStorageGuard storage=new AndroidStorageGuard(context,origin,user,device);
        boolean waitWifi=false,waitAny=false,waitSpace=false,retryPending=false;
        for(TransferRecord record:queue) {
            if(control.stopped) return Outcome.STOPPED;
            TransferKey key=key(origin,user,device,record);
            if(record.state.equals("failed"))continue;
            if(record.nextRetryAt>System.currentTimeMillis()){
                retryPending=true;control.retryAt=control.retryAt==0?record.nextRetryAt:Math.min(control.retryAt,record.nextRetryAt);continue;
            }
            if(record.media().isFile() && ResumableTransfer.verifies(record.media(),record.size,record.sha256)) {
                if(!controlNetworkAvailable(manager)){waitAny=true;continue;}
                try{confirm(api,token,record);synchronized(SessionStore.class){if(!sameSession(store,token,control))return Outcome.STOPPED;TransferRetry.reset(record);}grants.revoke(key);}
                catch(Exception e){if(Thread.currentThread().isInterrupted()||control.stopped)return Outcome.STOPPED;if(!controlNetworkAvailable(manager)){waitAny=true;continue;}synchronized(SessionStore.class){if(!sameSession(store,token,control))return Outcome.STOPPED;TransferRetry.failed(record,e,System.currentTimeMillis());}if(record.nextRetryAt>0){retryPending=true;control.retryAt=control.retryAt==0?record.nextRetryAt:Math.min(control.retryAt,record.nextRetryAt);}}
                continue;
            }
            if(!storage.allows(Math.max(0,record.size-record.offset()))){
                record.state="paused";record.pauseReason="device_storage";record.message="Libera espacio para continuar. Se conserva el parcial.";
                synchronized(SessionStore.class){if(!sameSession(store,token,control))return Outcome.STOPPED;record.save();}waitSpace=true;continue;
            }
            record.pauseReason="";
            Network selected=manager.getActiveNetwork();
            if(!permitted(manager,selected,grants,key,control)) {record.state="paused";record.message="Esperando Wi-Fi o una red autorizada para este archivo.";synchronized(SessionStore.class){if(!sameSession(store,token,control))return Outcome.STOPPED;record.save();}if(grants.authorized(key))waitAny=true;else waitWifi=true;continue;}
            control.transfer=key;
            try(NetworkMonitor monitor=new NetworkMonitor(context,state->{
                if(control.connection!=null && !permitted(manager,selected,grants,key,control)) control.connection.disconnect();
            })) {
                ResumableTransfer.Result result=ResumableTransfer.run(record,(offset,etag)->{
                    if(!permitted(manager,selected,grants,key,control)) throw new IOException("Red permitida no disponible");
                    URL url=URI.create(origin+"/api/v1/resources/"+record.id+"/download").toURL();
                    HttpsURLConnection connection=(HttpsURLConnection)selected.openConnection(url);
                    control.connection=connection;
                    connection.setConnectTimeout(10000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("Accept-Encoding","identity");
                    if(offset>0){connection.setRequestProperty("Range","bytes="+offset+"-");connection.setRequestProperty("If-Range",etag);}
                    int status=connection.getResponseCode();
                    if(status==401 || status==403) {
                        connection.disconnect();control.connection=null;
                        // Control request uses the shared observer to revoke/wipe sessions.
                        api.request("GET","/account/status",null,token);
                        throw new ApiClient.ApiException(status,"download_rejected");
                    }
                    return new ResumableTransfer.Response() {
                        public int status(){return status;}
                        public String header(String name){return connection.getHeaderField(name);}
                        public InputStream body() throws IOException{return connection.getInputStream();}
                        public void close(){connection.disconnect();control.connection=null;}
                    };
                },()->permitted(manager,selected,grants,key,control) && !new File(root,record.id+".deleted").exists(),SessionStore.class,()->sameSession(store,token,control) && !new File(root,record.id+".deleted").exists(),storage);
                if(result==ResumableTransfer.Result.PAUSED) {if(grants.authorized(key))waitAny=true;else waitWifi=true;continue;}
                confirm(api,token,record);synchronized(SessionStore.class){if(!sameSession(store,token,control))return Outcome.STOPPED;TransferRetry.reset(record);}grants.revoke(key);
            } catch(Exception e) {
                if(control.stopped) return Outcome.STOPPED;
                if(!permitted(manager,selected,grants,key,control) || Thread.currentThread().isInterrupted()){
                    record.state="paused";synchronized(SessionStore.class){if(!sameSession(store,token,control))return Outcome.STOPPED;record.save();}
                    if(grants.authorized(key))waitAny=true;else waitWifi=true;continue;
                }
                if(e instanceof ResumableTransfer.StoragePause || ResumableTransfer.isNoSpace(e)){
                    record.state="paused";record.pauseReason="device_storage";record.message="Libera espacio para continuar.";if(ResumableTransfer.isNoSpace(e))storage.noSpace();waitSpace=true;synchronized(SessionStore.class){if(!sameSession(store,token,control))return Outcome.STOPPED;record.save();}continue;
                }
                synchronized(SessionStore.class){if(!sameSession(store,token,control))return Outcome.STOPPED;TransferRetry.failed(record,e,System.currentTimeMillis());}
                if(record.nextRetryAt>0){retryPending=true;control.retryAt=control.retryAt==0?record.nextRetryAt:Math.min(control.retryAt,record.nextRetryAt);}

            } finally {control.transfer=null;if(control.connection!=null){control.connection.disconnect();control.connection=null;}}
        }
        if(!waitSpace)storage.recovered();
        return control.stopped?Outcome.STOPPED:waitAny?Outcome.WAIT_NETWORK:waitWifi?Outcome.WAIT_WIFI:waitSpace?Outcome.WAIT_SPACE:retryPending?Outcome.RETRY:Outcome.DONE;
    }
    private static boolean sameSession(SessionStore store,String token,TransferRuntime.Control control) {
        if(control.stopped)return false;
        try {return token.equals(store.token());}catch(Exception e){return false;}
    }
    private static void confirm(ApiClient api,String token,TransferRecord record) throws Exception {
        api.request("POST","/resources/"+record.id+"/deliveries/confirm",new JSONObject().put("size_bytes",record.size).put("sha256",record.sha256),token);
    }
    private static TransferKey key(String origin,String user,String device,TransferRecord record){return new TransferKey(origin,user,device,record.id,record.sha256);}
    private static boolean controlNetworkAvailable(ConnectivityManager manager){
        Network network=manager.getActiveNetwork();NetworkCapabilities caps=network==null?null:manager.getNetworkCapabilities(network);
        return caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    private static boolean permitted(ConnectivityManager manager,Network selected,DownloadPermissions grants,TransferKey key,TransferRuntime.Control control) {
        if(control.stopped || selected==null || !selected.equals(manager.getActiveNetwork()))return false;
        NetworkCapabilities caps=manager.getNetworkCapabilities(selected);
        if(caps==null)return false;
        DownloadPolicy.NetworkState state=new DownloadPolicy.NetworkState(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        return DownloadPolicy.allowsMedia(state,grants.authorized(key));
    }
    private static void removeRecord(File root,String resource) throws Exception {
        String id=UUID.fromString(resource).toString();
        try(FileOutputStream tombstone=new FileOutputStream(new File(root,id+".deleted"))){tombstone.write(1);tombstone.getFD().sync();}
        for(String suffix:new String[]{".part",".media"}) {File file=new File(root,id+suffix);if(file.exists()&&!file.delete())throw new IOException("No se pudo borrar archivo local");}
        File manifest=new File(root,id+".properties");
        if(manifest.isFile()){TransferRecord r=TransferRecord.read(manifest);r.state="deleted";r.save();}
    }
}
