package dev.arglabs.tubego;
import android.os.Bundle;import android.content.*;import android.widget.*;import org.json.JSONObject;import java.util.concurrent.*;
/** Role-appropriate status, never raw logs or credential exports. */
public final class DiagnosticsActivity extends LocalizedActivity {
 private final ExecutorService network=Executors.newSingleThreadExecutor();private TextView status;private NetworkMonitor monitor;
 @Override public void onCreate(Bundle state){super.onCreate(state);String origin=LanguagePreferences.origin(this);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,48,32,32);TextView title=new TextView(this);title.setText(Texts.text(this,"Diagnóstico de esta cuenta"));title.setTextSize(24);layout.addView(title);
  TextView version=new TextView(this);try{version.setText("Tubego · "+getPackageManager().getPackageInfo(getPackageName(),0).versionName);}catch(Exception e){version.setText("Tubego");}layout.addView(version);
  TextView connection=new TextView(this);layout.addView(connection);monitor=new NetworkMonitor(this,stateNow->connection.setText(Texts.text(this,!stateNow.validated?"Sin conexión validada":stateNow.wifi?"Conexión Wi-Fi":stateNow.cellular?"Conexión de datos móviles":"Otra conexión de red")));
  status=new TextView(this);layout.addView(status);Button health=new Button(this);health.setText(Texts.text(this,"Consultar versión y conexión del servidor"));layout.addView(health);health.setOnClickListener(v->{status.setText(Texts.text(this,"Comprobando conexión…"));network.execute(()->{try{JSONObject response=new ApiClient(origin).health();runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.text(this,"Conectado · versión ")+response.optString("version"));});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.error(this,e));});}});});
  Button alerts=new Button(this);alerts.setText(Texts.text(this,"Ver mis avisos"));layout.addView(alerts);alerts.setOnClickListener(v->launch("AlertsActivity",origin));
  try{JSONObject session=new SessionStore(this,origin).read();if(session!=null&&"approved".equals(session.optString("status"))&&"admin".equals(session.optString("role"))){Button admin=new Button(this);admin.setText(Texts.text(this,"Diagnóstico y mantenimiento administrativo"));layout.addView(admin);admin.setOnClickListener(v->launch("AdminCenterActivity",origin));}}catch(Exception ignored){}
  TextView note=new TextView(this);note.setText(Texts.text(this,"Este diagnóstico no mide velocidad ni descarga archivos de prueba. Los avisos y las acciones administrativas se filtran por los permisos de tu cuenta."));layout.addView(note);setContentView(layout);
 }
 private void launch(String name,String origin){try{startActivity(new Intent().setClassName(this,getPackageName()+"."+name).putExtra("server_url",origin));}catch(ActivityNotFoundException e){status.setText(Texts.text(this,"Esta acción todavía no está disponible en esta versión."));}}
 @Override protected void onDestroy(){if(monitor!=null)monitor.close();network.shutdownNow();super.onDestroy();}
}
