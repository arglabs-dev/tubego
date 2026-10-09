package dev.arglabs.tubego;
import android.content.Context;
import java.io.File;
import org.json.JSONObject;

/** Compatibility facade; all effects use the account/device command sequencer. */
public final class RecoveryOutboxDispatch {
    public static File root(Context context,String origin,JSONObject session) throws Exception {return LocalLibraryStorage.root(context,origin,session.getString("user_id"),session.getString("device_id"));}
    public static RecoveryOutbox box(Context context,String origin,JSONObject session) throws Exception {return new RecoveryOutbox(new File(root(context,origin,session),"recovery-outbox"));}
    public static void schedule(Context context,String origin){CommandDispatch.schedule(context,origin);}
    public static void schedule(Context context){CommandDispatch.schedule(context);}
    public static boolean flush(Context context){return CommandDispatch.flush(context);}
}
