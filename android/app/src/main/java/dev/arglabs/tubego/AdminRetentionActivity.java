package dev.arglabs.tubego;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Server retention only: local files and explicit cleanup remain independent. */
public final class AdminRetentionActivity extends LocalizedActivity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private LinearLayout layout;
    private TextView status;
    private String cursor="";
    private ApiClient api(){return new ApiClient(getIntent().getStringExtra("server_url"));}
    private String token() throws Exception{return AdminAccess.remoteToken(this,api().getBaseUrl());}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);
        status=new TextView(this);layout.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);if(!AdminAccess.allowed(this,getIntent().getStringExtra("server_url"))){status.setText("Se requiere una cuenta aprobada de administrador.");return;}load();
    }
    private void load(){
        status.setText(Texts.text(AdminRetentionActivity.this,"Cargando conservación del servidor…"));
        network.execute(()->{try{
            JSONObject response=api().request("GET","/admin/users/retention?after="+java.net.URLEncoder.encode(cursor,"UTF-8"),null,token());
            runOnUiThread(()->{if(isDestroyed())return;
                layout.removeAllViews();layout.addView(status);addDeadlines();
                status.setText(Texts.text(AdminRetentionActivity.this,"Conservar evita la limpieza automática del servidor. No impide la limpieza manual ni el borrado por bloqueo. Los archivos del teléfono no cambian."));
                var items=response.optJSONArray("items");
                for(int i=0;items!=null && i<items.length();i++){
                    JSONObject user=items.optJSONObject(i);
                    TextView title=new TextView(this);title.setText(user.optString("email"));layout.addView(title);
                    CheckBox choice=new CheckBox(this);choice.setText(Texts.text(AdminRetentionActivity.this,"Conservar archivos en el servidor"));choice.setChecked(user.optBoolean("preserve_server_files"));layout.addView(choice);
                    Button save=new Button(this);save.setText(Texts.text(AdminRetentionActivity.this,"Guardar conservación"));layout.addView(save);
                    save.setOnClickListener(v->{boolean preserve=choice.isChecked();
                        if(preserve)save(user.optString("id"),true,save);
                        else new AlertDialog.Builder(this).setTitle(Texts.text(AdminRetentionActivity.this,"Activar limpieza automática"))
                            .setMessage(Texts.text(AdminRetentionActivity.this,"Podrán borrarse las copias del servidor que ya cumplan las condiciones de limpieza. La biblioteca y las copias locales se conservan."))
                            .setNegativeButton(Texts.text(AdminRetentionActivity.this,"Cancelar"),null).setPositiveButton(Texts.text(AdminRetentionActivity.this,"Guardar"),(dialog,which)->save(user.optString("id"),false,save)).show();
                    });
                }
                if(!response.isNull("next_cursor")){Button next=new Button(this);next.setText(Texts.text(AdminRetentionActivity.this,"Siguiente página"));layout.addView(next);next.setOnClickListener(v->{cursor=response.optString("next_cursor");load();});}
                if(!cursor.isEmpty()){Button first=new Button(this);first.setText(Texts.text(AdminRetentionActivity.this,"Volver al inicio"));layout.addView(first);first.setOnClickListener(v->{cursor="";load();});}
            });
        }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.text(AdminRetentionActivity.this,"Se requiere una sesión administrativa activa para consultar conservación."));});}});
    }
    private void addDeadlines(){
        TextView heading=new TextView(this);heading.setText(Texts.text(AdminRetentionActivity.this,"Plazos globales (horas). Aplican también a archivos existentes."));layout.addView(heading);
        EditText absolute=new EditText(this);absolute.setHint(Texts.text(AdminRetentionActivity.this,"Máximo desde archivo listo (72)"));absolute.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);layout.addView(absolute);
        EditText delivery=new EditText(this);delivery.setHint(Texts.text(AdminRetentionActivity.this,"Máximo desde primera entrega (4)"));delivery.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);layout.addView(delivery);
        Button save=new Button(this);save.setText(Texts.text(AdminRetentionActivity.this,"Revisar impacto y guardar plazos"));save.setEnabled(false);layout.addView(save);
        network.execute(()->{try{JSONObject values=api().request("GET","/admin/retention/deadlines",null,token());runOnUiThread(()->{if(!isDestroyed()){absolute.setText(String.valueOf(values.optInt("absolute_hours")));delivery.setText(String.valueOf(values.optInt("delivery_hours")));save.setEnabled(true);}});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.text(AdminRetentionActivity.this,"No se pudieron consultar los plazos."));});}});
        save.setOnClickListener(v->{final int a,d;
            try{a=Integer.parseInt(absolute.getText().toString());d=Integer.parseInt(delivery.getText().toString());if(a<1||a>8760||d<1||d>8760)throw new IllegalArgumentException();}
            catch(Exception e){status.setText(Texts.text(AdminRetentionActivity.this,"Los plazos deben ser horas enteras entre 1 y 8760."));return;}
            save.setEnabled(false);network.execute(()->{try{
                JSONObject preview=api().request("POST","/admin/retention/deadlines/preview",new JSONObject().put("absolute_hours",a).put("delivery_hours",d),token());
                int count=preview.optInt("immediate_deletions");
                runOnUiThread(()->{if(isDestroyed())return;
                    new AlertDialog.Builder(this).setTitle(Texts.text(AdminRetentionActivity.this,"Confirmar plazos"))
                        .setMessage(Texts.text(AdminRetentionActivity.this,"Se aplicarán a todos los archivos existentes y nuevos, desde sus fechas originales. Copias del servidor que vencen ahora: ")+count+Texts.text(AdminRetentionActivity.this,". Los archivos del teléfono no cambian."))
                        .setNegativeButton(Texts.text(AdminRetentionActivity.this,"Cancelar"),(dialog,which)->save.setEnabled(true))
                        .setOnCancelListener(dialog->save.setEnabled(true))
                        .setPositiveButton(Texts.text(AdminRetentionActivity.this,"Aplicar"),(dialog,which)->network.execute(()->{String result;
                            try{JSONObject response=api().request("PUT","/admin/retention/deadlines",new JSONObject().put("absolute_hours",a).put("delivery_hours",d).put("preview_token",preview.optString("preview_token")).put("confirm_immediate_deletion",count>0),token());result=Texts.text(AdminRetentionActivity.this,"Plazos guardados. Copias retiradas: ")+response.optInt("removed_server_copies");}
                            catch(Exception e){result=Texts.text(AdminRetentionActivity.this,"No se pudo aplicar. Revisa el impacto otra vez; pudo cambiar o caducar.");}
                            final String message=result;runOnUiThread(()->{if(!isDestroyed()){status.setText(message);save.setEnabled(true);}});
                        })).show();
                });
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){status.setText(Texts.text(AdminRetentionActivity.this,"No se pudo revisar el impacto. Revisa conexión y permisos."));save.setEnabled(true);}});}});
        });
    }
    private void save(String user,boolean preserve,Button button){
        button.setEnabled(false);network.execute(()->{String result;
            try{JSONObject response=api().request("PUT","/admin/users/"+user+"/retention",new JSONObject().put("preserve_server_files",preserve),token());result=Texts.text(AdminRetentionActivity.this,"Guardado. Copias del servidor retiradas: ")+response.optInt("removed_server_copies");}
            catch(Exception e){result=Texts.text(AdminRetentionActivity.this,"No se pudo guardar. Revisa la conexión y los permisos administrativos.");}
            final String message=result;runOnUiThread(()->{if(!isDestroyed()){status.setText(message);button.setEnabled(true);}});
        });
    }
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
