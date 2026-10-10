package dev.arglabs.tubego;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Account preferences, ready to launch from the authenticated URL-entry screen. */
public final class MediaPreferencesActivity extends LocalizedActivity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private CheckBox ask; private Spinner choice; private EditText rewind;
    private TextView status; private Button save;
    private ApiClient api;
    private String token;
    private LocalMediaPreferences local;
    private CommandQueue commands;
    private long editVersion=0;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding * 2, padding, padding);
        TextView title = new TextView(this); title.setText(Texts.text(MediaPreferencesActivity.this,"Preferencias de reproducción y descarga")); title.setTextSize(22); layout.addView(title);
        ask = new CheckBox(this); ask.setText(Texts.text(MediaPreferencesActivity.this,"Preguntar calidad o audio para cada enlace")); ask.setChecked(true); layout.addView(ask);
        choice = new Spinner(this); choice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Texts.labels(this,MediaSelection.LABELS)));
        choice.setSelection(MediaSelection.index("720")); layout.addView(choice);
        TextView rewindLabel = new TextView(this); rewindLabel.setText(Texts.text(MediaPreferencesActivity.this,"Retroceso al continuar (0 a 120 segundos; requiere reproductor compatible)")); layout.addView(rewindLabel);
        rewind = new EditText(this); rewind.setInputType(InputType.TYPE_CLASS_NUMBER); rewind.setText("10"); layout.addView(rewind);
        status = new TextView(this); layout.addView(status);
        save = new Button(this); save.setText(Texts.text(MediaPreferencesActivity.this,"Guardar preferencias")); save.setEnabled(false); layout.addView(save);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        try {
            api = new ApiClient(getIntent().getStringExtra("server_url"));
            JSONObject session=new SessionStore(this,api.getBaseUrl()).read();
            if(session==null||!"approved".equals(session.optString("status")))throw new Exception();
            token=session.getString("token");local=new LocalMediaPreferences(LinkOutboxDispatch.root(this,api.getBaseUrl(),session));
            commands=new CommandQueue(LinkOutboxDispatch.root(this,api.getBaseUrl(),session));
            JSONObject cache=local.read();ask.setChecked(cache.optBoolean("ask_every_time",true));choice.setSelection(MediaSelection.index(cache.optString("selection","720")));rewind.setText(String.valueOf(cache.optInt("rewind_seconds",10)));
        }
        catch (Exception e) { status.setText(Texts.text(MediaPreferencesActivity.this,"Configura el servidor e inicia sesión con una cuenta aprobada.")); return; }
        save.setEnabled(true);
        status.setText(Texts.text(MediaPreferencesActivity.this,"Preferencias locales disponibles; sincronizando…"));
        final long requestedVersion=editVersion;
        network.execute(() -> {
            try {
                JSONObject prefs = api.request("GET", "/account/preferences", null, token);
                synchronized(SessionStore.class){if(!token.equals(new SessionStore(this,api.getBaseUrl()).token())||commands.pendingKind("preferences")||editVersion!=requestedVersion)return;local.save(prefs);}
                runOnUiThread(() -> { if (isDestroyed()||editVersion!=requestedVersion) return;
                    ask.setChecked(prefs.optBoolean("ask_every_time", true));
                    choice.setSelection(MediaSelection.index(prefs.optString("selection", "720")));
                    rewind.setText(String.valueOf(prefs.optInt("rewind_seconds", 10)));
                    status.setText(Texts.text(MediaPreferencesActivity.this,"Preferencias sincronizadas de tu cuenta")); save.setEnabled(true);
                });
            } catch (Exception e) { showStatus(Texts.text(MediaPreferencesActivity.this,"Usando preferencias locales. Puedes guardar cambios sin conexión.")); }
        });
        save.setOnClickListener(v -> savePreferences());
    }
    private void savePreferences() {
        final JSONObject prefs;
        try {
            int seconds = Integer.parseInt(rewind.getText().toString());
            if (seconds < 0 || seconds > 120) throw new IllegalArgumentException();
            prefs = new JSONObject().put("ask_every_time", ask.isChecked())
                .put("selection", MediaSelection.VALUES[choice.getSelectedItemPosition()])
                .put("rewind_seconds", seconds);
        } catch (Exception e) { status.setText(Texts.text(MediaPreferencesActivity.this,"El retroceso debe ser un entero entre 0 y 120.")); return; }
        try {
            synchronized(SessionStore.class){if(!token.equals(new SessionStore(this,api.getBaseUrl()).token()))return;
                commands.add("preferences",prefs.toString());local.save(prefs);editVersion++;}
            CommandDispatch.schedule(this,api.getBaseUrl());status.setText(Texts.text(MediaPreferencesActivity.this,"Guardadas en el teléfono. Sincronización pendiente."));
            network.execute(()->{CommandDispatch.flush(this,api.getBaseUrl());});
        }catch(Exception e){status.setText(Texts.text(MediaPreferencesActivity.this,"No se pudieron guardar las preferencias locales."));}
    }
    private void showStatus(String text) { runOnUiThread(() -> { if (!isDestroyed()) status.setText(text); }); }
    @Override protected void onDestroy() { network.shutdownNow(); super.onDestroy(); }
}
