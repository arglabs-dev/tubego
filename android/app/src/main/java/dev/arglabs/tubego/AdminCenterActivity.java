package dev.arglabs.tubego;
import android.app.Activity;import android.content.*;import android.os.Bundle;import android.widget.*;import org.json.JSONObject;import java.util.concurrent.*;import java.util.Locale;
/** Read-only dashboard; destructive operations live in their confirmation screens. */
public final class AdminCenterActivity extends LocalizedActivity {
 private final ExecutorService network=Executors.newSingleThreadExecutor();private LinearLayout layout;private TextView status,storage;private LinearLayout storageGraph;private String origin;private int generation;
 @Override public void onCreate(Bundle state){super.onCreate(state);layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,48,32,32);status=new TextView(this);layout.addView(status);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
  try{origin=new ApiClient(getIntent().getStringExtra("server_url")).getBaseUrl();}catch(Exception e){status.setText(Texts.text(AdminCenterActivity.this,"Configura una URL HTTPS válida."));}
 }
 @Override protected void onResume(){super.onResume();if(origin!=null)load();}
 private void load(){final int check=++generation;layout.removeAllViews();layout.addView(status);status.setText(Texts.text(AdminCenterActivity.this,"Verificando permisos administrativos…"));
  if(!AdminAccess.allowed(this,origin)){status.setText(Texts.text(AdminCenterActivity.this,"Se requiere una cuenta aprobada de administrador."));return;}
  network.execute(()->{try{String token=AdminAccess.remoteToken(this,origin);runOnUiThread(()->{if(isDestroyed()||check!=generation)return;if(!AdminAccess.allowed(this,origin)){deny();return;}menu();});
   try{JSONObject snapshot=new ApiClient(origin).request("GET","/admin/storage",null,token);runOnUiThread(()->{if(isDestroyed()||check!=generation||!AdminAccess.allowed(this,origin))return;String reason=snapshot.optString("reason","");if("storage_unavailable".equals(reason)||snapshot.isNull("used_percent")){storageGraph.removeAllViews();storage.setText(Texts.text(AdminCenterActivity.this,"No se pudo medir el almacenamiento del servidor. Las descargas quedan pausadas."));return;}showStorageGraph(snapshot.optDouble("used_percent"));storage.setText(String.format(Locale.ROOT,Texts.text(AdminCenterActivity.this,"Servidor: %.1f%% ocupado · %s libres de %s\nDescargas: %s · umbral 90%% ocupado\nConsultado: %s"),snapshot.optDouble("used_percent"),bytes(snapshot.optLong("free_bytes")),bytes(snapshot.optLong("total_bytes")),snapshot.optBoolean("downloads_paused")?Texts.text(AdminCenterActivity.this,"pausadas"):Texts.text(AdminCenterActivity.this,"permitidas"),snapshot.optString("checked_at")));});}
   catch(Exception e){runOnUiThread(()->{if(!isDestroyed()&&check==generation&&storage!=null){if(e instanceof ApiClient.ApiException&&(((ApiClient.ApiException)e).status==401||((ApiClient.ApiException)e).status==403)){deny();return;}storageGraph.removeAllViews();storage.setText(Texts.text(AdminCenterActivity.this,"No se pudo consultar el almacenamiento. Revisa conexión y permisos; no se ejecutó ninguna limpieza."));}});}
  }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()&&check==generation){deny();status.setText(Texts.text(AdminCenterActivity.this,"No se pudo validar una sesión administrativa activa. Revisa tu cuenta y conexión."));}});}});
 }
 private String bytes(long value){return String.format(Locale.ROOT,Texts.text(AdminCenterActivity.this,"%.1f GiB"),value/(1024.0*1024*1024));}
 private void deny(){layout.removeAllViews();layout.addView(status);status.setText(Texts.text(AdminCenterActivity.this,"Acceso administrativo no disponible."));}
 private void menu(){
  layout.removeAllViews();layout.addView(status);
  status.setText(Texts.text(this,"Administración · permisos verificados por el servidor"));
  LinearLayout card=UiKit.card(this);layout.addView(card);
  card.addView(UiKit.heading(this,Texts.text(this,"Espacio del servidor")));
  storageGraph=Ds.column(this);card.addView(storageGraph);
  storage=new TextView(this);storage.setText(Texts.text(this,"Consultando almacenamiento real del servidor…"));card.addView(storage);
  Button refresh=new Button(this);refresh.setText(Texts.text(this,"Actualizar permisos y almacenamiento"));refresh.setOnClickListener(v->load());layout.addView(refresh);
  UiKit.sectionCard(layout,"Cuentas y acceso","Estas opciones cambian quién puede usar el servicio.");
  add(AdminRegistrationsActivity.class);add(AdminUsersActivity.class);
  UiKit.sectionCard(layout,"Descargas del servidor","Las prioridades y los plazos se aplican a los usuarios del servidor.");
  add(AdminPriorityActivity.class);add(AdminRetentionActivity.class);add(AdminDownloadErrorsActivity.class);
  UiKit.sectionCard(layout,"Servicio y mantenimiento","Comprueba el correo, libera espacio y revisa el funcionamiento del servidor.");
  add(AdminMailActivity.class);
  add(ServerCleanupActivity.class,"Limpieza global del servidor","Solo administradores · permite limpiar las copias de todos los usuarios.");
  add(MaintenanceActivity.class);
  Ds.add(layout,UiKit.text(this,Texts.text(this,"Las acciones que borran archivos, cambian accesos o reinician el servicio piden confirmación. Los permisos se verifican en el servidor."),14,Ds.MUTED),18);
 }
 private void showStorageGraph(double percent){
  storageGraph.removeAllViews();float used=(float)Math.max(0,Math.min(1,percent/100));
  Ds.add(storageGraph,Ds.segments(this,new float[]{used,1-used},new int[]{used>=.9f?Ds.ERR:Ds.BRAND,Ds.TRACK},.9f,Texts.text(this,"Uso del servidor y límite del 90 %")),LinearLayout.LayoutParams.MATCH_PARENT,Ds.dp(this,24),0,12,0,8);
  storageGraph.addView(Ds.text(this,Texts.text(this,"Ocupado · libre · marca: límite de pausa"),12,Ds.MUTED,500));
 }
 private void add(Class<?> target){ScreenInfo.Info info=ScreenInfo.forScreen(target);add(target,info.title(),info.description());}
 private void add(Class<?> target,String title,String description){
  ScreenInfo.Info info=ScreenInfo.forScreen(target);
  Ds.add(layout,Ds.option(this,info.icon(),Texts.text(this,title),Texts.text(this,description),()->{
   if(!AdminAccess.allowed(this,origin)){deny();return;}
   try{startActivity(new Intent(this,target).putExtra("server_url",origin));}catch(ActivityNotFoundException e){status.setText(Texts.text(this,"Esta función todavía no está disponible en esta versión."));}
  }),10);
 }
 @Override protected void onDestroy(){generation++;network.shutdownNow();super.onDestroy();}
}
