package dev.arglabs.tubego;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Server retention only: local files and explicit cleanup remain independent. */
public final class AdminRetentionActivity extends Activity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private LinearLayout layout;
    private TextView status;
    private String cursor="";
    private ApiClient api(){return new ApiClient(getIntent().getStringExtra("server_url"));}
    private String token() throws Exception{return new SessionStore(this,api().getBaseUrl()).token();}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);
        status=new TextView(this);layout.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);load();
    }
    private void load(){
        status.setText("Cargando conservación del servidor…");
        network.execute(()->{try{
            JSONObject response=api().request("GET","/admin/users/retention?after="+java.net.URLEncoder.encode(cursor,"UTF-8"),null,token());
            runOnUiThread(()->{if(isDestroyed())return;
                layout.removeAllViews();layout.addView(status);
                status.setText("Conservar evita la limpieza automática del servidor. No impide la limpieza manual ni el borrado por bloqueo. Los archivos del teléfono no cambian.");
                var items=response.optJSONArray("items");
                for(int i=0;items!=null && i<items.length();i++){
                    JSONObject user=items.optJSONObject(i);
                    TextView title=new TextView(this);title.setText(user.optString("email"));layout.addView(title);
                    CheckBox choice=new CheckBox(this);choice.setText("Conservar archivos en el servidor");choice.setChecked(user.optBoolean("preserve_server_files"));layout.addView(choice);
                    Button save=new Button(this);save.setText("Guardar conservación");layout.addView(save);
                    save.setOnClickListener(v->{boolean preserve=choice.isChecked();
                        if(preserve)save(user.optString("id"),true,save);
                        else new AlertDialog.Builder(this).setTitle("Activar limpieza automática")
                            .setMessage("Podrán borrarse las copias del servidor que ya cumplan las condiciones de limpieza. La biblioteca y las copias locales se conservan.")
                            .setNegativeButton("Cancelar",null).setPositiveButton("Guardar",(dialog,which)->save(user.optString("id"),false,save)).show();
                    });
                }
                if(!response.isNull("next_cursor")){Button next=new Button(this);next.setText("Siguiente página");layout.addView(next);next.setOnClickListener(v->{cursor=response.optString("next_cursor");load();});}
                if(!cursor.isEmpty()){Button first=new Button(this);first.setText("Volver al inicio");layout.addView(first);first.setOnClickListener(v->{cursor="";load();});}
            });
        }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText("Se requiere una sesión administrativa activa para consultar conservación.");});}});
    }
    private void save(String user,boolean preserve,Button button){
        button.setEnabled(false);network.execute(()->{String result;
            try{JSONObject response=api().request("PUT","/admin/users/"+user+"/retention",new JSONObject().put("preserve_server_files",preserve),token());result="Guardado. Copias del servidor retiradas: "+response.optInt("removed_server_copies");}
            catch(Exception e){result="No se pudo guardar. Revisa la conexión y los permisos administrativos.";}
            final String message=result;runOnUiThread(()->{if(!isDestroyed()){status.setText(message);button.setEnabled(true);}});
        });
    }
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
