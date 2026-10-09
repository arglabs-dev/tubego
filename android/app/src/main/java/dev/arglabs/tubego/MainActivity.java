package dev.arglabs.tubego;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.InputType;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button connect;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding * 2, padding, padding);
        TextView heading = new TextView(this);
        heading.setText("Tubego");
        heading.setTextSize(28);
        layout.addView(heading);
        TextView description = new TextView(this);
        description.setText("Configura tu servidor para comenzar.");
        layout.addView(description);
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("https://tubego.example.com");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(getPreferences(MODE_PRIVATE).getString("server_url", ""));
        layout.addView(url);
        connect = new Button(this);
        connect.setText("Guardar y comprobar conexión");
        layout.addView(connect);
        status = new TextView(this);
        status.setText("Sin conexión comprobada");
        layout.addView(status);
        Button registration = new Button(this); registration.setText("Crear cuenta"); layout.addView(registration);
        registration.setOnClickListener(v -> {
            try { String origin = new ApiClient(url.getText().toString()).getBaseUrl();
                startActivity(new android.content.Intent(this, RegistrationActivity.class).putExtra("server_url",origin));
            } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
        });
        Button approvals = new Button(this); approvals.setText("Administrar solicitudes"); layout.addView(approvals);
        approvals.setOnClickListener(v -> {
            try { String origin = new ApiClient(url.getText().toString()).getBaseUrl();
                startActivity(new android.content.Intent(this, AdminRegistrationsActivity.class).putExtra("server_url",origin));
            } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
        });
        Button priorities = new Button(this); priorities.setText("Administrar prioridades"); layout.addView(priorities);
        priorities.setOnClickListener(v -> {
            try { String origin = new ApiClient(url.getText().toString()).getBaseUrl();
                startActivity(new android.content.Intent(this, AdminPriorityActivity.class).putExtra("server_url",origin));
            } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
        });
        setContentView(layout);
        connect.setOnClickListener(v -> {
            final ApiClient client;
            try { client = new ApiClient(url.getText().toString()); }
            catch (IllegalArgumentException e) { status.setText(e.getMessage()); return; }
            getPreferences(MODE_PRIVATE).edit().putString("server_url", client.getBaseUrl()).apply();
            status.setText("Comprobando conexión…");
            connect.setEnabled(false);
            network.execute(() -> {
                String message;
                try {
                    var health = client.health();
                    if (!"ok".equals(health.optString("status"))) throw new Exception("Servidor no disponible");
                    message = "Conectado · versión " + health.optString("version");
                } catch (Exception e) { message = "No se pudo conectar. Revisa la URL y tu conexión."; }
                final String result = message;
                runOnUiThread(() -> {
                    if (!isDestroyed()) { status.setText(result); connect.setEnabled(true); }
                });
            });
        });
    }

    @Override protected void onDestroy() {
        network.shutdownNow();
        super.onDestroy();
    }
}
