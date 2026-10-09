package dev.arglabs.tubego;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.*;
import java.util.*;

/** Durable account/device inbox independent of the runtime notification permission. */
public final class AlertNotifications {
    private AlertNotifications(){}
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences("tubego_alerts",Context.MODE_PRIVATE);}
    static String prefix(String origin,JSONObject session){return TransferKey.accountPrefix(origin,session.optString("user_id"),session.optString("device_id"));}
    private static boolean same(Context context,String origin,JSONObject session){
        try{JSONObject current=new SessionStore(context,origin).read();return current!=null && current.optString("token").equals(session.optString("token")) && current.optString("user_id").equals(session.optString("user_id")) && current.optString("device_id").equals(session.optString("device_id"));}catch(Exception e){return false;}
    }
    public static boolean enabled(Context context,String origin,JSONObject session){return prefs(context).getBoolean(prefix(origin,session)+"enabled",false);}
    public static void enable(Context context,String origin,JSONObject session,boolean value){synchronized(SessionStore.class){if(same(context,origin,session)){String key=prefix(origin,session);prefs(context).edit().putBoolean(key+"enabled",value).commit();if(!value){NotificationManager manager=context.getSystemService(NotificationManager.class);for(var notice:manager.getActiveNotifications())if(key.equals(notice.getTag()))manager.cancel(key,notice.getId());}}}}
    public static JSONArray inbox(Context context,String origin,JSONObject session)throws Exception{return new JSONArray(prefs(context).getString(prefix(origin,session)+"inbox","[]"));}
    public static void role(Context context,String origin,JSONObject session,String role)throws Exception{
        synchronized(SessionStore.class){if(!same(context,origin,session))return;String key=prefix(origin,session);prefs(context).edit().putString(key+"role",role).commit();
            if(!"admin".equals(role)){NotificationManager manager=context.getSystemService(NotificationManager.class);for(var notice:manager.getActiveNotifications())if(key.equals(notice.getTag())&&"tubego_admin".equals(notice.getNotification().getChannelId()))manager.cancel(key,notice.getId());}
        }
    }
    public static boolean admin(Context context,String origin,JSONObject session){return "admin".equals(prefs(context).getString(prefix(origin,session)+"role",session.optString("role")));}
    public static void add(Context context,String origin,JSONObject session,JSONObject entry)throws Exception{
        synchronized(SessionStore.class){
            if(!same(context,origin,session)||!AlertPolicy.accepts(entry.optString("kind"),admin(context,origin,session)))return;
            String key=prefix(origin,session);JSONArray old=inbox(context,origin,session),items=new JSONArray();boolean seen=false;
            for(int i=Math.max(0,old.length()-99);i<old.length();i++){JSONObject item=old.getJSONObject(i);if(item.optString("identity").equals(entry.optString("identity")))seen=true;items.put(item);}
            if(seen)return;items.put(entry);
            if(!prefs(context).edit().putString(key+"inbox",items.toString()).commit())throw new Exception("No se pudo guardar el aviso");
            if(!enabled(context,origin,session))return;
            NotificationManager manager=context.getSystemService(NotificationManager.class);
            if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED || !manager.areNotificationsEnabled())return;
            boolean admin=AlertPolicy.administrative(entry.optString("kind"));String channel=admin?"tubego_admin":"tubego_action_required";
            manager.createNotificationChannel(new NotificationChannel(channel,admin?"Administración del servidor":"Avisos que requieren acción",NotificationManager.IMPORTANCE_DEFAULT));
            Intent intent=new Intent(context,AlertsActivity.class).putExtra("server_url",origin);
            PendingIntent open=PendingIntent.getActivity(context,(key+entry.optString("identity")).hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            try{manager.notify(key,entry.optString("identity").hashCode(),new Notification.Builder(context,channel).setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle(entry.optString("title")).setContentText(entry.optString("message")).setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());}
            catch(SecurityException ignored){/* The inbox remains available without OS permission. */}
        }
    }
    public static void clear(Context context,String origin,String user,String device)throws Exception{
        synchronized(SessionStore.class){String key=TransferKey.accountPrefix(origin,user,device);SharedPreferences store=prefs(context);NotificationManager manager=context.getSystemService(NotificationManager.class);
            for(var notice:manager.getActiveNotifications())if(key.equals(notice.getTag()))manager.cancel(key,notice.getId());
            var edit=store.edit();for(String stored:store.getAll().keySet())if(stored.startsWith(key))edit.remove(stored);edit.commit();}
    }
    static SharedPreferences storage(Context context){return prefs(context);}
    static boolean current(Context context,String origin,JSONObject session){return same(context,origin,session);}
}
