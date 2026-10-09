package dev.arglabs.tubego;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.*;
import org.json.JSONObject;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Disk-only library. Opening a player never enqueues or streams a download. */
public final class LocalLibraryActivity extends Activity {
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private LinearLayout list;private TextView status;private String origin;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,24,24,24);
        TextView title=new TextView(this);title.setText("Biblioteca del teléfono");title.setTextSize(24);layout.addView(title);
        status=new TextView(this);layout.addView(status);
        Button refresh=new Button(this);refresh.setText("Actualizar lista local");refresh.setOnClickListener(v->load());layout.addView(refresh);
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);layout.addView(scroll);setContentView(layout);
        try{origin=new ApiClient(getIntent().getStringExtra("server_url")).getBaseUrl();}catch(Exception e){status.setText("Servidor inválido");return;}
        load();
    }
    private void load(){if(origin==null)return;status.setText("Leyendo archivos del teléfono…");disk.execute(()->{
        List<TransferRecord> records=new ArrayList<>();String error=null;
        try {synchronized(SessionStore.class){JSONObject session=new SessionStore(this,origin).read();
            if(session==null || !"approved".equals(session.optString("status")))throw new Exception("Inicia sesión con una cuenta aprobada.");
            File root=LocalLibraryStorage.root(this,origin,session.getString("user_id"),session.getString("device_id"));File[] files=root.listFiles((d,n)->n.endsWith(".properties"));
            if(files!=null)for(File file:files){try{records.add(OfflineMediaAccess.record(root,file.getName().replace(".properties","")));}catch(Exception ignored){}}
        }}catch(Exception e){error=e.getMessage();}
        records.sort(Comparator.comparing((TransferRecord r)->r.createdAt).reversed());final String message=error;
        runOnUiThread(()->{if(isDestroyed())return;list.removeAllViews();status.setText(message!=null?message:records.isEmpty()?"No hay archivos descargados en este teléfono.":"Reproducción local. No requiere conexión.");
            for(TransferRecord r:records){TextView name=new TextView(this);name.setText(r.title);list.addView(name);Button play=new Button(this);boolean available=false;try{available=OfflineMediaAccess.available(r);}catch(Exception ignored){}
                play.setText(available?"Reproducir · "+r.mediaFormat:"complete".equals(r.state)?"Archivo local ausente":"No disponible localmente · "+r.state);play.setEnabled(available);play.setOnClickListener(v->open(r.id));list.addView(play);}
        });
    });}
    private void open(String id){status.setText("Verificando archivo local…");disk.execute(()->{
        try{Uri uri=PrivateMediaContentProvider.create(this,origin,id);String mime=getContentResolver().getType(uri);
            runOnUiThread(()->{if(isDestroyed())return;Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                view.setClipData(ClipData.newRawUri("Tubego",uri));
                List<ResolveInfo> players=getPackageManager().queryIntentActivities(view,0);
                if(players.isEmpty()){new AlertDialog.Builder(this).setMessage("No hay un reproductor compatible. Instala VLC u otro reproductor que admita archivos locales y vuelve a intentarlo.").setPositiveButton("Aceptar",null).show();status.setText("Archivo conservado en el teléfono.");return;}
                Intent chooser=Intent.createChooser(view,"Reproducir archivo local");
                for(ResolveInfo player:players)if("org.videolan.vlc".equals(player.activityInfo.packageName)){
                    Intent vlc=new Intent(view).setPackage("org.videolan.vlc");chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,new android.os.Parcelable[]{vlc});break;}
                try{startActivity(chooser);status.setText("PiP y audio con pantalla bloqueada dependen del reproductor elegido.");}catch(ActivityNotFoundException e){status.setText("El reproductor ya no está disponible.");}
            });
        }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText("No se pudo abrir el archivo local: "+e.getMessage());});}
    });}
    @Override protected void onDestroy(){disk.shutdownNow();super.onDestroy();}
}
