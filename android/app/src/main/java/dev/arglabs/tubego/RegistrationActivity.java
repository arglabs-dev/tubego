package dev.arglabs.tubego;
import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class RegistrationActivity extends Activity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32,64,32,32);
        TextView heading = new TextView(this); heading.setText("Crear cuenta · Tubego"); heading.setTextSize(24); layout.addView(heading);
        EditText email = new EditText(this); email.setHint("Correo electrónico"); email.setSingleLine(true); email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); layout.addView(email);
        EditText password = new EditText(this); password.setHint("Contraseña (12–128 caracteres)"); password.setSingleLine(true); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); layout.addView(password);
        Button register = new Button(this); register.setText("Registrarme"); layout.addView(register);
        Button resend = new Button(this); resend.setText("Reenviar verificación"); layout.addView(resend);
        TextView status = new TextView(this); status.setText("Verifica tu correo desde el enlace enviado. Después un administrador debe aprobar tu cuenta."); layout.addView(status);
        setContentView(layout);
        register.setOnClickListener(v -> submit("/auth/register",email.getText().toString(),password.getText().toString(),status,register,resend,password));
        resend.setOnClickListener(v -> submit("/auth/verification/resend",email.getText().toString(),null,status,register,resend,password));
    }
    private void submit(String path,String email,String password,TextView status,Button register,Button resend,EditText secret) {
        if (password != null && (password.length()<12 || password.length()>128)) { status.setText("Usa entre 12 y 128 caracteres."); return; }
        register.setEnabled(false); resend.setEnabled(false); status.setText("Enviando…");
        network.execute(() -> {
            String message;
            try {
                JSONObject body = new JSONObject().put("email",email);
                if (password != null) body.put("password",password);
                message = new ApiClient(getIntent().getStringExtra("server_url")).request("POST",path,body,null).getString("message");
            } catch (Exception e) { message = "No se pudo enviar. Revisa el correo, la conexión y la configuración del servidor."; }
            final String result = message;
            runOnUiThread(() -> { if (!isDestroyed()) { status.setText(result); register.setEnabled(true); resend.setEnabled(true); secret.setText(""); } });
        });
    }
    @Override protected void onDestroy() { network.shutdownNow(); super.onDestroy(); }
}
