package dev.arglabs.tubego;
import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A single durable, origin/user/device-scoped command. Tokens never enter this file. */
public final class MaintenanceCommands {
    private static File root(Context c,String origin,JSONObject session)throws Exception{return LocalLibraryStorage.root(c,origin,session.getString("user_id"),session.getString("device_id"));}
    public static boolean same(JSONObject a,JSONObject b){return a!=null&&b!=null&&a.optString("token").equals(b.optString("token"))&&a.optString("user_id").equals(b.optString("user_id"))&&a.optString("device_id").equals(b.optString("device_id"));}
    static JSONObject read(File root,String name)throws Exception{File f=new File(root,name);if(!f.isFile())return null;if(f.length()>131072)throw new java.io.IOException("maintenance record too large");return new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}
    static void write(File root,String name,JSONObject value)throws Exception{if(!root.exists()&&!root.mkdirs())throw new java.io.IOException("storage");File temp=new File(root,name+".tmp");try(java.io.FileOutputStream out=new java.io.FileOutputStream(temp)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}Files.move(temp.toPath(),new File(root,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
    public static void enqueue(Context c,String origin,JSONObject command)throws Exception{
        synchronized(SessionStore.class){JSONObject session=new SessionStore(c,origin).read();if(session==null||!"admin".equals(session.optString("role")))throw new Exception("admin");File r=root(c,origin,session);if(read(r,"maintenance-pending.json")!=null)throw new Exception("pending");command.put("request_id",UUID.randomUUID().toString());write(r,"maintenance-pending.json",command);}
        MaintenanceJobService.schedule(c,origin);
    }
    public static boolean flush(Context c,String origin)throws Exception{
        JSONObject session,command;File r;
        synchronized(SessionStore.class){session=new SessionStore(c,origin).read();if(session==null)return false;r=root(c,origin,session);command=read(r,"maintenance-pending.json");if(command==null)return false;}
        JSONObject result;
        try{result=new ApiClient(origin).request("POST","/admin/maintenance/jobs",command,session.getString("token"));}
        catch(ApiClient.ApiException e){if(e.status==429||e.status>=500&& !e.code.equals("capability_unavailable"))throw e;result=new JSONObject().put("status","failed").put("error_code",e.code.isEmpty()?"permission_or_configuration_unavailable":e.code);}
        synchronized(SessionStore.class){if(!same(session,new SessionStore(c,origin).read()))return false;JSONObject current=read(r,"maintenance-pending.json");if(current==null||!current.getString("request_id").equals(command.getString("request_id")))return false;write(r,"maintenance-last.json",result);Files.deleteIfExists(new File(r,"maintenance-pending.json").toPath());}
        return true;
    }
    public static JSONObject last(Context c,String origin)throws Exception{synchronized(SessionStore.class){JSONObject session=new SessionStore(c,origin).read();return session==null?null:read(root(c,origin,session),"maintenance-last.json");}}
    public static void cache(Context c,String origin,JSONObject expected,JSONObject value)throws Exception{synchronized(SessionStore.class){if(same(expected,new SessionStore(c,origin).read()))write(root(c,origin,expected),"maintenance-status.json",value);}}
    public static JSONObject cached(Context c,String origin)throws Exception{synchronized(SessionStore.class){JSONObject session=new SessionStore(c,origin).read();return session==null?null:read(root(c,origin,session),"maintenance-status.json");}}
}
