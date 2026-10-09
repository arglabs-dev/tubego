package dev.arglabs.tubego;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Account preferences, ready to launch from the authenticated URL-entry screen. */
public final class MediaPreferencesActivity extends Activity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private CheckBox ask; private Spinner choice; private EditText rewind;
    private TextView status; private Button save;
    private ApiClient api;
    private String token;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding * 2, padding, padding);
        TextView title = new TextView(this); title.setText("Preferencias de reproducción y descarga"); title.setTextSize(22); layout.addView(title);
        ask = new CheckBox(this); ask.setText("Preguntar calidad o audio para cada enlace"); ask.setChecked(true); layout.addView(ask);
        choice = new Spinner(this); choice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, MediaSelection.LABELS));
        choice.setSelection(MediaSelection.index("720")); layout.addView(choice);
        TextView rewindLabel = new TextView(this); rewindLabel.setText("Retroceso al continuar (0 a 120 segundos; requiere reproductor compatible)"); layout.addView(rewindLabel);
        rewind = new EditText(this); rewind.setInputType(InputType.TYPE_CLASS_NUMBER); rewind.setText("10"); layout.addView(rewind);
        status = new TextView(this); layout.addView(status);
        save = new Button(this); save.setText("Guardar preferencias"); save.setEnabled(false); layout.addView(save);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        token = getSharedPreferences("tubego_account", MODE_PRIVATE).getString("session_token", null);
        try { api = new ApiClient(getIntent().getStringExtra("server_url")); }
        catch (Exception e) { status.setText("Configura una URL de servidor válida."); return; }
        status.setText("Cargando preferencias…");
        network.execute(() -> {
            try {
                JSONObject prefs = api.request("GET", "/account/preferences", null, token);
                runOnUiThread(() -> { if (isDestroyed()) return;
                    ask.setChecked(prefs.optBoolean("ask_every_time", true));
                    choice.setSelection(MediaSelection.index(prefs.optString("selection", "720")));
                    rewind.setText(String.valueOf(prefs.optInt("rewind_seconds", 10)));
                    status.setText("Preferencias sincronizadas de tu cuenta"); save.setEnabled(true);
                });
            } catch (Exception e) { showStatus("No se pudieron cargar. Revisa tu sesión y conexión."); }
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
        } catch (Exception e) { status.setText("El retroceso debe ser un entero entre 0 y 120."); return; }
        save.setEnabled(false); status.setText("Guardando…");
        network.execute(() -> {
            try { api.request("PUT", "/account/preferences", prefs, token); showStatus("Preferencias guardadas para todos tus dispositivos"); }
            catch (Exception e) { showStatus("No se guardaron las preferencias. Reintenta cuando tengas conexión."); }
            runOnUiThread(() -> { if (!isDestroyed()) save.setEnabled(true); });
        });
    }
    private void showStatus(String text) { runOnUiThread(() -> { if (!isDestroyed()) status.setText(text); }); }
    @Override protected void onDestroy() { network.shutdownNow(); super.onDestroy(); }
}
