package dev.arglabs.tubego;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;

/** Session validation and offline-first progress. Network work never holds the session lock. */
public final class PlaybackProgress {
    private PlaybackProgress() {}
    static JSONObject session(Context context,String origin,String token) throws Exception {
        JSONObject value=new SessionStore(context,origin).read();
        if(value==null||!"approved".equals(value.optString("status"))||!token.equals(value.optString("token")))throw new SecurityException("Session changed");return value;
    }
    static PlaybackStore store(Context context,String origin,String token) throws Exception {
        JSONObject s=session(context,origin,token);return new PlaybackStore(LinkOutboxDispatch.root(context,origin,s));
    }
    public static JSONObject read(Context context,String origin,String token,String id) throws Exception {
        synchronized(SessionStore.class){return store(context,origin,token).read(id);}
    }
    public static void checkpoint(Context context,String origin,String token,String id,PlaybackTimeline timeline) throws Exception {
        if(timeline.duration<=0)return;
        synchronized(SessionStore.class){PlaybackStore store=store(context,origin,token);store.save(id,PlaybackState.checkpoint(timeline,store.read(id)),true);}
        TransferJobs.wake(context,origin,false);
    }
    public static void mark(Context context,String origin,String token,String id,boolean seen) throws Exception {
        synchronized(SessionStore.class){PlaybackStore store=store(context,origin,token);store.save(id,PlaybackState.mark(store.read(id),seen),true);}
        TransferJobs.wake(context,origin,false);
    }
    public static void sync(Context context,String origin,String token) throws Exception {
        PlaybackStore store; synchronized(SessionStore.class){store=store(context,origin,token);}
        ApiClient api=new ApiClient(origin);
        Exception pending=null;
        for(String id:store.ids()) {
            JSONObject snapshot;synchronized(SessionStore.class){session(context,origin,token);snapshot=store.read(id);}
            String revision=snapshot.optString("dirty");if(revision.isEmpty())continue;
            try {
                JSONObject response=api.request("PUT","/resources/"+id+"/playback",PlaybackState.wire(snapshot),token);
                synchronized(SessionStore.class){session(context,origin,token);store.reconcile(id,response,revision);}
            } catch(ApiClient.ApiException rejected) { if(rejected.status==401||rejected.status==403)throw rejected;pending=rejected; }
        }
        String cursor="";
        do {
            JSONObject page=api.request("GET","/playback?limit=100&after="+java.net.URLEncoder.encode(cursor,"UTF-8"),null,token);
            org.json.JSONArray items=page.getJSONArray("items");
            synchronized(SessionStore.class){session(context,origin,token);for(int i=0;i<items.length();i++){JSONObject row=items.getJSONObject(i);store.reconcile(row.getString("resource_id"),row,null);}}
            cursor=page.isNull("next_cursor")?"":page.getString("next_cursor");
        }while(!cursor.isEmpty());
        if(pending!=null)throw pending;
    }
    static String label(Context context,String status) {
        return Texts.text(context,status.equals("finished")?"Terminado":status.equals("in_progress")?"En progreso":"Sin empezar");
    }
}
