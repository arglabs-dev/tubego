package dev.arglabs.tubego;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import java.io.File;
import java.util.concurrent.*;
import org.json.JSONObject;
/** Durable pending/error intentions; only failed effects may be requested anew. */
public final class CommandsActivity extends Activity{
 private final ExecutorService network=Executors.newSingleThreadExecutor();private String origin,token;private File root;private LinearLayout layout;
 @Override public void onCreate(Bundle state){super.onCreate(state);layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
  try{origin=new ApiClient(getIntent().getStringExtra("server_url")).getBaseUrl();JSONObject session=new SessionStore(this,origin).read();if(session==null)throw new Exception();token=session.getString("token");root=LinkOutboxDispatch.root(this,origin,session);render();}catch(Exception e){TextView text=new TextView(this);text.setText("Inicia sesión para consultar acciones pendientes.");layout.addView(text);}}
 private void render()throws Exception{layout.removeAllViews();TextView title=new TextView(this);title.setText("Acciones pendientes y errores");layout.addView(title);boolean found=false;
  for(CommandQueue.Entry entry:new CommandQueue(root).entries())if(entry.state.equals("queued")||entry.state.equals("error")){found=true;TextView row=new TextView(this);row.setText(entry.sequence+" · "+entry.kind+" · "+(entry.state.equals("queued")?"Pendiente de sincronización":entry.error));layout.addView(row);
   if(entry.state.equals("error")){Button retry=new Button(this);retry.setText("Volver a solicitar esta acción");layout.addView(retry);retry.setOnClickListener(v->{try{synchronized(SessionStore.class){if(!token.equals(new SessionStore(this,origin).token()))return;new CommandQueue(root).retry(entry.id);}CommandDispatch.schedule(this,origin);render();}catch(Exception e){row.setText("No se pudo guardar el reintento.");}});}}
  if(!found){TextView text=new TextView(this);text.setText("Sin acciones pendientes ni errores.");layout.addView(text);}Button sync=new Button(this);sync.setText("Sincronizar ahora");layout.addView(sync);sync.setOnClickListener(v->{sync.setEnabled(false);network.execute(()->{CommandDispatch.flush(this,origin);runOnUiThread(()->{if(isDestroyed())return;try{if(!token.equals(new SessionStore(this,origin).token())){finish();return;}render();}catch(Exception e){finish();}});});});
 }
 @Override public void onDestroy(){network.shutdownNow();super.onDestroy();}
}
