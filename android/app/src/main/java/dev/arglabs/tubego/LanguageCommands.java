package dev.arglabs.tubego;
import android.content.Context;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;
/** Optional PLA-255 integration keeps independently delivered cards buildable. */
public final class LanguageCommands {
 private LanguageCommands(){}
 public static void register(Context context){
  try{Class<?> helper=Class.forName("dev.arglabs.tubego.LanguagePreferences");Class<?> dispatcher=Class.forName("dev.arglabs.tubego.LanguagePreferences$Dispatcher");
   Object callback=Proxy.newProxyInstance(dispatcher.getClassLoader(),new Class<?>[]{dispatcher},(proxy,method,args)->{
    if(method.getName().equals("enqueue")){enqueue((Context)args[0],(JSONObject)args[1]);return null;}
    if(method.getName().equals("toString"))return "Tubego ordered language dispatcher";
    if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
    if(method.getName().equals("equals"))return proxy==args[0];return null;
   });helper.getMethod("setDispatcher",dispatcher).invoke(null,callback);
  }catch(ClassNotFoundException absent){/* PLA-255 can be installed independently. */}catch(Exception invalid){throw new IllegalStateException("Cannot install ordered language dispatcher",invalid);}
 }
 private static void enqueue(Context context,JSONObject pending)throws Exception{
  String origin=pending.getString("origin"),user=pending.getString("user_id");
  synchronized(SessionStore.class){JSONObject session=new SessionStore(context,origin).read();if(session==null||!user.equals(session.getString("user_id")))return;
   long revision=pending.getLong("revision");JSONObject payload=new JSONObject().put("language",pending.getString("language")).put("revision",revision);
   String id=UUID.nameUUIDFromBytes((origin+"\n"+user+"\nlanguage\n"+revision).getBytes(StandardCharsets.UTF_8)).toString();
   CommandQueue queue=new CommandQueue(LinkOutboxDispatch.root(context,origin,session));CommandQueue.Entry existing=queue.find(id);
   if(existing==null){CommandDispatch.schedule(context,origin);queue.importIntent(id,"language",payload.toString());}
   else if(!existing.state.equals("queued"))acknowledge(context,origin,user,revision);
  }
 }
 public static void pending(Context context,String origin)throws Exception{try{Class.forName("dev.arglabs.tubego.LanguagePreferences").getMethod("dispatchPending",Context.class,String.class).invoke(null,context,origin);}catch(ClassNotFoundException absent){/* No pending locale exists before PLA-255. */}}
 public static void refresh(Context context,String origin){try{Class.forName("dev.arglabs.tubego.LanguagePreferences").getMethod("refresh",Context.class,String.class).invoke(null,context,origin);}catch(Exception unavailable){/* Metadata polling does not invalidate completed command receipts. */}}
 public static void acknowledge(Context context,String origin,String user,long revision)throws Exception{try{Class.forName("dev.arglabs.tubego.LanguagePreferences").getMethod("acknowledge",Context.class,String.class,String.class,long.class).invoke(null,context,origin,user,revision);}catch(ClassNotFoundException absent){/* No locale helper yet. */}}
}
