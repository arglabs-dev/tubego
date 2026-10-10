package dev.arglabs.tubego;
import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class LoginActivity extends LocalizedActivity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private TextView status; private Button login,forgot,refresh,change,logout,devices;
    private EditText email,password,newPassword; private CheckBox revoke;
    private String origin;
    private static final class AccountSwitchRequired extends Exception {}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); origin=getIntent().getStringExtra("server_url");
        LinearLayout layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32,64,32,32);
        TextView title=new TextView(this);title.setText(Texts.text(LoginActivity.this,"Cuenta · Tubego"));title.setTextSize(24);layout.addView(title);
        email=new EditText(this);email.setHint(Texts.text(LoginActivity.this,"Correo electrónico"));email.setSingleLine(true);email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);layout.addView(email);
        password=new EditText(this);password.setHint(Texts.text(LoginActivity.this,"Contraseña actual"));password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);layout.addView(password);
        login=new Button(this);login.setText(Texts.text(LoginActivity.this,"Iniciar sesión"));layout.addView(login);
        forgot=new Button(this);forgot.setText(Texts.text(LoginActivity.this,"Recuperar contraseña por correo"));layout.addView(forgot);
        refresh=new Button(this);refresh.setText(Texts.text(LoginActivity.this,"Consultar estado de mi cuenta"));layout.addView(refresh);
        newPassword=new EditText(this);newPassword.setHint(Texts.text(LoginActivity.this,"Nueva contraseña (12–128 caracteres)"));newPassword.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);newPassword.setSingleLine(true);layout.addView(newPassword);
        revoke=new CheckBox(this);revoke.setText(Texts.text(LoginActivity.this,"Cerrar las demás sesiones al cambiar contraseña"));revoke.setChecked(true);layout.addView(revoke);
        change=new Button(this);change.setText(Texts.text(LoginActivity.this,"Cambiar contraseña"));layout.addView(change);
        devices=new Button(this);devices.setText(Texts.text(LoginActivity.this,"Dispositivos vinculados"));layout.addView(devices);
        devices.setOnClickListener(v->startActivity(new android.content.Intent(this,DevicesActivity.class).putExtra("server_url",origin)));
        logout=new Button(this);logout.setText(Texts.text(LoginActivity.this,"Cerrar sesión"));layout.addView(logout);
        logout.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setMessage(Texts.text(LoginActivity.this,"Al cerrar sesión se borrarán las descargas de este teléfono. Los otros dispositivos conservarán sus archivos. ¿Continuar?")).setNegativeButton(Texts.text(LoginActivity.this,"Cancelar"),null).setPositiveButton(Texts.text(LoginActivity.this,"Cerrar sesión"),(d,w)->submit("logout")).show());
        status=new TextView(this);layout.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        login.setOnClickListener(v->submit("login"));forgot.setOnClickListener(v->submit("forgot"));refresh.setOnClickListener(v->submit("status"));change.setOnClickListener(v->submit("change"));
        submit("status");
    }
    private void enabled(boolean value) {login.setEnabled(value);forgot.setEnabled(value);refresh.setEnabled(value);change.setEnabled(value);logout.setEnabled(value);devices.setEnabled(value);}
    private String accountMessage(String state) {
        switch(state) {
            case "approved": return Texts.text(LoginActivity.this,"Cuenta aprobada. Puedes utilizar el servicio.");
            case "pending_verification": return Texts.text(LoginActivity.this,"Verifica tu correo desde el enlace enviado. Puedes reenviarlo en Crear cuenta.");
            case "pending_approval": return Texts.text(LoginActivity.this,"Correo verificado. Espera aprobación del administrador.");
            default: return Texts.text(LoginActivity.this,"Cuenta no disponible.");
        }
    }
    private void submit(String operation) {
        String address=email.getText().toString(), secret=password.getText().toString(), replacement=newPassword.getText().toString();boolean revokeOthers=revoke.isChecked();
        enabled(false);status.setText(Texts.text(LoginActivity.this,"Consultando…"));
        network.execute(()->{
            String message;
            try {
                ApiClient api=new ApiClient(origin);SessionStore store=new SessionStore(this,origin);
                if(operation.equals("logout")) {
                    SessionLifecycle.logout(this,origin);message=Texts.text(LoginActivity.this,"Sesión cerrada. Archivos locales borrados; el servidor recibirá el cierre cuando haya conexión.");
                } else if(operation.equals("login")) {
                    JSONObject active=store.read();
                    if(active!=null && !active.optString("email","").equalsIgnoreCase(address.trim()))
                        throw new AccountSwitchRequired();
                    JSONObject body=new JSONObject().put("email",address).put("password",secret).put("device_name",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL);
                    JSONObject previous=store.deviceIdentity(address);
                    if(previous==null) previous=store.read();
                    if(previous!=null) {
                        body.put("device_id",previous.optString("device_id"));
                        JSONObject pending=store.pendingLogout();
                        if(pending!=null) {
                            var sessions=pending.getJSONArray("sessions");
                            for(int i=0;i<sessions.length();i++) if(sessions.getJSONObject(i).optString("device_id").equals(previous.optString("device_id"))) body.put("replace_device",true);
                        }
                    }
                    JSONObject session=api.request("POST","/auth/login",body,null);
                    store.save(session); LinkOutboxDispatch.schedule(this,origin); if("approved".equals(session.optString("status")))TransferJobs.register(this,origin); message=accountMessage(session.getString("status"));
                } else if(operation.equals("forgot")) {
                    api.request("POST","/auth/password/forgot",new JSONObject().put("email",address),null);message=Texts.text(this,"Si la cuenta permite recuperación, recibirás un correo.");
                } else if(operation.equals("change")) {
                    if(store.token()==null) throw new Exception(Texts.text(LoginActivity.this,"No session"));
                    api.request("POST","/account/password",new JSONObject().put("current_password",secret).put("password",replacement).put("revoke_other_sessions",revokeOthers),store.token());
                    message=Texts.text(LoginActivity.this,"Contraseña actualizada.");
                } else {
                    if(store.token()==null) message=Texts.text(LoginActivity.this,"Inicia sesión para consultar tu estado.");
                    else {
                        JSONObject account=api.request("GET","/account/status",null,store.token());
                        JSONObject session=store.read();session.put("status",account.getString("status")).put("role",account.getString("role"));store.save(session);
                        message=accountMessage(account.getString("status"));
                    }
                }
            } catch(AccountSwitchRequired e) {
                message=Texts.text(LoginActivity.this,"Cierra la sesión actual antes de cambiar de cuenta. Se te pedirá confirmar el borrado de sus descargas locales.");
            } catch(ApiClient.ApiException e) {
                if(e.code.equals("session_revoked") || e.code.equals("account_unavailable")) {
                    try {new SessionStore(this,origin).clear();} catch(Exception ignored) { }
                    message=Texts.text(LoginActivity.this,"Tu sesión fue revocada o tu cuenta no está disponible.");
                } else if(e.code.equals("session_expired")) message=Texts.text(LoginActivity.this,"Tu sesión venció. Inicia sesión nuevamente.");
                else if(e.status==429) message=Texts.text(LoginActivity.this,"Demasiados intentos. Espera antes de volver a intentar.");
                else if(e.status==401) message=Texts.text(LoginActivity.this,"Correo o contraseña incorrectos, o sesión no válida.");
                else message=Texts.text(LoginActivity.this,"No se pudo completar la operación. Revisa los datos.");
            } catch(Exception e) {message=Texts.text(LoginActivity.this,"No se pudo completar. Revisa los datos y la conexión.");}
            final String result=message;
            runOnUiThread(()->{if(!isDestroyed()){status.setText(result);enabled(true);password.setText("");newPassword.setText("");}});
        });
    }
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
