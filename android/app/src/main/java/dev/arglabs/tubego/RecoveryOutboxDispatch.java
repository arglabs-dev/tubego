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
    public static void schedule(Context context,String origin){CommandDispatch.schedule(context,origin);}
    public static void schedule(Context context){CommandDispatch.schedule(context);}
    public static boolean flush(Context context){return CommandDispatch.flush(context);}
}
