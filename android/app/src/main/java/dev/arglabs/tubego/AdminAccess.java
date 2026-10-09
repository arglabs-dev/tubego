package dev.arglabs.tubego;
import android.content.Context;import org.json.JSONObject;
/** Local visibility is convenient; every protected action also validates live backend privileges. */
public final class AdminAccess {
 public static boolean allowed(Context context,String origin){try{return allowed(new SessionStore(context,origin).read());}catch(Exception e){return false;}}
 public static boolean allowed(JSONObject session){return session!=null&&AdminRole.allowed(session.optString("status"),session.optString("role"),session.optString("token"));}
 public static String remoteToken(Context context,String origin)throws Exception {
  SessionStore store=new SessionStore(context,origin);JSONObject session;String token;
  synchronized(SessionStore.class){session=store.read();if(!allowed(session))throw new SecurityException("Se requiere una cuenta aprobada de administrador.");token=session.getString("token");}
  JSONObject account=new ApiClient(origin).request("GET","/account/status",null,token);
  synchronized(SessionStore.class){JSONObject current=store.read();if(current==null||!token.equals(current.optString("token")))throw new SecurityException("La sesión cambió.");current.put("role",account.getString("role")).put("status",account.getString("status"));store.save(current);if(!allowed(current))throw new SecurityException("Tu cuenta ya no tiene permisos administrativos.");}
  return token;
 }
}
