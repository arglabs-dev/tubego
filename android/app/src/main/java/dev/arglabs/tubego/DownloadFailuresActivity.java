package dev.arglabs.tubego;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.*;

/** Own server errors and a route to local retry controls. */
public final class DownloadFailuresActivity extends Activity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();private LinearLayout layout;private TextView status;private String origin;
    @Override public void onCreate(Bundle state){super.onCreate(state);origin=getIntent().getStringExtra("server_url");layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);status=new TextView(this);layout.addView(status);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);}
    @Override protected void onResume(){super.onResume();load();}
    private boolean active(String token){try{return token!=null&&token.equals(new SessionStore(this,origin).token());}catch(Exception e){return false;}}
    private void load(){layout.removeAllViews();layout.addView(status);status.setText("Consultando fallos del servidor…");Button local=new Button(this);local.setText("Descargas de este teléfono / Reintentar");layout.addView(local);local.setOnClickListener(v->startActivity(new android.content.Intent(this,NetworkPolicyActivity.class).putExtra("server_url",origin)));
        network.execute(()->{try{String token=new SessionStore(this,origin).token();var result=new ApiClient(origin).request("GET","/tasks?limit=100",null,token);var items=result.getJSONArray("items");runOnUiThread(()->{if(isDestroyed()||!active(token))return;status.setText("Fallos definitivos del servidor. Cada reintento manual inicia un nuevo presupuesto de tres reintentos automáticos.");for(int i=0;i<items.length();i++){JSONObject task=items.optJSONObject(i);if(!"failed".equals(task.optString("status")))continue;TextView description=new TextView(this);description.setText(task.optString("resource_id")+"\n"+task.optString("error_code")+" · "+task.optString("error_message"));layout.addView(description);Button retry=new Button(this);retry.setText("Reintentar en el servidor");layout.addView(retry);retry.setOnClickListener(v->{retry.setEnabled(false);network.execute(()->{try{if(!active(token))throw new Exception();new ApiClient(origin).request("POST","/tasks/"+task.optString("id")+"/retry",null,token);runOnUiThread(()->{if(!isDestroyed())load();});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){status.setText("No se pudo reintentar. Revisa la conexión y tu sesión.");retry.setEnabled(true);}});}});});}});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText("No se pudieron consultar errores del servidor. Las descargas locales siguen disponibles en el botón de este teléfono.");});}});
    }
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
