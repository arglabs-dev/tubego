package dev.arglabs.tubego;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Server connection settings; account and administration have their own destinations. */
public final class MainActivity extends LocalizedActivity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = Ds.column(this);
        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.heading(this, Texts.text(this, "Dirección del servidor")));
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("https://");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(getSharedPreferences("server_connection", MODE_PRIVATE).getString("server_url", ""));
        card.addView(url);
        TextView status = new TextView(this);
        status.setText(Texts.text(this, "Sin conexión comprobada"));
        card.addView(status);
        Button connect = new Button(this);
        connect.setText(Texts.text(this, "Guardar y comprobar conexión"));
        UiKit.button(connect, true);
        card.addView(connect);
        body.addView(card);
        Ds.add(body, UiKit.text(this, Texts.text(this, "Esta dirección se usa solo en esta app. Si cambias de servidor, inicia sesión con una cuenta de ese servidor."), 14, Ds.MUTED), 16);
        setContentView(body);
        connect.setOnClickListener(v -> {
            final ApiClient client;
            try { client = new ApiClient(url.getText().toString()); }
            catch (Exception e) { status.setText(Texts.error(this, e)); return; }
            getSharedPreferences("server_connection", MODE_PRIVATE).edit().putString("server_url", client.getBaseUrl()).apply();
            TransferJobs.register(this, client.getBaseUrl());
            connect.setEnabled(false);
            status.setText(Texts.text(this, "Comprobando conexión…"));
            network.execute(() -> {
                String message;
                try {
                    var health = client.health();
                    if (!"ok".equals(health.optString("status"))) throw new Exception();
                    message = Texts.text(this, "Conectado · versión ") + health.optString("version");
                } catch (Exception e) { message = Texts.text(this, "No se pudo conectar. Revisa la URL y tu conexión."); }
                final String result = message;
                runOnUiThread(() -> { if (!isDestroyed()) { connect.setEnabled(true); status.setText(result); } });
            });
        });
    }
    @Override protected void onDestroy() { network.shutdownNow(); super.onDestroy(); }
}
