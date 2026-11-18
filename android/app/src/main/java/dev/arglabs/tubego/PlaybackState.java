package dev.arglabs.tubego;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Same registers and conservative interval union as the server. No maximum-position rule. */
public final class PlaybackState {
    private static final String ZERO="00000000-0000-0000-0000-000000000000";
    private PlaybackState() {}
    public static JSONObject empty() throws Exception {
        return new JSONObject().put("position_ms",0).put("duration_ms",0).put("position_at_ms",0).put("position_id",ZERO)
                .put("ranges",new JSONArray()).put("ended",false).put("manual_seen",JSONObject.NULL)
                .put("manual_at_ms",0).put("manual_id",ZERO);
    }
    public static List<long[]> ranges(JSONObject state) throws Exception {
        List<long[]> result=new ArrayList<>();JSONArray data=state.optJSONArray("ranges");
        if(data!=null)for(int i=0;i<data.length();i++){JSONArray r=data.getJSONArray(i);result.add(new long[]{r.getLong(0),r.getLong(1)});}return result;
    }
    public static JSONObject merge(JSONObject local,JSONObject remote) throws Exception {
        JSONObject result=new JSONObject(local.toString());
        for(String prefix:new String[]{"position","manual"}) {
            long a=local.optLong(prefix+"_at_ms"),b=remote.optLong(prefix+"_at_ms");
            if(b>a || b==a && remote.optString(prefix+"_id",ZERO).compareTo(local.optString(prefix+"_id",ZERO))>0) {
                String[] fields=prefix.equals("position")?new String[]{"position_ms","duration_ms","position_at_ms","position_id"}:new String[]{"manual_seen","manual_at_ms","manual_id"};
                for(String key:fields)result.put(key,remote.get(key));
            }
        }
        List<long[]> all=ranges(local);all.addAll(ranges(remote));long duration=result.optLong("duration_ms");
        List<long[]> clipped=new ArrayList<>();for(long[] r:all){long end=Math.min(duration,r[1]);if(r[0]<end)clipped.add(new long[]{r[0],end});}
        PlaybackTimeline union=new PlaybackTimeline(0,duration,clipped,false);JSONArray data=new JSONArray();
        for(long[] r:union.ranges())data.put(new JSONArray().put(r[0]).put(r[1]));
        return result.put("ranges",data).put("ended",local.optBoolean("ended")||remote.optBoolean("ended"));
    }
    public static String status(JSONObject state) throws Exception {
        boolean manual=!state.isNull("manual_seen");long covered=0;for(long[] r:ranges(state))covered+=r[1]-r[0];long duration=state.optLong("duration_ms");
        if(manual?state.optBoolean("manual_seen"):state.optBoolean("ended")&&duration>0&&covered*100>=duration*95)return "finished";
        return covered>0||state.optLong("position_ms")>0?"in_progress":"not_started";
    }
    public static JSONObject checkpoint(PlaybackTimeline timeline,JSONObject current) throws Exception {
        JSONObject update=empty().put("position_ms",timeline.position).put("duration_ms",timeline.duration)
                .put("position_at_ms",Math.max(System.currentTimeMillis(),current.optLong("position_at_ms")+1))
                .put("position_id",UUID.randomUUID().toString()).put("ended",timeline.ended);
        JSONArray data=new JSONArray();for(long[] r:timeline.ranges())data.put(new JSONArray().put(r[0]).put(r[1]));update.put("ranges",data);
        return merge(current,update);
    }
    public static JSONObject mark(JSONObject current,boolean seen) throws Exception {
        return new JSONObject(current.toString()).put("manual_seen",seen)
                .put("manual_at_ms",Math.max(System.currentTimeMillis(),current.optLong("manual_at_ms")+1)).put("manual_id",UUID.randomUUID().toString());
    }
    public static JSONObject wire(JSONObject state) throws Exception {
        JSONObject result=empty();for(String key:new String[]{"position_ms","duration_ms","position_at_ms","position_id","ranges","ended","manual_seen","manual_at_ms","manual_id"})if(state.has(key))result.put(key,state.get(key));return result;
    }
}
