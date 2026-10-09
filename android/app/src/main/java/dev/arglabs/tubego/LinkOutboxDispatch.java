package dev.arglabs.tubego;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

public final class LinkOutboxDispatch {
    private static final int JOB=228233;
    private static synchronized Set<String> origins(Context context){return new HashSet<>(context.getSharedPreferences("tubego_outbox_jobs",Context.MODE_PRIVATE).getStringSet("origins",new HashSet<>()));}
    private static synchronized void track(Context context,String origin,boolean add){Set<String> all=origins(context);if(add)all.add(origin);else all.remove(origin);context.getSharedPreferences("tubego_outbox_jobs",Context.MODE_PRIVATE).edit().putStringSet("origins",all).commit();}
    public static File root(Context context,String origin,JSONObject session) throws Exception {return LocalLibraryStorage.root(context,origin,session.getString("user_id"),session.getString("device_id"));}
    public static LinkOutbox box(Context context,String origin,JSONObject session) throws Exception {return new LinkOutbox(new File(root(context,origin,session),"outbox"));}
    public static void schedule(Context context,String origin){CommandDispatch.schedule(context,origin);}
    public static void schedule(Context context){CommandDispatch.schedule(context);}
    public static boolean flush(Context context){return CommandDispatch.flush(context);}
}
