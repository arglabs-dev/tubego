package dev.arglabs.tubego;
import android.app.Activity;import android.content.*;import android.content.res.Configuration;import android.os.LocaleList;import org.json.JSONObject;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.Locale;
/** Account-scoped language survives media cleanup; pending writes are consumed by the shared command sequencer. */
public final class LanguagePreferences {
 public interface Dispatcher {void enqueue(Context context,JSONObject pending)throws Exception;}
 private static volatile Dispatcher dispatcher;
 public static void setDispatcher(Dispatcher value){dispatcher=value;}
 private static SharedPreferences prefs(Context c){return c.getSharedPreferences("tubego_language",Context.MODE_PRIVATE);}
 private static String scope(String origin,String user)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest((new ApiClient(origin).getBaseUrl()+"\n"+user).getBytes(StandardCharsets.UTF_8));StringBuilder key=new StringBuilder();for(byte b:hash)key.append(String.format(Locale.ROOT,"%02x",b&255));return key.toString();}
 public static String origin(Context context){if(context instanceof Activity){Intent intent=((Activity)context).getIntent();if(intent!=null&&intent.hasExtra("server_url"))return intent.getStringExtra("server_url");}return context.getSharedPreferences("server_connection",Context.MODE_PRIVATE).getString("server_url","");}
 public static String language(Context context,String origin){String fallback=LanguageDefaults.system(android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage());try{JSONObject session=new SessionStore(context,origin).read();if(session==null)return fallback;String saved=prefs(context).getString(scope(origin,session.getString("user_id"))+".language",fallback);return LanguageDefaults.valid(saved)?saved:fallback;}catch(Exception e){return fallback;}}
 public static Context localized(Context context,String language){Configuration config=new Configuration(context.getResources().getConfiguration());config.setLocales(LocaleList.forLanguageTags(language));return context.createConfigurationContext(config);}
 public static JSONObject saveLocal(Context context,String origin,String language)throws Exception {
  if(!LanguageDefaults.valid(language))throw new IllegalArgumentException("Unsupported language");JSONObject pending;
  synchronized(SessionStore.class){JSONObject session=new SessionStore(context,origin).read();if(session==null||!"approved".equals(session.optString("status")))throw new SecurityException("Approved account required");String namespace=scope(origin,session.getString("user_id"));SharedPreferences p=prefs(context);long revision=p.getLong(namespace+".revision",0)+1;
   if(!p.edit().putString(namespace+".language",language).putLong(namespace+".revision",revision).putBoolean(namespace+".pending",true).commit())throw new Exception("Could not save language");pending=new JSONObject().put("origin",new ApiClient(origin).getBaseUrl()).put("user_id",session.getString("user_id")).put("language",language).put("revision",revision);
  }
  Dispatcher current=dispatcher;if(current!=null)try{current.enqueue(context,pending);}catch(Exception ignored){/* Local preference remains durable and pending. */}return pending;
 }
 public static boolean dispatchPending(Context context,String origin)throws Exception {JSONObject pending=pending(context,origin);Dispatcher current=dispatcher;if(pending==null)return true;if(current==null)return false;current.enqueue(context,pending);return true;}
 public static JSONObject pending(Context context,String origin)throws Exception {
  synchronized(SessionStore.class){JSONObject session=new SessionStore(context,origin).read();if(session==null)return null;String namespace=scope(origin,session.getString("user_id"));SharedPreferences p=prefs(context);if(!p.getBoolean(namespace+".pending",false))return null;return new JSONObject().put("origin",new ApiClient(origin).getBaseUrl()).put("user_id",session.getString("user_id")).put("language",p.getString(namespace+".language","en")).put("revision",p.getLong(namespace+".revision",0));}
 }
 public static void acknowledge(Context context,String origin,String user,long revision)throws Exception {
  synchronized(SessionStore.class){String namespace=scope(origin,user);SharedPreferences p=prefs(context);if(p.getLong(namespace+".revision",0)==revision)p.edit().putBoolean(namespace+".pending",false).commit();}
 }
 /** Apply a remote read only if no local write happened since that read started. */
 static boolean applyRemote(Context context,String origin,String token,String user,long revision,String value)throws Exception {
  synchronized(SessionStore.class){String namespace=scope(origin,user);JSONObject current=new SessionStore(context,origin).read();SharedPreferences p=prefs(context);
   if(current==null||!token.equals(current.optString("token"))||!user.equals(current.optString("user_id"))||p.getLong(namespace+".revision",0)!=revision||p.getBoolean(namespace+".pending",false)||!LanguageDefaults.valid(value))return false;
   return p.edit().putString(namespace+".language",value).commit();
  }
 }
 public static void refresh(Context context,String origin)throws Exception {
  final String token,user;final long revision;
  synchronized(SessionStore.class){JSONObject session=new SessionStore(context,origin).read();if(session==null||!"approved".equals(session.optString("status")))return;token=session.getString("token");user=session.getString("user_id");String namespace=scope(origin,user);if(prefs(context).getBoolean(namespace+".pending",false))return;revision=prefs(context).getLong(namespace+".revision",0);}
  JSONObject response=new ApiClient(origin).request("GET","/account/preferences/language",null,token);
  applyRemote(context,origin,token,user,revision,response.optString("language",""));
 }
}
