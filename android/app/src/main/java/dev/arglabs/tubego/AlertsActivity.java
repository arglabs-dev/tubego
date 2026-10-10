package dev.arglabs.tubego;

import android.app.Activity;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Always available center; notification opt-in never gates media work. */
public final class AlertsActivity extends LocalizedActivity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private LinearLayout layout;private TextView status;private String origin;private JSONObject session;
    @Override public void onCreate(Bundle state){super.onCreate(state);origin=getIntent().getStringExtra("server_url");layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);status=new TextView(this);layout.addView(status);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);}
    @Override protected void onResume(){super.onResume();refresh();}
    private void refresh(){
        try{session=new SessionStore(this,origin).read();if(session==null)throw new Exception();render();}
        catch(Exception e){layout.removeAllViews();layout.addView(status);status.setText(Texts.text(AlertsActivity.this,"Inicia sesión para ver los avisos de esta cuenta y dispositivo."));return;}
        network.execute(()->{try{AlertSync.poll(this,origin);}catch(Exception ignored){try{if(session!=null)AlertSync.local(this,origin,session);}catch(Exception ignoredLocal){}}
            runOnUiThread(()->{if(!isDestroyed())try{JSONObject current=new SessionStore(this,origin).read();if(current==null||!current.optString("token").equals(session.optString("token")))return;render();}catch(Exception ignored){}});
        });
    }
    private void render()throws Exception{
        layout.removeAllViews();layout.addView(status);status.setText(Texts.text(AlertsActivity.this,"Centro de avisos. No avisamos al terminar cada video. Las notificaciones son opcionales y los avisos quedan disponibles aquí."));
        Button enable=new Button(this);enable.setText(AlertNotifications.enabled(this,origin,session)?Texts.text(AlertsActivity.this,"Desactivar avisos del sistema"):Texts.text(AlertsActivity.this,"Activar avisos del sistema"));layout.addView(enable);
        enable.setOnClickListener(v->{if(AlertNotifications.enabled(this,origin,session)){AlertNotifications.enable(this,origin,session,false);refresh();return;}
            if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},253);}
            else{AlertNotifications.enable(this,origin,session,true);refresh();}
        });
        Button update=new Button(this);update.setText(Texts.text(AlertsActivity.this,"Actualizar avisos"));layout.addView(update);update.setOnClickListener(v->refresh());
        JSONArray items=AlertNotifications.inbox(this,origin,session);boolean admin=AlertNotifications.admin(this,origin,session);
        for(int i=items.length()-1;i>=0;i--){JSONObject item=items.getJSONObject(i);if(!AlertPolicy.accepts(item.optString("kind"),admin))continue;
            TextView text=new TextView(this);text.setText(Texts.text(this,item.optString("title"))+"\n"+Texts.text(this,item.optString("message"))+"\n"+item.optString("created_at"));layout.addView(text);
            Button open=new Button(this);open.setText(Texts.text(AlertsActivity.this,"Revisar"));layout.addView(open);
            open.setOnClickListener(v->{Class<?> destination="storage".equals(item.optString("destination"))?DeviceStorageActivity.class:"registrations".equals(item.optString("destination"))?AdminRegistrationsActivity.class:"admin".equals(item.optString("destination"))?AdminUsersActivity.class:DownloadFailuresActivity.class;
                startActivity(new android.content.Intent(this,destination).putExtra("server_url",origin));});
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==253 && session!=null){AlertNotifications.enable(this,origin,session,results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED);refresh();}}
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
