package dev.arglabs.tubego;
import android.app.Activity;import android.content.*;import android.os.Bundle;import android.widget.*;import org.json.JSONObject;import java.util.concurrent.*;import java.util.Locale;
/** Read-only dashboard; destructive operations live in their confirmation screens. */
public final class AdminCenterActivity extends Activity {
 private final ExecutorService network=Executors.newSingleThreadExecutor();private LinearLayout layout;private TextView status,storage;private String origin;private int generation;
 @Override public void onCreate(Bundle state){super.onCreate(state);layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,48,32,32);status=new TextView(this);layout.addView(status);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
  try{origin=new ApiClient(getIntent().getStringExtra("server_url")).getBaseUrl();}catch(Exception e){status.setText("Configura una URL HTTPS válida.");}
 }
 @Override protected void onResume(){super.onResume();if(origin!=null)load();}
 private void load(){final int check=++generation;layout.removeAllViews();layout.addView(status);status.setText("Verificando permisos administrativos…");
  if(!AdminAccess.allowed(this,origin)){status.setText("Se requiere una cuenta aprobada de administrador.");return;}
  network.execute(()->{try{String token=AdminAccess.remoteToken(this,origin);runOnUiThread(()->{if(isDestroyed()||check!=generation)return;if(!AdminAccess.allowed(this,origin)){deny();return;}menu();});
   try{JSONObject snapshot=new ApiClient(origin).request("GET","/admin/storage",null,token);runOnUiThread(()->{if(isDestroyed()||check!=generation||!AdminAccess.allowed(this,origin))return;String reason=snapshot.optString("reason","");if("storage_unavailable".equals(reason)||snapshot.isNull("used_percent")){storage.setText("No se pudo medir el almacenamiento del servidor. Las descargas quedan pausadas.");return;}storage.setText(String.format(Locale.ROOT,"Servidor: %.1f%% ocupado · %s libres de %s\nDescargas: %s · umbral 90%% ocupado\nConsultado: %s",snapshot.optDouble("used_percent"),bytes(snapshot.optLong("free_bytes")),bytes(snapshot.optLong("total_bytes")),snapshot.optBoolean("downloads_paused")?"pausadas":"permitidas",snapshot.optString("checked_at")));});}
   catch(Exception e){runOnUiThread(()->{if(!isDestroyed()&&check==generation&&storage!=null){if(e instanceof ApiClient.ApiException&&(((ApiClient.ApiException)e).status==401||((ApiClient.ApiException)e).status==403)){deny();return;}storage.setText("No se pudo consultar el almacenamiento. Revisa conexión y permisos; no se ejecutó ninguna limpieza.");}});}
  }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()&&check==generation){deny();status.setText("No se pudo validar una sesión administrativa activa. Revisa tu cuenta y conexión.");}});}});
 }
 private static String bytes(long value){return String.format(Locale.ROOT,"%.1f GiB",value/(1024.0*1024*1024));}
 private void deny(){layout.removeAllViews();layout.addView(status);status.setText("Acceso administrativo no disponible.");}
 private void menu(){layout.removeAllViews();layout.addView(status);status.setText("Administración · permisos verificados por el servidor");
  storage=new TextView(this);storage.setText("Consultando almacenamiento real del servidor…");layout.addView(storage);Button refresh=new Button(this);refresh.setText("Actualizar permisos y almacenamiento");refresh.setOnClickListener(v->load());layout.addView(refresh);
  add("Solicitudes verificadas · Aprobar / Rechazar","AdminRegistrationsActivity");add("Usuarios · Bloquear / Eliminar / Estado y rol","AdminUsersActivity");add("Prioridades de usuarios · Persistentes 3:1","AdminPriorityActivity");add("Conservación por usuario y plazos globales","AdminRetentionActivity");add("Avisos administrativos","AlertsActivity");add("Errores de descarga del servidor","AdminDownloadErrorsActivity");add("Limpiar archivos del servidor…","ServerCleanupActivity");add("Mantenimiento del servicio…","MaintenanceActivity");
  TextView explanation=new TextView(this);explanation.setText("La limpieza y los cambios de conservación aplican al servidor. Bloquear o eliminar usuarios revoca su acceso y ordena borrar sus copias locales al próximo contacto. Cada acción destructiva pide confirmación en su pantalla. No se permite administrar sin conexión.");layout.addView(explanation);
 }
 private void add(String label,String name){Button button=new Button(this);button.setText(label);Intent intent=new Intent().setClassName(this,getPackageName()+"."+name).putExtra("server_url",origin);boolean installed=intent.resolveActivity(getPackageManager())!=null;
  button.setEnabled(installed);if(!installed)button.setText(label+" · no disponible en esta versión");layout.addView(button);button.setOnClickListener(v->{if(!AdminAccess.allowed(this,origin)){deny();return;}try{startActivity(intent);}catch(ActivityNotFoundException e){status.setText("Esta función todavía no está disponible en esta versión.");}});
 }
 @Override protected void onDestroy(){generation++;network.shutdownNow();super.onDestroy();}
}
