package dev.arglabs.tubego;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
public final class DevicesActivity extends LocalizedActivity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private LinearLayout layout;private TextView status;private String origin;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);origin=getIntent().getStringExtra("server_url");layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);
        status=new TextView(this);layout.addView(status);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);load();
    }
    private void load() {
        layout.removeAllViews();layout.addView(status);status.setText(Texts.text(DevicesActivity.this,"Consultando dispositivos…"));
        network.execute(()->{
            try {
                var items=new ApiClient(origin).request("GET","/account/devices",null,new SessionStore(this,origin).token()).getJSONArray("items");
                runOnUiThread(()->{if(isDestroyed())return;status.setText(Texts.text(DevicesActivity.this,"Dispositivos vinculados"));
                    for(int i=0;i<items.length();i++){
                        JSONObject item=items.optJSONObject(i);boolean revoked=!item.isNull("revoked_at");boolean current=item.optBoolean("current");
                        TextView text=new TextView(this);text.setText(item.optString("name")+(current?Texts.text(DevicesActivity.this," · este teléfono"):"")+Texts.text(DevicesActivity.this,"\nÚltima conexión: ")+item.optString("last_seen_at",Texts.text(DevicesActivity.this,"Sin registro"))+(revoked?Texts.text(DevicesActivity.this," · desvinculado"):""));layout.addView(text);
                        if(!revoked){Button button=new Button(this);button.setText(Texts.text(DevicesActivity.this,"Desvincular / cerrar sesión"));layout.addView(button);
                            button.setOnClickListener(v->new AlertDialog.Builder(this).setMessage(Texts.text(DevicesActivity.this,"Se cerrará la sesión y se borrarán los archivos de este dispositivo cuando contacte al servidor. No afecta a los demás. ¿Continuar?")).setNegativeButton(Texts.text(DevicesActivity.this,"Cancelar"),null).setPositiveButton(Texts.text(DevicesActivity.this,"Desvincular"),(d,w)->revoke(item.optString("id"),current)).show());}
                    }
                });
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.text(DevicesActivity.this,"No se pudo consultar. Inicia sesión o revisa tu conexión."));});}
        });
    }
    private void revoke(String device,boolean current){
        network.execute(()->{
            try{
                SessionStore store=new SessionStore(this,origin);String token=store.token();
                new ApiClient(origin).request("POST","/account/devices/"+device+"/revoke",null,token);
                if(current) SessionLifecycle.remotelyRevoked(this,origin,token,"session_revoked");
                runOnUiThread(()->{if(!isDestroyed()){if(current){status.setText(Texts.text(DevicesActivity.this,"Dispositivo desvinculado. Archivos locales borrados."));layout.removeAllViews();layout.addView(status);}else load();}});
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.text(DevicesActivity.this,"No se pudo desvincular. Revisa la conexión e inténtalo nuevamente."));});}
        });
    }
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
