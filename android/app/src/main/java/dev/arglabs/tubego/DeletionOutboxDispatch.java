package dev.arglabs.tubego;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

public final class DeletionOutboxDispatch {
    private static final int JOB=228244;
    private static synchronized Set<String> origins(Context context){return new HashSet<>(context.getSharedPreferences("tubego_deletion_jobs",Context.MODE_PRIVATE).getStringSet("origins",new HashSet<>()));}
    private static synchronized void track(Context context,String origin,boolean add){Set<String> all=origins(context);if(add)all.add(origin);else all.remove(origin);context.getSharedPreferences("tubego_deletion_jobs",Context.MODE_PRIVATE).edit().putStringSet("origins",all).commit();}
    public static File root(Context context,String origin,JSONObject session) throws Exception {return LocalLibraryStorage.root(context,origin,session.getString("user_id"),session.getString("device_id"));}
    public static DeletionOutbox box(Context context,String origin,JSONObject session) throws Exception {return new DeletionOutbox(new File(root(context,origin,session),"deletion-outbox"));}
    public static void schedule(Context context,String origin){track(context,origin,true);schedule(context);}
    public static void schedule(Context context){if(origins(context).isEmpty())return;
        ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).schedule(new JobInfo.Builder(JOB,new ComponentName(context,DeletionOutboxJobService.class))
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
                    if(current==null||!token.equals(current.getString("token")))throw new DeletionOutbox.SessionGone();
                    TransferRuntime.stopOrigin(origin);
                    synchronized(SessionStore.class){if(!token.equals(store.token()))throw new DeletionOutbox.SessionGone();LocalResourceDeletion.apply(root(context,origin,session),entry.resource);}
                    JSONObject response=api.request("POST","/resources/"+entry.resource+"/delete",new JSONObject().put("scope",entry.scope).put("request_id",entry.id),token);
                    current=store.read();if(current==null||!token.equals(current.getString("token")))throw new DeletionOutbox.SessionGone();
                    TransferJobs.wake(context,origin,false);
                    return response.optString("resource_id",response.optString("id",""));
                }catch(DeletionOutbox.SessionGone e){throw e;}
                catch(Exception e){
                    JSONObject current=store.read();if(current==null||!token.equals(current.getString("token")))throw new DeletionOutbox.SessionGone();
                    if(e instanceof ApiClient.ApiException){int code=((ApiClient.ApiException)e).status;
                        if(code==401||code==403)throw new DeletionOutbox.SessionGone();
                        if(code>=400&&code<500&&code!=408&&code!=429)throw new DeletionOutbox.PermanentFailure("El servidor rechazó el borrado (HTTP "+code+"). Revisa tu cuenta y el recurso.");}
                    throw e;
                }
            },SessionStore.class,()->{try{return token.equals(store.token());}catch(Exception e){return false;}});
            if(done)track(context,origin,false);else complete=false;
        }catch(Exception e){complete=false;}
    }return complete;}
}
