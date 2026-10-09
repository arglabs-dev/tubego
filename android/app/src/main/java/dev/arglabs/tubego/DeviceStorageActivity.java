package dev.arglabs.tubego;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Current-device settings; changing this does not affect backend quotas or peers. */
public final class DeviceStorageActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private TextView status;private EditText threshold;private String origin;
    private AndroidStorageGuard guard;private JSONObject session;
    @Override public void onCreate(Bundle state){super.onCreate(state);origin=getIntent().getStringExtra("server_url");
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);
        status=new TextView(this);layout.addView(status);threshold=new EditText(this);threshold.setHint("Porcentaje mínimo libre (1–90)");threshold.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);layout.addView(threshold);
        Button save=new Button(this);save.setText("Guardar umbral de este teléfono");layout.addView(save);
        Button refresh=new Button(this);refresh.setText("Actualizar espacio disponible");layout.addView(refresh);
        TextView explanation=new TextView(this);explanation.setText("Las descargas se pausan sin borrar archivos. Solo continúan cuando el archivo pendiente cabe manteniendo el porcentaje libre. Los mensajes y acciones pequeñas siguen funcionando.");layout.addView(explanation);
        Button cleanup=new Button(this);cleanup.setText("Gestionar cuenta / Liberar descargas al cerrar sesión");layout.addView(cleanup);
        cleanup.setOnClickListener(v->startActivity(new android.content.Intent(this,LoginActivity.class).putExtra("server_url",origin)));
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        try{session=new SessionStore(this,origin).read();if(session==null)throw new Exception();guard=new AndroidStorageGuard(this,origin,session.getString("user_id"),session.getString("device_id"));threshold.setText(String.valueOf(guard.settings.threshold()));}
        catch(Exception e){status.setText("Inicia sesión para configurar el almacenamiento de este dispositivo.");save.setEnabled(false);refresh.setEnabled(false);return;}
        save.setOnClickListener(v->{try{synchronized(SessionStore.class){JSONObject current=new SessionStore(this,origin).read();if(current==null||!session.optString("user_id").equals(current.optString("user_id"))||!session.optString("device_id").equals(current.optString("device_id")))throw new Exception();guard.settings.threshold(Integer.parseInt(threshold.getText().toString()));}TransferJobs.wake(this,origin,false);refresh();}catch(Exception e){status.setText("Usa un porcentaje entero entre 1 y 90 y una sesión activa.");}});
        refresh.setOnClickListener(v->refresh());refresh();
    }
    private void refresh(){io.execute(()->{try{DeviceStoragePolicy.Decision state=guard.sample(0);
        long owned=AndroidStorageGuard.used(LocalLibraryStorage.root(this,origin,session.getString("user_id"),session.getString("device_id")));
        long all=AndroidStorageGuard.used(new java.io.File(getFilesDir(),"tubego-media"));
        String message=String.format(Locale.ROOT,"Disponible: %.1f %% · %d MiB\nTubego en este teléfono: %d MiB\nEsta cuenta/dispositivo: %d MiB\nUmbral: %d %%",state.total>0?100.0*state.available/state.total:0.0,state.available/1048576,all/1048576,owned/1048576,guard.settings.threshold());
        if(state.paused)message+="\nDescargas pausadas. Libera espacio para continuar automáticamente.";
        final String result=message;runOnUiThread(()->{if(!isDestroyed())status.setText(result);});
    }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText("No se pudo consultar el almacenamiento.");});}});}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
