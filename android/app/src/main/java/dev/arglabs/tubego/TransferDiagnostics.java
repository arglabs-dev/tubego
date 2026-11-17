package dev.arglabs.tubego;

import android.content.Context;
import org.json.JSONObject;

/** Account-scoped last attempt, with protocol codes only. Never exception messages or credentials. */
final class TransferDiagnostics {
    static void finish(Context context,String origin,TransferRuntime.Control control,TransferDriver.Outcome outcome,Exception error){
        try{synchronized(SessionStore.class){
            JSONObject session=new SessionStore(context,origin).read();if(session==null||!session.optString("token").equals(control.sessionToken))return;
            String code=error==null?"":error instanceof ApiClient.ApiException?"http_"+((ApiClient.ApiException)error).status:error instanceof org.json.JSONException?"invalid_sync_response":error instanceof java.io.IOException?"local_or_network_io":"transfer_start_failed";
            JSONObject value=new JSONObject().put("at",java.time.Instant.now().toString()).put("phase",control.phase).put("outcome",outcome.name()).put("error_code",code);
            context.getSharedPreferences("tubego_transfer_diagnostics",Context.MODE_PRIVATE).edit().putString(TransferKey.accountPrefix(origin,session.getString("user_id"),session.getString("device_id"))+"last_attempt",value.toString()).commit();
        }}catch(Exception ignored){}
    }
    static JSONObject read(Context context,String origin)throws Exception{
        synchronized(SessionStore.class){
            JSONObject session=new SessionStore(context,origin).read();if(session==null)return null;
            String saved=context.getSharedPreferences("tubego_transfer_diagnostics",Context.MODE_PRIVATE).getString(TransferKey.accountPrefix(origin,session.getString("user_id"),session.getString("device_id"))+"last_attempt",null);
            return saved==null?null:new JSONObject(saved);
        }
    }
}
