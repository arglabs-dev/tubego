package dev.arglabs.tubego;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

public final class SessionLifecycle {
    private static final int JOB=228231;
    private static synchronized Set<String> origins(Context context) {
        return new HashSet<>(context.getSharedPreferences("tubego_pending",Context.MODE_PRIVATE).getStringSet("origins",new HashSet<>()));
    }
    private static synchronized void track(Context context,String origin,boolean add) {
        Set<String> set=origins(context);if(add)set.add(origin);else set.remove(origin);
        if(!context.getSharedPreferences("tubego_pending",Context.MODE_PRIVATE).edit().putStringSet("origins",set).commit()) throw new IllegalStateException("No se pudo guardar la operación pendiente");
    }
    public static void logout(Context context,String origin) throws Exception {
        synchronized(SessionStore.class){
        SessionStore store=new SessionStore(context,origin);JSONObject session=store.read();if(session==null)return;
        TransferJobs.stop(context,origin);
        synchronized(SessionStore.class) {LocalLibraryStorage.wipe(context,origin,session.getString("user_id"),session.getString("device_id"));}
        JSONObject pending=store.pendingLogout();
        org.json.JSONArray sessions=pending==null?new org.json.JSONArray():pending.getJSONArray("sessions");
        boolean duplicate=false;for(int i=0;i<sessions.length();i++) if(sessions.getJSONObject(i).getString("device_id").equals(session.getString("device_id"))) duplicate=true;
        if(!duplicate) sessions.put(session);
        store.savePendingLogout(new JSONObject().put("sessions",sessions));track(context,origin,true);store.clear();schedule(context);
            }
}
    public static void remotelyRevoked(Context context,String origin,String token,String code) throws Exception {
        synchronized(SessionStore.class){
        if(token==null || (!code.equals("session_revoked")&&!code.equals("account_unavailable")))return;
        SessionStore store=new SessionStore(context,origin);JSONObject session=store.read();
        if(session==null || !session.getString("token").equals(token))return;
        TransferJobs.stop(context,origin);
        JSONObject previous=store.cleanup();
        org.json.JSONArray entries=previous==null?new org.json.JSONArray():previous.getJSONArray("devices");
        entries.put(new JSONObject().put("user_id",session.getString("user_id")).put("device_id",session.getString("device_id")));
        store.saveCleanup(new JSONObject().put("devices",entries));track(context,origin,true);store.clear();
        try {cleanup(context,origin,store);} finally {schedule(context);}
            }
}
    private static void cleanup(Context context,String origin,SessionStore store) throws Exception {
        synchronized(SessionStore.class){
        JSONObject pending=store.cleanup();if(pending==null)return;
        org.json.JSONArray entries=pending.getJSONArray("devices"),remaining=new org.json.JSONArray();
        for(int i=0;i<entries.length();i++) {
            JSONObject identity=entries.getJSONObject(i);
            try {synchronized(SessionStore.class){LocalLibraryStorage.wipe(context,origin,identity.getString("user_id"),identity.getString("device_id"));}}
            catch(Exception e) {remaining.put(identity);}
        }
        if(remaining.length()>0) {store.saveCleanup(new JSONObject().put("devices",remaining));throw new Exception("Limpieza local pendiente");}
        store.clearCleanup();
            }
}
    public static boolean flush(Context context) {
        boolean all=true;
        for(String origin:origins(context)) {
            try {
                SessionStore store=new SessionStore(context,origin);cleanup(context,origin,store);
                JSONObject pending=store.pendingLogout();
                if(pending!=null) {
                    org.json.JSONArray sessions=pending.getJSONArray("sessions");
                    org.json.JSONArray remaining=new org.json.JSONArray();
                    for(int i=0;i<sessions.length();i++) {
                        JSONObject session=sessions.getJSONObject(i);
                        try {new ApiClient(origin).request("POST","/account/session/logout",null,session.getString("token"));}
                        catch(ApiClient.ApiException e) {if(e.status!=401)remaining.put(session);}
                        catch(Exception e) {remaining.put(session);}
                    }
                    if(remaining.length()>0) {store.savePendingLogout(new JSONObject().put("sessions",remaining));all=false;continue;}
                    store.clearPendingLogout();
                }
                track(context,origin,false);
            } catch(Exception e) {all=false;}
        }
        return all;
    }
    public static void schedule(Context context) {
        if(origins(context).isEmpty()) return;
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        scheduler.schedule(new JobInfo.Builder(JOB,new ComponentName(context,LogoutJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }
}
