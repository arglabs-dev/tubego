package dev.arglabs.tubego;
import android.app.*;
import android.os.Bundle;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Permanent history and explicit re-requests; all commands can be saved offline. */
public final class ResubmitActivity extends LocalizedActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String origin,selected;private SessionStore store;private TextView status;private LinearLayout rows;
    @Override public void onCreate(Bundle state){super.onCreate(state);origin=getIntent().getStringExtra("server_url");selected=getIntent().getStringExtra("resource_id");
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);int p=(int)(16*getResources().getDisplayMetrics().density);layout.setPadding(p,p,p,p);
        status=new TextView(this);status.setText(Texts.text(ResubmitActivity.this,"Historial / Volver a solicitar"));layout.addView(status);
        Button refresh=new Button(this);refresh.setText(Texts.text(ResubmitActivity.this,"Actualizar / Enviar pendientes"));layout.addView(refresh);rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);layout.addView(rows);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        try{store=new SessionStore(this,origin);JSONObject session=store.read();if(session==null||!"approved".equals(session.optString("status")))throw new Exception();}
        catch(Exception e){status.setText(Texts.text(ResubmitActivity.this,"Inicia sesión con una cuenta aprobada para consultar tu biblioteca."));refresh.setEnabled(false);return;}
        refresh.setOnClickListener(v->load());load();
    }
    private void load(){worker.execute(()->{try{
        JSONObject session=store.read();if(session==null)return;String token=session.getString("token");File root=RecoveryOutboxDispatch.root(this,origin,session),cache=new File(root,"resource-history.json");JSONArray resources=new JSONArray();
        synchronized(SessionStore.class){if(!token.equals(store.token()))return;if(cache.isFile()&&!Files.isSymbolicLink(cache.toPath()))resources=new JSONArray(new String(Files.readAllBytes(cache.toPath()),java.nio.charset.StandardCharsets.UTF_8));}
        try{
            JSONArray fresh=new JSONArray();ApiClient api=new ApiClient(origin);String cursor="";
            do{JSONObject response=api.request("GET","/resources?limit=10&cursor="+java.net.URLEncoder.encode(cursor,"UTF-8"),null,token);JSONArray page=response.getJSONArray("items");for(int i=0;i<page.length();i++)fresh.put(page.getJSONObject(i));cursor=response.isNull("next_cursor")?"":response.getString("next_cursor");}while(!cursor.isEmpty());
            synchronized(SessionStore.class){if(!token.equals(store.token()))return;if(!root.isDirectory()&&!root.mkdirs())throw new IOException();File temp=new File(root,"resource-history.tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(fresh.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}Files.move(temp.toPath(),cache.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            resources=fresh;
        }catch(Exception ignored){/* The permanent cached history remains usable offline. */}
        JSONArray result=resources;
        runOnUiThread(()->{try{if(!token.equals(store.token())||isDestroyed())return;render(result);}catch(Exception ignored){rows.removeAllViews();}});
        RecoveryOutboxDispatch.schedule(this,origin);RecoveryOutboxDispatch.flush(this);
    }catch(Exception e){message(Texts.text(ResubmitActivity.this,"No se pudo leer el historial de esta cuenta."));}});}
    private void render(JSONArray history)throws Exception{rows.removeAllViews();int shown=0;
        for(int i=0;i<history.length();i++){JSONObject resource=history.getJSONObject(i);String id=resource.getString("id");if(selected!=null&&!java.util.UUID.fromString(selected).equals(java.util.UUID.fromString(id)))continue;shown++;
            TextView card=new TextView(this);String title=resource.isNull("title")?Texts.text(ResubmitActivity.this,"Archivo"):resource.optString("title",Texts.text(ResubmitActivity.this,"Archivo"));
            card.setText(title+"\n"+resource.optString("source_url")+"\n"+Texts.quality(this,"audio".equals(resource.optString("media_format"))?"audio":resource.optString("quality","best"))+(resource.isNull("duration_seconds")?"":" · "+Math.round(resource.getDouble("duration_seconds"))+Texts.text(ResubmitActivity.this," segundos"))+"\n"+(resource.isNull("server_deleted_at")?Texts.text(ResubmitActivity.this,"Consulta disponibilidad al solicitar"):Texts.text(ResubmitActivity.this,"La copia del servidor ya no está disponible")));rows.addView(card);
            Button request=new Button(this);request.setText(Texts.text(ResubmitActivity.this,"Volver a solicitar / Descargar"));rows.addView(request);
            request.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(Texts.text(ResubmitActivity.this,"Solicitar ")+title).setMessage(Texts.text(ResubmitActivity.this,"Se recuperará esta variante sin crear otra ficha. Tus dispositivos que ya tengan el archivo completo no lo descargarán otra vez. Si lo habías borrado, autorizas descargarlo de nuevo en este teléfono; tus otros dispositivos necesitarán su propia aprobación.")).setNegativeButton(Texts.text(ResubmitActivity.this,"Cancelar"),null).setPositiveButton(Texts.text(ResubmitActivity.this,"Solicitar"),(d,w)->save(id)).show());
        }
        if(shown==0)status.setText(Texts.text(ResubmitActivity.this,"No hay fichas disponibles. Conecta para actualizar el historial."));
    }
    private void save(String id){worker.execute(()->{try{JSONObject session=store.read();if(session==null)return;String token=session.getString("token");
        synchronized(SessionStore.class){if(!token.equals(store.token()))return;RecoveryOutboxDispatch.box(this,origin,session).add(id,"approved");}
        RecoveryOutboxDispatch.schedule(this,origin);message(Texts.text(ResubmitActivity.this,"Solicitud guardada. Se enviará con cualquier conexión; el archivo respetará Wi-Fi o tu permiso de datos."));RecoveryOutboxDispatch.flush(this);
    }catch(Exception e){message(Texts.text(ResubmitActivity.this,"No se pudo guardar la solicitud. Revisa sesión y almacenamiento."));}});}
    private void message(String text){runOnUiThread(()->{if(!isDestroyed())status.setText(text);});}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
