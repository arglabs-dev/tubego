package dev.arglabs.tubego;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Current-device settings; changing this does not affect backend quotas or peers. */
public final class DeviceStorageActivity extends LocalizedActivity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private TextView folderStatus;
    private LinearLayout chart;private TextView status;private EditText threshold;private String origin;
    private AndroidStorageGuard guard;private JSONObject session;
    @Override public void onCreate(Bundle state){super.onCreate(state);origin=getIntent().getStringExtra("server_url");
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);
        LinearLayout card=UiKit.card(this);layout.addView(card);card.addView(UiKit.heading(this,Texts.text(this,"Espacio disponible")));
        chart=Ds.column(this);card.addView(chart);status=new TextView(this);card.addView(status);
        UiKit.sectionCard(layout,"Reserva de espacio libre","Si el teléfono baja de este porcentaje, se pausan las descargas; no se borra ningún archivo.");
        layout.addView(UiKit.heading(this,Texts.text(this,"Porcentaje mínimo libre (1–90)")));
        threshold=new EditText(this);threshold.setHint(Texts.text(DeviceStorageActivity.this,"Porcentaje mínimo libre (1–90)"));threshold.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);layout.addView(threshold);
        Button save=new Button(this);save.setText(Texts.text(DeviceStorageActivity.this,"Guardar umbral de este teléfono"));UiKit.button(save,true);layout.addView(save);
        Button refresh=new Button(this);refresh.setText(Texts.text(DeviceStorageActivity.this,"Actualizar espacio disponible"));layout.addView(refresh);
        TextView explanation=new TextView(this);explanation.setText(Texts.text(DeviceStorageActivity.this,"Las descargas se pausan sin borrar archivos. Solo continúan cuando el archivo pendiente cabe manteniendo el porcentaje libre. Los mensajes y acciones pequeñas siguen funcionando."));layout.addView(explanation);
        TextView folderHeading=UiKit.heading(this,Texts.text(this,"Carpeta de descargas"));layout.addView(folderHeading);
        folderStatus=new TextView(this);folderStatus.setTextIsSelectable(true);layout.addView(folderStatus);
        Button choose=new Button(this);choose.setText(Texts.text(this,"Elegir carpeta"));layout.addView(choose);
        Button defaults=new Button(this);defaults.setText(Texts.text(this,"Usar Descargas/TubeGo"));layout.addView(defaults);
        layout.addView(UiKit.text(this,Texts.text(this,"Las descargas nuevas se guardan como una sola copia. Puedes modificarlas con el gestor de archivos. Los archivos compartidos se conservan al cerrar sesión o desinstalar TubeGo."),14,UiKit.MUTED));
        choose.setOnClickListener(v->{android.content.Intent picker=new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE);picker.putExtra(android.content.Intent.EXTRA_LOCAL_ONLY,true);picker.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION|android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION|android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(picker,4812);});
        defaults.setOnClickListener(v->{try{SharedMedia.folder(this,origin,session,"");showFolder();}catch(Exception error){status.setText(Texts.error(this,error));}});
        Button cleanup=new Button(this);cleanup.setText(Texts.text(DeviceStorageActivity.this,"Gestionar mi cuenta y sesiones"));layout.addView(cleanup);
        cleanup.setOnClickListener(v->startActivity(new android.content.Intent(this,LoginActivity.class).putExtra("server_url",origin)));
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        try{session=new SessionStore(this,origin).read();if(session==null)throw new Exception();guard=new AndroidStorageGuard(this,origin,session.getString("user_id"),session.getString("device_id"));threshold.setText(String.valueOf(guard.settings.threshold()));}
        catch(Exception e){status.setText(Texts.text(DeviceStorageActivity.this,"Inicia sesión para configurar el almacenamiento de este dispositivo."));save.setEnabled(false);refresh.setEnabled(false);return;}
        showFolder();
        save.setOnClickListener(v->{try{synchronized(SessionStore.class){JSONObject current=new SessionStore(this,origin).read();if(current==null||!session.optString("user_id").equals(current.optString("user_id"))||!session.optString("device_id").equals(current.optString("device_id")))throw new Exception();guard.settings.threshold(Integer.parseInt(threshold.getText().toString()));}TransferJobs.wake(this,origin,false);refresh();}catch(Exception e){status.setText(Texts.text(DeviceStorageActivity.this,"Usa un porcentaje entero entre 1 y 90 y una sesión activa."));}});
        refresh.setOnClickListener(v->refresh());refresh();
    }
    private void showFolder(){try{String tree=SharedMedia.tree(this,origin,session);folderStatus.setText(SharedMedia.folderLocation(tree));}catch(Exception e){folderStatus.setText(Texts.error(this,e));}}
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){super.onActivityResult(request,result,data);
        if(request!=4812||result!=RESULT_OK||data==null||data.getData()==null)return;
        try{org.json.JSONObject current=new SessionStore(this,origin).read();if(current==null||!session.getString("token").equals(current.getString("token")))return;
            int flags=data.getFlags()&(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION|android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if((flags&android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0)throw new java.io.IOException("La carpeta requiere permiso de escritura");
            getContentResolver().takePersistableUriPermission(data.getData(),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION|android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);SharedMedia.folder(this,origin,session,data.getData().toString());showFolder();
        }catch(Exception e){status.setText(Texts.error(this,e));}
    }
    private void refresh(){io.execute(()->{try{DeviceStoragePolicy.Decision state=guard.sample(0);
        long owned=AndroidStorageGuard.used(LocalLibraryStorage.root(this,origin,session.getString("user_id"),session.getString("device_id")));
        long all=AndroidStorageGuard.used(new java.io.File(getFilesDir(),"tubego-media"));
        String message=String.format(Locale.ROOT,Texts.text(DeviceStorageActivity.this,"Disponible: %.1f %% · %d MiB\nTubego en este teléfono: %d MiB\nEsta cuenta/dispositivo: %d MiB\nUmbral: %d %%"),state.total>0?100.0*state.available/state.total:0.0,state.available/1048576,all/1048576,owned/1048576,guard.settings.threshold());
        if(state.paused)message+=Texts.text(DeviceStorageActivity.this,"\nDescargas pausadas. Libera espacio para continuar automáticamente.");
        final String result=message;runOnUiThread(()->{if(!isDestroyed()){status.setText(result);chart.removeAllViews();float free=state.total>0?(float)Math.max(0,Math.min(1,(double)state.available/state.total)):0;
            Ds.add(chart,Ds.segments(this,new float[]{1-free,free},new int[]{state.paused?Ds.WARN:Ds.BRAND,Ds.TRACK},1-guard.settings.threshold()/100f,Texts.text(this,"Espacio ocupado, libre y reserva mínima")),LinearLayout.LayoutParams.MATCH_PARENT,Ds.dp(this,24),0,12,0,8);
            chart.addView(Ds.text(this,Texts.text(this,"Ocupado · libre · marca: reserva mínima"),12,Ds.MUTED,500));}});
    }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.text(DeviceStorageActivity.this,"No se pudo consultar el almacenamiento."));});}});}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
