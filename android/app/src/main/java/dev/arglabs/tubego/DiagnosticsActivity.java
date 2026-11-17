package dev.arglabs.tubego;
import android.os.Bundle;import android.content.*;import android.widget.*;import org.json.JSONObject;import java.util.concurrent.*;
/** Role-appropriate status, never raw logs or credential exports. */
public final class DiagnosticsActivity extends LocalizedActivity {
 private final ExecutorService network=Executors.newSingleThreadExecutor();private TextView status;private NetworkMonitor monitor;
 @Override public void onCreate(Bundle state){super.onCreate(state);String origin=LanguagePreferences.origin(this);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,48,32,32);TextView title=new TextView(this);title.setText(Texts.text(this,"Diagnóstico de esta cuenta"));title.setTextSize(24);title.setTag("legacy_heading");layout.addView(title);
  TextView version=new TextView(this);try{version.setText("Tubego · "+getPackageManager().getPackageInfo(getPackageName(),0).versionName);}catch(Exception e){version.setText("Tubego");}layout.addView(version);
  TextView connection=new TextView(this);layout.addView(connection);monitor=new NetworkMonitor(this,stateNow->connection.setText(Texts.text(this,!stateNow.validated?"Sin conexión validada":stateNow.wifi?"Conexión Wi-Fi":stateNow.cellular?"Conexión de datos móviles":"Otra conexión de red")));
  try{
   JSONObject transfer=TransferDiagnostics.read(this,origin);
   TextView transferInfo=new TextView(this);
   android.app.job.JobScheduler scheduler=getSystemService(android.app.job.JobScheduler.class);
   boolean scheduled=scheduler.getPendingJob(TransferJobs.jobId(origin,false))!=null||scheduler.getPendingJob(TransferJobs.jobId(origin,true))!=null||scheduler.getPendingJob(TransferJobs.wifiJobId(origin))!=null;
   transferInfo.setText(Texts.text(this,"Transferencias del teléfono")+"\n"+Texts.text(this,"Trabajo programado: ")+(scheduled?Texts.text(this,"Sí"):Texts.text(this,"No"))+(transfer==null?"\n"+Texts.text(this,"Todavía no hay un intento registrado."):"\n"+transfer.optString("at")+"\n"+transferPhase(transfer.optString("phase"))+" · "+transferOutcome(transfer.optString("outcome"))+"\n"+transfer.optString("error_code")));
   transferInfo.setTextIsSelectable(true);layout.addView(transferInfo);
  }catch(Exception ignored){}
  status=new TextView(this);layout.addView(status);Button health=new Button(this);health.setText(Texts.text(this,"Consultar versión y conexión del servidor"));layout.addView(health);health.setOnClickListener(v->{status.setText(Texts.text(this,"Comprobando conexión…"));network.execute(()->{try{JSONObject response=new ApiClient(origin).health();runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.text(this,"Conectado · versión ")+response.optString("version"));});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.error(this,e));});}});});
  Button alerts=new Button(this);alerts.setText(Texts.text(this,"Ver mis avisos"));layout.addView(alerts);alerts.setOnClickListener(v->launch("AlertsActivity",origin));
  try{JSONObject session=new SessionStore(this,origin).read();if(session!=null&&"approved".equals(session.optString("status"))&&"admin".equals(session.optString("role"))){Button admin=new Button(this);admin.setText(Texts.text(this,"Diagnóstico y mantenimiento administrativo"));layout.addView(admin);admin.setOnClickListener(v->launch("AdminCenterActivity",origin));}}catch(Exception ignored){}
  TextView note=new TextView(this);note.setText(Texts.text(this,"Este diagnóstico no mide velocidad ni descarga archivos de prueba. Los avisos y las acciones administrativas se filtran por los permisos de tu cuenta."));layout.addView(note);setContentView(layout);
 }
 private String transferPhase(String phase){return Texts.text(this,switch(phase){case "authorizing"->"Validando sesión";case "opening_history"->"Sincronizando historial";case "device_sync"->"Consultando descargas pendientes";case "reconciling_files"->"Revisando archivos del teléfono";case "transfer_queue"->"Procesando cola de descargas";default->"Iniciando transferencia";});}
 private String transferOutcome(String value){return Texts.text(this,switch(value){case "DONE"->"Sincronización terminada";case "WAIT_WIFI"->"Esperando Wi-Fi";case "WAIT_NETWORK"->"Esperando conexión";case "WAIT_SPACE"->"Esperando espacio libre";case "STOPPED"->"Transferencia detenida";default->"Pendiente de reintento";});}
 private void launch(String name,String origin){try{startActivity(new Intent().setClassName(this,MainActivity.class.getPackage().getName()+"."+name).putExtra("server_url",origin));}catch(ActivityNotFoundException e){status.setText(Texts.text(this,"Esta acción todavía no está disponible en esta versión."));}}
 @Override protected void onDestroy(){if(monitor!=null)monitor.close();network.shutdownNow();super.onDestroy();}
}
