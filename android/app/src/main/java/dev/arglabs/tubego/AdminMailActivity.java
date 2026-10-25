package dev.arglabs.tubego;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Administrator-only SMTP configuration and delivery diagnostics. */
public final class AdminMailActivity extends LocalizedActivity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private LinearLayout layout;
    private TextView status;
    private String origin;
    private String recipient = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        origin = getIntent().getStringExtra("server_url");
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 64, 32, 32);
        status = new TextView(this);
        layout.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        load();
    }

    private void load() {
        status.setText("Consultando configuración SMTP…");
        network.execute(() -> {
            try {
                String token = AdminAccess.remoteToken(this, origin);
                JSONObject config = new ApiClient(origin).request("GET", "/admin/mail/status", null, token);
                runOnUiThread(() -> showConfiguration(config));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) status.setText(Texts.error(this, e));
                });
            }
        });
    }

    private void showConfiguration(JSONObject config) {
        if (isDestroyed()) return;
        recipient = config.optString("test_recipient", "");
        layout.removeAllViews();
        layout.addView(status);
        String details = "SMTP del servidor\n" +
                "Host: " + value(config, "host") + "\n" +
                "Puerto: " + config.optInt("port") + "\n" +
                "Cifrado: " + value(config, "transport") + "\n" +
                "Remitente: " + value(config, "sender") + "\n" +
                "Usuario: " + value(config, "username") + "\n" +
                "Contraseña configurada: " + (config.optBoolean("password_configured") ? "sí" : "no") + "\n" +
                "URL pública HTTPS: " + (config.optBoolean("public_url_https") ? "sí" : "no") + "\n" +
                "Configuración lista: " + (config.optBoolean("ready") ? "sí" : "no") + "\n" +
                "Destinatario de prueba: " + recipient;
        TextView summary = new TextView(this);
        summary.setText(details);
        layout.addView(summary);

        Button check = new Button(this);
        check.setText("Comprobar conexión y autenticación SMTP");
        layout.addView(check);
        check.setOnClickListener(v -> checkConnection());

        Button send = new Button(this);
        send.setText("Enviar correo de prueba");
        layout.addView(send);
        send.setOnClickListener(v -> confirmSend());

        Button refresh = new Button(this);
        refresh.setText("Actualizar configuración");
        layout.addView(refresh);
        refresh.setOnClickListener(v -> load());
        status.setText("La contraseña nunca se muestra en esta pantalla.");
    }

    private String value(JSONObject config, String key) {
        String value = config.optString(key, "");
        return value.isEmpty() || "null".equals(value) ? "(sin configurar)" : value;
    }

    private void checkConnection() {
        status.setText("Comprobando conexión segura y autenticación SMTP…");
        network.execute(() -> {
            try {
                JSONObject result = new ApiClient(origin).request("POST", "/admin/mail/check", new JSONObject(), new SessionStore(this, origin).token());
                showResult(result.optString("message", "Conexión SMTP correcta."));
            } catch (Exception e) {
                showResult(errorMessage(e));
            }
        });
    }

    private void confirmSend() {
        new AlertDialog.Builder(this)
                .setMessage("Se enviará un mensaje de prueba desde la cuenta configurada a " + recipient + ". ¿Continuar?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Enviar", (dialog, which) -> sendTest())
                .show();
    }

    private void sendTest() {
        status.setText("Enviando correo de prueba…");
        network.execute(() -> {
            try {
                JSONObject result = new ApiClient(origin).request("POST", "/admin/mail/test", new JSONObject(), new SessionStore(this, origin).token());
                showResult(result.optString("message", "El servidor SMTP aceptó el mensaje."));
            } catch (Exception e) {
                showResult(errorMessage(e));
            }
        });
    }

    private String errorMessage(Exception error) {
        if (error instanceof ApiClient.ApiException) {
            switch (((ApiClient.ApiException) error).code) {
                case "smtp_config_incomplete": return "Faltan datos SMTP. Revisa la configuración del servidor.";
                case "smtp_transport_invalid": return "Elige solo un modo de cifrado SMTP.";
                case "smtp_connection_failed": return "La API no pudo conectarse al host y puerto SMTP. Revisa la salida de red del contenedor.";
                case "smtp_tls_failed": return "No se pudo establecer el cifrado TLS con el servidor SMTP.";
                case "smtp_auth_failed": return "El servidor SMTP rechazó el usuario o la contraseña.";
                case "smtp_check_failed": return "La conexión SMTP no superó la comprobación.";
                case "smtp_recipient_rejected": return "El servidor SMTP rechazó la dirección de prueba.";
                case "smtp_send_failed": return "El servidor SMTP no aceptó el correo de prueba.";
                default: return Texts.error(this, error);
            }
        }
        return Texts.error(this, error);
    }

    private void showResult(String message) {
        runOnUiThread(() -> { if (!isDestroyed()) status.setText(message); });
    }

    @Override protected void onDestroy() {
        network.shutdownNow();
        super.onDestroy();
    }
}
