package dev.arglabs.tubego;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Durable request journal. Only one unsent request; retries keep the same UUID. No tokens on disk. */
public final class MaintenanceCommands {
    private static final java.util.concurrent.CopyOnWriteArraySet<java.util.function.Consumer<String>> observers=new java.util.concurrent.CopyOnWriteArraySet<>();
    static void observe(java.util.function.Consumer<String> observer){observers.add(observer);}
    static void unobserve(java.util.function.Consumer<String> observer){observers.remove(observer);}
    private static final ConcurrentHashMap<String,Object> dispatchLocks=new ConcurrentHashMap<>();
    private static String now(){return java.time.Instant.now().toString();}
    private static void changed(Context c,String origin){new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{for(java.util.function.Consumer<String> observer:observers)observer.accept(origin);});}
    private static File root(Context c,String origin,JSONObject session)throws Exception{return LocalLibraryStorage.root(c,origin,session.getString("user_id"),session.getString("device_id"));}
    public static boolean same(JSONObject a,JSONObject b){return a!=null&&b!=null&&a.optString("token").equals(b.optString("token"))&&a.optString("user_id").equals(b.optString("user_id"))&&a.optString("device_id").equals(b.optString("device_id"));}
    static JSONObject read(File root,String name)throws Exception{File f=new File(root,name);if(!f.isFile())return null;if(f.length()>524288)throw new java.io.IOException("maintenance record too large");return new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}
    static void write(File root,String name,JSONObject value)throws Exception{if(!root.exists()&&!root.mkdirs())throw new java.io.IOException("storage");File temp=new File(root,name+".tmp");try(java.io.FileOutputStream out=new java.io.FileOutputStream(temp)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}Files.move(temp.toPath(),new File(root,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
    private static JSONArray entries(File root)throws Exception{JSONObject journal=read(root,"maintenance-journal.json");return journal==null?new JSONArray():journal.optJSONArray("entries");}
    private static void remember(File root,JSONObject value)throws Exception{
        JSONArray old=entries(root),next=new JSONArray();String id=value.optString("request_id",value.optString("id"));boolean replaced=false;
        for(int n=0;n<old.length();n++){
            JSONObject entry=old.getJSONObject(n);
            if(id.equals(entry.optString("request_id",entry.optString("id")))){next.put(value);replaced=true;}else next.put(entry);
        }
        if(!replaced)next.put(value);
        JSONArray bounded=new JSONArray();for(int n=Math.max(0,next.length()-40);n<next.length();n++)bounded.put(next.getJSONObject(n));
        write(root,"maintenance-journal.json",new JSONObject().put("entries",bounded));
    }
    public static void enqueue(Context c,String origin,JSONObject command)throws Exception{
        synchronized(SessionStore.class){
            JSONObject session=new SessionStore(c,origin).read();if(session==null||!"admin".equals(session.optString("role"))||!"approved".equals(session.optString("status")))throw new Exception("admin");
            File r=root(c,origin,session);if(read(r,"maintenance-pending.json")!=null)throw new Exception("pending");
            command.put("request_id",UUID.randomUUID().toString()).put("created_at",now()).put("transport_status","saved").put("attempts",0);
            write(r,"maintenance-pending.json",command);remember(r,new JSONObject(command.toString()));
        }
        changed(c,origin);MaintenanceJobService.schedule(c,origin);
    }
    static JSONObject wire(JSONObject command)throws Exception{
        JSONObject value=new JSONObject();for(String field:new String[]{"request_id","action","confirmed","revision","version"})if(command.has(field))value.put(field,command.get(field));return value;
    }
    interface Sender {JSONObject send(JSONObject payload,String token)throws Exception;}
    public static boolean flush(Context c,String origin)throws Exception{return flush(c,origin,(payload,token)->new ApiClient(origin).request("POST","/admin/maintenance/jobs",payload,token));}
    static boolean flush(Context c,String origin,Sender sender)throws Exception{
        synchronized(dispatchLocks.computeIfAbsent(origin,key->new Object())){return dispatch(c,origin,sender);}
    }
    private static boolean dispatch(Context c,String origin,Sender sender)throws Exception{
        JSONObject session,command;File r;
        synchronized(SessionStore.class){
            session=new SessionStore(c,origin).read();if(session==null)return false;r=root(c,origin,session);command=read(r,"maintenance-pending.json");if(command==null)return false;
            String at=now();if(!command.has("sent_at"))command.put("sent_at",at);
            command.put("last_attempt_at",at).put("attempts",command.optInt("attempts")+1).put("transport_status","sending");
            write(r,"maintenance-pending.json",command);remember(r,new JSONObject(command.toString()));
        }
        changed(c,origin);JSONObject result;
        try{result=sender.send(wire(command),session.getString("token"));}
        catch(Exception e){
            ApiClient.ApiException api=e instanceof ApiClient.ApiException?(ApiClient.ApiException)e:null;
            if(api==null||api.status==429||api.status>=500&&!api.code.equals("capability_unavailable")){
                synchronized(SessionStore.class){if(same(session,new SessionStore(c,origin).read())){
                    command.put("transport_status",api!=null&&api.status==429?"retry_wait":"unconfirmed").put("last_error_code",api==null?"connection_unconfirmed":api.status==429?"rate_limited":api.code);
                    if(api!=null)command.put("last_response_at",now()).put("last_http_status",api.status);
                    write(r,"maintenance-pending.json",command);remember(r,new JSONObject(command.toString()));
                }}
                changed(c,origin);throw e;
            }
            result=new JSONObject().put("status","failed").put("error_code",api.code.isEmpty()?(api.status==409?"maintenance_conflict":api.status==422?"version_not_approved":"http_"+api.status):api.code);
        }
        synchronized(SessionStore.class){
            if(!same(session,new SessionStore(c,origin).read()))return false;
            JSONObject current=read(r,"maintenance-pending.json");if(current==null||!current.getString("request_id").equals(command.getString("request_id")))return false;
            result.put("request_id",command.optString("request_id")).put("action",command.optString("action")).put("received_at",now());
            command.put("transport_status",result.has("id")?"accepted":"rejected").put("received_at",result.getString("received_at")).put("server_job",result);
            remember(r,new JSONObject(command.toString()));write(r,"maintenance-last.json",result);Files.deleteIfExists(new File(r,"maintenance-pending.json").toPath());
        }
        changed(c,origin);return true;
    }
    public static JSONArray journal(Context c,String origin)throws Exception{synchronized(SessionStore.class){JSONObject session=new SessionStore(c,origin).read();return session==null?new JSONArray():entries(root(c,origin,session));}}
    public static JSONObject pending(Context c,String origin)throws Exception{synchronized(SessionStore.class){JSONObject session=new SessionStore(c,origin).read();return session==null?null:read(root(c,origin,session),"maintenance-pending.json");}}
    public static JSONObject last(Context c,String origin)throws Exception{synchronized(SessionStore.class){JSONObject session=new SessionStore(c,origin).read();return session==null?null:read(root(c,origin,session),"maintenance-last.json");}}
    public static void cache(Context c,String origin,JSONObject expected,JSONObject value)throws Exception{
        synchronized(SessionStore.class){if(same(expected,new SessionStore(c,origin).read())){
            File r=root(c,origin,expected);write(r,"maintenance-status.json",value);
            JSONArray local=entries(r),jobs=value.optJSONArray("jobs");
            if(jobs!=null)for(int n=0;n<local.length();n++)for(int j=0;j<jobs.length();j++){
                JSONObject entry=local.getJSONObject(n),job=jobs.getJSONObject(j);
                if(entry.optString("request_id").equals(job.optString("id")))entry.put("server_job",job);
            }
            write(r,"maintenance-journal.json",new JSONObject().put("entries",local));
        }}
    }
    public static JSONObject cached(Context c,String origin)throws Exception{synchronized(SessionStore.class){JSONObject session=new SessionStore(c,origin).read();return session==null?null:read(root(c,origin,session),"maintenance-status.json");}}
}
