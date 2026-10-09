package dev.arglabs.tubego;
import android.app.job.*;
import android.content.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/** One transport per origin, with durable creation order across every command kind. */
public final class CommandDispatch {
 private static final int JOB=228246;
 private static final ConcurrentHashMap<String,Object> LOCKS=new ConcurrentHashMap<>();
 private CommandDispatch(){}
 private static Set<String> origins(Context c){Set<String> all=new HashSet<>();for(String prefs:new String[]{"tubego_commands","tubego_outbox_jobs","tubego_recovery_jobs","tubego_deletion_jobs","tubego_server_cleanup_jobs"})all.addAll(c.getSharedPreferences(prefs,Context.MODE_PRIVATE).getStringSet("origins",new HashSet<>()));return all;}
 public static void schedule(Context c,String origin){Set<String> all=origins(c);all.add(origin);c.getSharedPreferences("tubego_commands",Context.MODE_PRIVATE).edit().putStringSet("origins",all).commit();schedule(c);}
 public static void schedule(Context c){if(origins(c).isEmpty())return;((JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).schedule(new JobInfo.Builder(JOB,new ComponentName(c,CommandJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());}
 public static boolean flush(Context c){boolean done=true;for(String origin:origins(c))if(!flush(c,origin))done=false;return done;}
 public static CommandQueue.Entry enqueue(Context c,String origin,String kind,JSONObject payload)throws Exception{
  synchronized(SessionStore.class){JSONObject session=new SessionStore(c,origin).read();if(session==null||!"approved".equals(session.optString("status")))throw new IOException("Inicia sesión para guardar la acción");
   CommandQueue.Entry entry=new CommandQueue(LinkOutboxDispatch.root(c,origin,session)).add(kind,payload.toString());schedule(c,origin);return entry;}
 }
 private static boolean active(SessionStore store,String token){try{return token.equals(store.token());}catch(Exception e){return false;}}
 public static boolean flush(Context c,String origin){synchronized(LOCKS.computeIfAbsent(origin,key->new Object())){
  try{
   SessionStore store=new SessionStore(c,origin);JSONObject session=store.read();if(session==null)return true;
   String token=session.getString("token");File root=LinkOutboxDispatch.root(c,origin,session);CommandQueue queue=new CommandQueue(root);
   synchronized(SessionStore.class){if(!active(store,token))return false;importLegacy(root,queue);}
   ApiClient api=new ApiClient(origin);JSONObject status=api.request("GET","/account/status",null,token);if(!"approved".equals(status.optString("status")))return false;
   int count=0;for(CommandQueue.Entry entry:queue.entries()){
    if(!entry.state.equals("queued"))continue;if(++count>100)return false;if(!active(store,token))return false;
    JSONObject payload=new JSONObject(entry.payload);String resource=payload.optString("resource_id");String stamp="";
    synchronized(SessionStore.class){if(!active(store,token))return false;
     if(entry.kind.equals("delete")){TransferRuntime.stopOrigin(origin);LocalResourceDeletion.apply(root,resource);}
     if(entry.kind.equals("recover"))stamp=LocalResourceDeletion.stamp(root,resource);
    }
    JSONObject response=api.request("POST","/device/commands",new JSONObject().put("id",entry.id).put("sequence",entry.sequence).put("kind",entry.kind).put("payload",payload),token);
    if(!entry.id.equals(response.optString("id"))||entry.sequence!=response.optLong("sequence"))throw new IOException("Respuesta de sincronización inválida");
    JSONObject result=response.optJSONObject("result");if(result==null)throw new IOException("Respuesta incompleta");
    boolean accepted="complete".equals(response.optString("status"));if(!accepted&&!"rejected".equals(response.optString("status")))throw new IOException("Estado de sincronización inválido");
    synchronized(SessionStore.class){if(!active(store,token))return false;
     if(accepted&&entry.kind.equals("recover")&&!newerBarrier(queue,entry,resource)&&stamp.equals(LocalResourceDeletion.stamp(root,resource))){
      if(payload.optBoolean("approve_redownload"))LocalResourceDeletion.approve(root,resource);
      File manifest=new File(root,UUID.fromString(resource)+".properties");if(manifest.isFile()){TransferRecord record=TransferRecord.read(manifest);if(Arrays.asList("deleted","cancelled","unavailable","missing").contains(record.state)){record.state="pending";record.message="Solicitud aprobada. Esperando disponibilidad y red permitida.";record.save();}}
     }
     String error=accepted?"":"El servidor rechazó la acción: "+result.optString("code","command_rejected");
     queue.finish(entry,accepted?"complete":"error",error,result.toString());CommandBridge.finishLegacy(root,entry,accepted?"submitted":"error",error,result.optString("resource_id",""));
    }
    if(accepted)TransferJobs.wake(c,origin,false);
   }return true;
  }catch(Exception e){return false;}
 }}
 static boolean newerBarrier(CommandQueue queue,CommandQueue.Entry older,String resource)throws Exception {
  for(CommandQueue.Entry entry:queue.entries())if(entry.sequence>older.sequence&&!entry.state.equals("error")){
   if(entry.kind.equals("server_cleanup"))return true;
   if(entry.kind.equals("delete")&&new JSONObject(entry.payload).optString("resource_id").equals(resource))return true;
  }return false;
 }
 private static void importLegacy(File root,CommandQueue queue)throws Exception{
  Map<String,List<Properties>> records=new LinkedHashMap<>();boolean cleanup=false;Set<String> deletions=new HashSet<>();
  String[][] kinds={{"outbox","submit"},{"recovery-outbox","recover"},{"deletion-outbox","delete"},{"server-cleanup-outbox","server_cleanup"}};
  for(String[] pair:kinds){List<Properties> list=new ArrayList<>();File[] files=new File(root,pair[0]).listFiles((dir,name)->name.endsWith(".properties"));if(files!=null){Arrays.sort(files,Comparator.comparing(File::getName));for(File file:files){if(java.nio.file.Files.isSymbolicLink(file.toPath())||file.length()>16384)throw new IOException("Registro inválido");Properties p=new Properties();try(InputStream in=new FileInputStream(file)){p.load(in);}String id=UUID.fromString(p.getProperty("id")).toString();if(!file.getName().equals(id+".properties"))throw new IOException("Registro inválido");if(queue.find(id)!=null||p.getProperty("state","queued").equals("submitted"))continue;list.add(p);if(pair[1].equals("server_cleanup"))cleanup=true;if(pair[1].equals("delete"))deletions.add(p.getProperty("resource"));}}
   records.put(pair[1],list);
  }
  // Legacy records lack immutable creation dates: never guess order from mtime.
  // An ambiguous recovery requires a NEW explicit approval after deletion or
  // server-cleanup barriers; all imported commands then have permanent sequence.
  for(String[] pair:kinds)for(Properties p:records.get(pair[1])){
   boolean ambiguous=pair[1].equals("recover")&&(cleanup||deletions.contains(p.getProperty("resource")));
   String kind=pair[1];if(ambiguous||!p.getProperty("state","queued").equals("queued"))kind="noop";
   CommandQueue.Entry entry=queue.importIntent(p.getProperty("id"),kind,kind.equals("noop")?"{}":CommandBridge.payload(kind,p));
   if(ambiguous){CommandQueue.Entry legacy=new CommandQueue.Entry(entry.id,entry.sequence,pair[1],"{}","queued","","");CommandBridge.finishLegacy(root,legacy,"error","Solicita nuevamente: una limpieza o borrado pendiente requiere confirmar la intención.","");}
  }
 }
}
