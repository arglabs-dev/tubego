package dev.arglabs.tubego;
import android.app.*;
import android.os.Bundle;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Online history plus cached local resources; deletion also works offline. */
public final class ResourceDeletionActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout rows;private TextView status;private String origin;private SessionStore store;
    private final LinkedHashMap<String,String> titles=new LinkedHashMap<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);origin=getIntent().getStringExtra("server_url");
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        int p=(int)(16*getResources().getDisplayMetrics().density);layout.setPadding(p,p,p,p);
        status=new TextView(this);status.setText("Biblioteca · borrar con confirmación");layout.addView(status);
        Button refresh=new Button(this);refresh.setText("Actualizar biblioteca y enviar pendientes");layout.addView(refresh);
        rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);layout.addView(rows);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        try {store=new SessionStore(this,origin);if(store.read()==null||!"approved".equals(store.read().optString("status")))throw new Exception();}catch(Exception e){status.setText("Inicia sesión para gestionar tu biblioteca.");refresh.setEnabled(false);return;}
        refresh.setOnClickListener(v->load());load();
    }
    private void load(){worker.execute(()->{
        try {
            JSONObject session=store.read();if(session==null)return;String token=session.getString("token");
            File root=DeletionOutboxDispatch.root(this,origin,session);LinkedHashMap<String,String> result=new LinkedHashMap<>();
            synchronized(SessionStore.class){if(!token.equals(store.token()))return;
                File[] cached=root.listFiles((d,n)->n.endsWith(".properties"));if(cached!=null)for(File file:cached){
                    if(java.nio.file.Files.isSymbolicLink(file.toPath()))continue;
                    Properties props=new Properties();try(FileInputStream in=new FileInputStream(file)){props.load(in);}
                    String id=props.getProperty("id");if(id!=null)result.put(UUID.fromString(id).toString(),props.getProperty("title","Archivo"));
                }
            }
            try {
                ApiClient api=new ApiClient(origin);String cursor="";
                do {JSONObject page=api.request("GET","/resources?limit=50&cursor="+java.net.URLEncoder.encode(cursor,"UTF-8"),null,token);
                    JSONArray list=page.getJSONArray("items");for(int i=0;i<list.length();i++){JSONObject row=list.getJSONObject(i);result.put(row.getString("id"),row.optString("title","Archivo"));}
                    cursor=page.isNull("next_cursor")?"":page.getString("next_cursor");
                }while(!cursor.isEmpty());
            }catch(Exception ignored){ /* Cached files remain manageable without a network. */ }
            if(!token.equals(store.token()))return;
            runOnUiThread(()->{try{if(!token.equals(store.token())||isDestroyed())return;titles.clear();titles.putAll(result);render();}catch(Exception ignored){rows.removeAllViews();}});
            DeletionOutboxDispatch.schedule(this,origin);DeletionOutboxDispatch.flush(this);
        }catch(Exception e){message("No se pudo leer la biblioteca de esta cuenta.");}
    });}
    private void render(){rows.removeAllViews();if(titles.isEmpty())status.setText("No hay recursos disponibles en la biblioteca.");
        for(Map.Entry<String,String> row:titles.entrySet()){
            TextView title=new TextView(this);title.setText(row.getValue());rows.addView(title);
            Button delete=new Button(this);delete.setText("Borrar…");rows.addView(delete);delete.setOnClickListener(v->
                new AlertDialog.Builder(this).setTitle("Borrar "+row.getValue()).setMessage("Se borrará en todos tus dispositivos. El historial se conserva. Los equipos sin conexión lo harán al reconectar.")
                    .setNeutralButton("Cancelar",null)
                    .setNegativeButton("Solo dispositivos",(d,w)->confirm(row.getKey(),"devices"))
                    .setPositiveButton("Dispositivos y servidor",(d,w)->confirm(row.getKey(),"devices_and_server")).show());
        }
    }
    private void confirm(String id,String scope){worker.execute(()->{try{
        JSONObject session=store.read();if(session==null)throw new Exception();String token=session.getString("token");
        TransferRuntime.stopOrigin(origin);
        synchronized(SessionStore.class){if(!token.equals(store.token()))return;
            DeletionOutboxDispatch.box(this,origin,session).add(id,scope);
            LocalResourceDeletion.apply(DeletionOutboxDispatch.root(this,origin,session),id);
        }
        DeletionOutboxDispatch.schedule(this,origin);message("Borrado local. La orden para los demás dispositivos se enviará con cualquier conexión.");
        DeletionOutboxDispatch.flush(this);
    }catch(Exception e){message("No se completó la limpieza local. Los borrados pendientes se reintentarán al conectar.");}});}
    private void message(String text){runOnUiThread(()->{if(!isDestroyed())status.setText(text);});}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
