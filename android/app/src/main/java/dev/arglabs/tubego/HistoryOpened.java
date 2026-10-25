package dev.arglabs.tubego;
import android.content.Context;import org.json.JSONObject;import java.io.*;import java.nio.file.*;import java.time.Instant;import java.util.*;
/** Latest opening per resource is durable offline and reconciled monotonically by the API. */
public final class HistoryOpened {
 private static Properties load(File root)throws IOException{Properties p=new Properties();File f=new File(root,"opened.properties");if(f.exists())try(FileInputStream in=new FileInputStream(f)){p.load(in);}return p;}
 private static void save(File root,Properties p)throws IOException{File tmp=new File(root,"opened.properties.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){p.store(out,"Pending opening history");out.getFD().sync();}Files.move(tmp.toPath(),new File(root,"opened.properties").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
 public static void record(Context context,String origin,String id,String expectedToken)throws Exception {
  synchronized(SessionStore.class){JSONObject session=new SessionStore(context,origin).read();if(session==null||!"approved".equals(session.optString("status"))||!expectedToken.equals(session.optString("token")))return;
   File root=LocalLibraryStorage.root(context,origin,session.getString("user_id"),session.getString("device_id"));if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Biblioteca no disponible");
   Properties p=load(root);String timestamp=Instant.now().toString();p.setProperty(UUID.fromString(id).toString(),timestamp);save(root,p);
   HistoryCache cache=new HistoryCache(root);String old=cache.read().get(id);JSONObject row=old==null?new JSONObject().put("id",id):new JSONObject(old);
   if(old==null){try{TransferRecord record=OfflineMediaAccess.record(root,id);row.put("title",record.title).put("created_at",record.createdAt).put("media_format",record.mediaFormat);}catch(Exception ignored){}}
   row.put("last_opened_at",timestamp);cache.merge(java.util.Collections.singletonMap(id,row.toString()));
  }
 }
 public static String pending(File root,String id)throws IOException{return load(root).getProperty(id);}
 public static void flush(Context context,String origin,String token,File root)throws Exception {
  Properties pending;synchronized(SessionStore.class){if(!token.equals(new SessionStore(context,origin).token()))return;pending=load(root);}
  ApiClient api=new ApiClient(origin);
  for(String id:pending.stringPropertyNames()){
   String timestamp=pending.getProperty(id);
   try{api.request("POST","/resources/"+UUID.fromString(id)+"/opened",new JSONObject().put("opened_at",timestamp),token);}
   catch(ApiClient.ApiException e){if(e.status!=404&&e.status!=422)throw e; /* A removed resource or invalid device clock must not block every transfer. */}
   synchronized(SessionStore.class){if(!token.equals(new SessionStore(context,origin).token()))return;Properties current=load(root);if(timestamp.equals(current.getProperty(id))){current.remove(id);save(root,current);}}
  }
 }
}
