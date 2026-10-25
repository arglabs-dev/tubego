package dev.arglabs.tubego;

import android.content.*;
import org.json.*;
import java.io.*;

/** Bounded polling, separate from transfer event cursors and media sockets. */
public final class AlertSync {
    private AlertSync(){}
    public static void poll(Context context,String origin)throws Exception{
        JSONObject session=new SessionStore(context,origin).read();if(session==null||!"approved".equals(session.optString("status")))return;
        String token=session.getString("token"),prefix=AlertNotifications.prefix(origin,session);
        ApiClient api=new ApiClient(origin);SharedPreferences store=AlertNotifications.storage(context);
        for(int page=0;page<4;page++){
            JSONObject result=api.request("GET","/device/alerts?limit=50",null,token);
            AlertNotifications.role(context,origin,session,result.optString("role"));
            JSONArray rows=result.getJSONArray("items");long cursor=store.getLong(prefix+"event_cursor",0);
            for(int i=0;i<rows.length();i++){JSONObject entry=rows.getJSONObject(i);long id=entry.getLong("id");if(id<=cursor)continue;
                entry.put("identity","server:"+id);AlertNotifications.add(context,origin,session,entry);cursor=id;
            }
            synchronized(SessionStore.class){if(!AlertNotifications.current(context,origin,session))return;
                if(!store.edit().putLong(prefix+"event_cursor",Math.max(cursor,store.getLong(prefix+"event_cursor",0))).commit())throw new IOException("No se pudo guardar el cursor de avisos");}
            long next=result.getLong("next_cursor");
            if(next>result.getLong("cursor"))api.request("POST","/device/alerts/ack",new JSONObject().put("event_id",next),token);
            if(!result.getBoolean("has_more"))break;
        }
        local(context,origin,session);
    }
    public static void local(Context context,String origin,JSONObject session)throws Exception{
        if(session==null||!"approved".equals(session.optString("status")))return;
        String prefix=AlertNotifications.prefix(origin,session);SharedPreferences store=AlertNotifications.storage(context);
        File root=LocalLibraryStorage.root(context,origin,session.getString("user_id"),session.getString("device_id"));
        File[] manifests=root.listFiles((dir,name)->name.endsWith(".properties"));
        if(manifests!=null)for(File file:manifests){try{TransferRecord record=TransferRecord.read(file);if(!"failed".equals(record.state))continue;
            String incident=record.failureIncident.isEmpty()?record.sha256+":"+record.failures:record.failureIncident,key=prefix+"failure:"+record.id;
            if(incident.equals(store.getString(key,"")))continue;
            AlertNotifications.add(context,origin,session,new JSONObject().put("identity","local:"+record.id+":"+incident).put("kind","download_failed").put("title","Descarga fallida").put("message","La transferencia falló definitivamente. Abre Descargas para revisar el motivo y reintentar.").put("destination","downloads").put("created_at",java.time.Instant.now().toString()));
            synchronized(SessionStore.class){if(!AlertNotifications.current(context,origin,session))return;store.edit().putString(key,incident).commit();}
        }catch(FileNotFoundException ignored){/* Concurrent explicit cleanup. */}}
        SharedPreferences space=context.getSharedPreferences("tubego_device_storage",Context.MODE_PRIVATE);long sequence=space.getLong(prefix+"low_space_sequence",0);
        if(space.getBoolean(prefix+"low_space_active",false)&&sequence>store.getLong(prefix+"space_sequence",0)){
            AlertNotifications.add(context,origin,session,new JSONObject().put("identity","storage:"+sequence).put("kind","device_storage").put("title","Poco espacio en el teléfono").put("message","Las descargas esperan espacio suficiente. Tus archivos se conservan.").put("destination","storage").put("created_at",java.time.Instant.now().toString()));
            synchronized(SessionStore.class){if(!AlertNotifications.current(context,origin,session))return;store.edit().putLong(prefix+"space_sequence",Math.max(sequence,store.getLong(prefix+"space_sequence",0))).commit();}
        }
    }
}
