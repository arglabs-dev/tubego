package dev.arglabs.tubego;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AdminUsersActivity extends LocalizedActivity {
 private final ExecutorService network=Executors.newSingleThreadExecutor();
 private LinearLayout layout;private TextView status;private String origin;
 @Override public void onCreate(Bundle state){super.onCreate(state);origin=getIntent().getStringExtra("server_url");layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);status=new TextView(this);layout.addView(status);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);try{JSONObject account=new SessionStore(this,origin).read();if(account==null||!"approved".equals(account.optString("status"))||!"admin".equals(account.optString("role"))){status.setText(Texts.text(AdminUsersActivity.this,"Se requiere una cuenta aprobada de administrador."));return;}}catch(Exception e){status.setText(Texts.text(AdminUsersActivity.this,"Inicia sesión como administrador."));return;}load();}
 private String token() throws Exception{return AdminAccess.remoteToken(this,origin);}
 private void load(){layout.removeAllViews();layout.addView(status);status.setText(Texts.text(AdminUsersActivity.this,"Consultando usuarios…"));Button alerts=new Button(this);alerts.setText(Texts.text(AdminUsersActivity.this,"Avisos administrativos"));layout.addView(alerts);alerts.setOnClickListener(v->startActivity(new android.content.Intent(this,AlertsActivity.class).putExtra("server_url",origin)));Button errors=new Button(this);errors.setText(Texts.text(AdminUsersActivity.this,"Diagnósticos de descargas fallidas"));layout.addView(errors);errors.setOnClickListener(v->startActivity(new android.content.Intent(this,AdminDownloadErrorsActivity.class).putExtra("server_url",origin)));
  network.execute(()->{try{var users=new ApiClient(origin).request("GET","/admin/users",null,token()).getJSONArray("items");
   runOnUiThread(()->{if(isDestroyed())return;status.setText(Texts.text(AdminUsersActivity.this,"Administración de usuarios"));
    for(int i=0;i<users.length();i++){
     JSONObject user=users.optJSONObject(i);String id=user.optString("id"),state=user.optString("status");boolean deleted="deleted".equals(state);
     TextView description=new TextView(this);description.setText((deleted?Texts.text(AdminUsersActivity.this,"Cuenta eliminada"):user.optString("email"))+" · "+Texts.state(this,state)+" · "+Texts.state(this,user.optString("role"))+(!user.isNull("email_verified_at")?Texts.text(AdminUsersActivity.this," · correo verificado"):Texts.text(AdminUsersActivity.this," · correo sin verificar"))+(user.optBoolean("cleanup_pending")?Texts.text(AdminUsersActivity.this,"\nLimpieza del servidor pendiente"):""));layout.addView(description);
     if(!deleted){if(!"blocked".equals(state))button(id,Texts.text(AdminUsersActivity.this,"Bloquear usuario"),"POST","/block",false);else button(id,Texts.text(AdminUsersActivity.this,"Desbloquear usuario"),"POST","/unblock",false);button(id,Texts.text(AdminUsersActivity.this,"Eliminar cuenta y datos"),"DELETE","",true);}
     if(user.optBoolean("cleanup_pending"))button(id,Texts.text(AdminUsersActivity.this,"Reintentar limpieza"),"POST","/cleanup/retry",false);
    }
   });
  }catch(Exception e){show(Texts.text(AdminUsersActivity.this,"Se requiere una sesión activa de administrador. Revisa tu cuenta y conexión."));}});
 }
 private void button(String id,String label,String method,String suffix,boolean delete){Button button=new Button(this);button.setText(label);layout.addView(button);button.setOnClickListener(v->{
  String message=delete?Texts.text(AdminUsersActivity.this,"Eliminar la cuenta, datos personales e historial y sus archivos del servidor. Sus dispositivos borrarán archivos al volver a contactar; las copias exportadas quedan fuera del control de Tubego. Esta acción no se puede deshacer."):suffix.equals("/block")?Texts.text(AdminUsersActivity.this,"Bloquear acceso, cancelar descargas y borrar archivos del servidor. Sus dispositivos borrarán archivos al próximo contacto. Se conserva la cuenta y su historial."):suffix.equals("/unblock")?Texts.text(AdminUsersActivity.this,"Reactivar la cuenta requiere correo verificado para usar el servicio. Deberá iniciar sesión otra vez; archivos y sesiones anteriores no se restauran."):Texts.text(AdminUsersActivity.this,"Reintentar la limpieza pendiente de archivos del servidor.");
  new AlertDialog.Builder(this).setMessage(message).setNegativeButton(Texts.text(AdminUsersActivity.this,"Cancelar"),null).setPositiveButton(Texts.text(AdminUsersActivity.this,"Confirmar"),(d,w)->act(id,method,suffix)).show();
 });}
 private void act(String id,String method,String suffix){network.execute(()->{try{JSONObject response=new ApiClient(origin).request(method,"/admin/users/"+id+suffix,null,token());runOnUiThread(()->{if(!isDestroyed()){load();if(response.optBoolean("cleanup_pending"))status.setText(Texts.text(AdminUsersActivity.this,"Acceso revocado; limpieza pendiente, se reintentará automáticamente."));}});}catch(ApiClient.ApiException e){show(e.status==409?Texts.text(AdminUsersActivity.this,"No se puede realizar: cuenta propia, último administrador, estado inválido o limpieza pendiente."):Texts.text(AdminUsersActivity.this,"No se pudo ejecutar. Revisa permisos y conexión."));}catch(Exception e){show(Texts.text(AdminUsersActivity.this,"No se pudo ejecutar. Revisa tu conexión e inténtalo otra vez."));}});}
 private void show(String text){runOnUiThread(()->{if(!isDestroyed())status.setText(text);});}
 @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
