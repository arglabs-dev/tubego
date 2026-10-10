package dev.arglabs.tubego;
import android.app.*;
import android.os.Bundle;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Explicit server-only scopes; never deletes local files or resource history. */
public final class ServerCleanupActivity extends LocalizedActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String origin;private SessionStore store;private LinearLayout pending;private TextView status;private JSONObject ownPreview,globalPreview;
    @Override public void onCreate(Bundle state){super.onCreate(state);origin=getIntent().getStringExtra("server_url");
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);int pad=(int)(16*getResources().getDisplayMetrics().density);layout.setPadding(pad,pad,pad,pad);
        status=new TextView(this);status.setText(Texts.text(ServerCleanupActivity.this,"Limpieza del servidor · conserva teléfono e historial"));layout.addView(status);
        Button own=new Button(this);own.setText(Texts.text(ServerCleanupActivity.this,"Limpiar mis archivos del servidor"));layout.addView(own);
        Button global=new Button(this);global.setText(Texts.text(ServerCleanupActivity.this,"Limpiar todos los archivos móviles del servidor (administración)"));layout.addView(global);
        Button refresh=new Button(this);refresh.setText(Texts.text(ServerCleanupActivity.this,"Actualizar estimaciones / Enviar pendientes"));layout.addView(refresh);
        pending=new LinearLayout(this);pending.setOrientation(LinearLayout.VERTICAL);layout.addView(pending);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        try{store=new SessionStore(this,origin);JSONObject session=store.read();if(session==null||!"approved".equals(session.optString("status")))throw new Exception();global.setVisibility("admin".equals(session.optString("role"))?android.view.View.VISIBLE:android.view.View.GONE);}
        catch(Exception e){own.setEnabled(false);global.setEnabled(false);refresh.setEnabled(false);status.setText(Texts.text(ServerCleanupActivity.this,"Inicia sesión con una cuenta aprobada."));return;}
        own.setOnClickListener(v->confirm("own",ownPreview));global.setOnClickListener(v->confirm("global",globalPreview));refresh.setOnClickListener(v->load());load();
    }
    private void confirm(String scope,JSONObject preview){String label=scope.equals("own")?Texts.text(ServerCleanupActivity.this,"solo tus archivos del servidor"):Texts.text(ServerCleanupActivity.this,"los archivos móviles de TODOS los usuarios del servidor");
        String estimate=preview==null?Texts.text(ServerCleanupActivity.this,"Cantidad y tamaño no disponibles sin conexión."):Texts.text(ServerCleanupActivity.this,"Estimación guardada: ")+preview.optInt("files_known")+Texts.text(ServerCleanupActivity.this," archivos conocidos · ")+preview.optLong("bytes_known")+Texts.text(ServerCleanupActivity.this," bytes · ")+preview.optInt("in_progress")+Texts.text(ServerCleanupActivity.this," tareas en curso · ")+preview.optInt("sizes_unknown")+Texts.text(ServerCleanupActivity.this," archivos sin tamaño conocido.");
        new AlertDialog.Builder(this).setTitle(Texts.text(ServerCleanupActivity.this,"Confirmar limpieza del servidor")).setMessage(Texts.text(ServerCleanupActivity.this,"Se eliminarán ")+label+Texts.text(ServerCleanupActivity.this," al ejecutar la orden. ")+estimate+Texts.text(ServerCleanupActivity.this," Los parciales también se limpian y las tareas se cancelan. Tus copias del teléfono y el historial se conservarán. El bot de Telegram mantiene su almacenamiento independiente.")).setNegativeButton(Texts.text(ServerCleanupActivity.this,"Cancelar"),null).setPositiveButton(Texts.text(ServerCleanupActivity.this,"Limpiar servidor"),(d,w)->save(scope)).show();
    }
    private void save(String scope){worker.execute(()->{try{JSONObject session=store.read();if(session==null)return;String token=session.getString("token");
        synchronized(SessionStore.class){if(!token.equals(store.token()))return;ServerCleanupOutboxDispatch.box(this,origin,session).add(scope);}
        TransferRuntime.stopOrigin(origin);ServerCleanupOutboxDispatch.schedule(this,origin);message(Texts.text(ServerCleanupActivity.this,"Limpieza guardada. Se enviará con cualquier conexión y se comprobarán los permisos al ejecutarse."));ServerCleanupOutboxDispatch.flush(this);load();
    }catch(Exception e){message(Texts.text(ServerCleanupActivity.this,"No se pudo guardar la limpieza. Revisa tu sesión y almacenamiento."));}});}
    private void load(){worker.execute(()->{try{JSONObject session=store.read();if(session==null)return;String token=session.getString("token");File root=ServerCleanupOutboxDispatch.root(this,origin,session);
        JSONObject own=readPreview(root,"own"),global=readPreview(root,"global");ApiClient api=new ApiClient(origin);
        try{JSONObject result=api.request("GET","/server-cleanup/preview?scope=own",null,token);synchronized(SessionStore.class){if(!token.equals(store.token()))return;savePreview(root,"own",result);}own=result;}catch(Exception ignored){}
        if("admin".equals(session.optString("role")))try{JSONObject result=api.request("GET","/server-cleanup/preview?scope=global",null,token);synchronized(SessionStore.class){if(!token.equals(store.token()))return;savePreview(root,"global",result);}global=result;}catch(Exception ignored){}
        JSONObject ownResult=own,globalResult=global;java.util.List<ServerCleanupOutbox.Entry> entries=ServerCleanupOutboxDispatch.box(this,origin,session).entries();
        runOnUiThread(()->{try{if(isDestroyed()||!token.equals(store.token()))return;ownPreview=ownResult;globalPreview=globalResult;pending.removeAllViews();
            for(ServerCleanupOutbox.Entry entry:entries){TextView item=new TextView(this);item.setText((entry.scope.equals("global")?Texts.text(ServerCleanupActivity.this,"Todos los usuarios"):Texts.text(ServerCleanupActivity.this,"Solo mi servidor"))+" · "+entry.state+(entry.error.isEmpty()?"":"\n"+entry.error));pending.addView(item);
                if(entry.state.equals("error")){Button retry=new Button(this);retry.setText(Texts.text(ServerCleanupActivity.this,"Reintentar con los permisos actuales"));pending.addView(retry);retry.setOnClickListener(v->worker.execute(()->{try{synchronized(SessionStore.class){if(!token.equals(store.token()))return;ServerCleanupOutboxDispatch.box(this,origin,session).retry(entry.id);}ServerCleanupOutboxDispatch.schedule(this,origin);ServerCleanupOutboxDispatch.flush(this);load();}catch(Exception e){message(Texts.text(ServerCleanupActivity.this,"No se pudo reintentar."));}}));}
            }
        }catch(Exception ignored){pending.removeAllViews();}});
        ServerCleanupOutboxDispatch.schedule(this,origin);ServerCleanupOutboxDispatch.flush(this);
    }catch(Exception e){message(Texts.text(ServerCleanupActivity.this,"No se pudo cargar la información de esta cuenta."));}});}
    private JSONObject readPreview(File root,String scope)throws Exception{File file=new File(root,"server-preview-"+scope+".json");if(!file.isFile()||Files.isSymbolicLink(file.toPath()))return null;return new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));}
    private void savePreview(File root,String scope,JSONObject value)throws Exception{if(!root.isDirectory()&&!root.mkdirs())throw new IOException();File temp=new File(root,"server-preview-"+scope+".tmp"),target=new File(root,"server-preview-"+scope+".json");try(FileOutputStream out=new FileOutputStream(temp)){out.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    private void message(String text){runOnUiThread(()->{if(!isDestroyed())status.setText(text);});}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
