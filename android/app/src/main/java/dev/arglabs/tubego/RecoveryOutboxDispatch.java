package dev.arglabs.tubego;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

public final class RecoveryOutboxDispatch {
    private static final int JOB=228243;
    private static synchronized Set<String> origins(Context context){return new HashSet<>(context.getSharedPreferences("tubego_recovery_jobs",Context.MODE_PRIVATE).getStringSet("origins",new HashSet<>()));}
    private static synchronized void track(Context context,String origin,boolean add){Set<String> all=origins(context);if(add)all.add(origin);else all.remove(origin);context.getSharedPreferences("tubego_recovery_jobs",Context.MODE_PRIVATE).edit().putStringSet("origins",all).commit();}
    public static File root(Context context,String origin,JSONObject session) throws Exception {return LocalLibraryStorage.root(context,origin,session.getString("user_id"),session.getString("device_id"));}
    public static RecoveryOutbox box(Context context,String origin,JSONObject session) throws Exception {return new RecoveryOutbox(new File(root(context,origin,session),"recovery-outbox"));}
    public static void schedule(Context context,String origin){track(context,origin,true);schedule(context);}
    public static void schedule(Context context){if(origins(context).isEmpty())return;
        ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).schedule(new JobInfo.Builder(JOB,new ComponentName(context,RecoveryOutboxJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());}
    public static boolean flush(Context context){boolean complete=true;for(String origin:origins(context)){
        try{
            SessionStore store=new SessionStore(context,origin);JSONObject session=store.read();
            if(session==null){track(context,origin,false);continue;}
            String token=session.getString("token");ApiClient api=new ApiClient(origin);
            JSONObject status=api.request("GET","/account/status",null,token);
            if(!"approved".equals(status.getString("status"))){complete=false;continue;}
            boolean done=box(context,origin,session).flush(entry->{
                try{
                    JSONObject current=store.read();
                    if(current==null||!token.equals(current.getString("token")))throw new RecoveryOutbox.SessionGone();
                    // Send earlier offline deletions before approving a new generation.
                    DeletionOutboxDispatch.flush(context);
                    for(DeletionOutbox.Entry deletion:DeletionOutboxDispatch.box(context,origin,session).entries())
                        if(!deletion.state.equals("submitted"))throw new java.io.IOException("Borrados anteriores pendientes");
                    TransferRuntime.stopOrigin(origin);
                    java.io.File privateRoot=root(context,origin,session);String marker;
                    synchronized(SessionStore.class){if(!token.equals(store.token()))throw new RecoveryOutbox.SessionGone();marker=LocalResourceDeletion.stamp(privateRoot,entry.resource);}
                    JSONObject response=api.request("POST","/resources/"+entry.resource+"/request-again",new JSONObject().put("approve_redownload",entry.scope.equals("approved")).put("request_id",entry.id),token);
                    synchronized(SessionStore.class){
                        if(!token.equals(store.token()))throw new RecoveryOutbox.SessionGone();
                        // A newer local delete intent wins over this older response.
                        if(marker.equals(LocalResourceDeletion.stamp(privateRoot,entry.resource))) {
                            if(entry.scope.equals("approved"))LocalResourceDeletion.approve(privateRoot,entry.resource);
                            java.io.File manifest=new java.io.File(privateRoot,java.util.UUID.fromString(entry.resource).toString()+".properties");
                            if(manifest.isFile()) {TransferRecord record=TransferRecord.read(manifest);if(record.state.equals("deleted")||record.state.equals("cancelled")||record.state.equals("unavailable")||record.state.equals("missing")) {record.state="pending";record.message="Solicitud aprobada. Esperando disponibilidad y red permitida.";record.save();}}
                        }
                    }
                    TransferJobs.wake(context,origin,false);
                    return response.optString("resource_id",response.optString("id",""));
                }catch(RecoveryOutbox.SessionGone e){throw e;}
                catch(Exception e){
                    JSONObject current=store.read();if(current==null||!token.equals(current.getString("token")))throw new RecoveryOutbox.SessionGone();
                    if(e instanceof ApiClient.ApiException){int code=((ApiClient.ApiException)e).status;
                        if(code==401||code==403)throw new RecoveryOutbox.SessionGone();
                        if(code==409&&"cleanup_pending".equals(((ApiClient.ApiException)e).code))throw e;
                        if(code>=400&&code<500&&code!=408&&code!=429)throw new RecoveryOutbox.PermanentFailure("El servidor rechazó la solicitud (HTTP "+code+"). Revisa tu cuenta y el recurso.");}
                    throw e;
                }
            },SessionStore.class,()->{try{return token.equals(store.token());}catch(Exception e){return false;}});
            if(done)track(context,origin,false);else complete=false;
        }catch(Exception e){complete=false;}
    }return complete;}
}
