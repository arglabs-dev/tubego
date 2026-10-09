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
    private Button adminCenter;
    private EditText serverUrl;

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
        EditText url = new EditText(this);serverUrl=url;
        url.setSingleLine(true);
        url.setHint("https://tubego.example.com");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(getSharedPreferences("server_connection",MODE_PRIVATE).getString("server_url", getPreferences(MODE_PRIVATE).getString("server_url", "")));
        layout.addView(url);
        connect = new Button(this);
        connect.setText("Guardar y comprobar conexión");
        layout.addView(connect);
        status = new TextView(this);
        status.setText("Sin conexión comprobada");
        layout.addView(status);
        Button account = new Button(this); account.setText("Iniciar sesión / Mi cuenta"); layout.addView(account);
        account.setOnClickListener(v -> {
            try { String origin = new ApiClient(url.getText().toString()).getBaseUrl();
                startActivity(new android.content.Intent(this, LoginActivity.class).putExtra("server_url",origin));
            } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
        });
        Button registration = new Button(this); registration.setText("Crear cuenta"); layout.addView(registration);
        registration.setOnClickListener(v -> {
            try { String origin = new ApiClient(url.getText().toString()).getBaseUrl();
                startActivity(new android.content.Intent(this, RegistrationActivity.class).putExtra("server_url",origin));
            } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
        });
        Button addLink=new Button(this);addLink.setText("Agregar enlace / Pendientes de envío");layout.addView(addLink);
        addLink.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();
            getSharedPreferences("server_connection",MODE_PRIVATE).edit().putString("server_url",origin).apply();
            startActivity(new android.content.Intent(this,LinkEntryActivity.class).putExtra("server_url",origin));
        }catch(Exception e){status.setText("Configura una URL HTTPS de servidor válida.");}});
        Button commands=new Button(this);commands.setText("Acciones pendientes y errores");layout.addView(commands);
        commands.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();startActivity(new android.content.Intent(this,CommandsActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText("Configura una URL HTTPS válida.");}});
        Button mediaPrefs=new Button(this);mediaPrefs.setText("Preferencias de reproducción y descarga");layout.addView(mediaPrefs);
        mediaPrefs.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();startActivity(new android.content.Intent(this,MediaPreferencesActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText("Configura una URL HTTPS de servidor válida.");}});
        Button dataPolicy = new Button(this); dataPolicy.setText("Permisos de datos por descarga"); layout.addView(dataPolicy);
        dataPolicy.setOnClickListener(v -> {
            try {String origin=new ApiClient(url.getText().toString()).getBaseUrl();
                startActivity(new android.content.Intent(this,NetworkPolicyActivity.class).putExtra("server_url",origin));
            } catch(IllegalArgumentException e) {status.setText(e.getMessage());}
        });
        Button recovery=new Button(this);recovery.setText("Historial / Volver a solicitar");layout.addView(recovery);
        recovery.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();startActivity(new android.content.Intent(this,ResubmitActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText("Configura una URL HTTPS válida.");}});
        Button serverCleanup=new Button(this);serverCleanup.setText("Limpiar archivos del servidor");layout.addView(serverCleanup);
        serverCleanup.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();startActivity(new android.content.Intent(this,ServerCleanupActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText("Configura una URL HTTPS válida.");}});
        adminCenter=new Button(this);adminCenter.setText("Administración");adminCenter.setVisibility(android.view.View.GONE);layout.addView(adminCenter);
        adminCenter.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();if(!AdminAccess.allowed(this,origin)){adminCenter.setVisibility(android.view.View.GONE);return;}startActivity(new android.content.Intent(this,AdminCenterActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText("Inicia sesión como administrador.");}});
        url.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){updateAdminVisibility();}public void afterTextChanged(android.text.Editable e){}});
        Button deletion=new Button(this);deletion.setText("Biblioteca / Borrar recursos");layout.addView(deletion);
        deletion.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();startActivity(new android.content.Intent(this,ResourceDeletionActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText("Configura una URL HTTPS válida.");}});
        Button library = new Button(this); library.setText("Biblioteca / Historial"); layout.addView(library);
        library.setOnClickListener(v -> {try {String origin=new ApiClient(url.getText().toString()).getBaseUrl();startActivity(new android.content.Intent(this,LocalLibraryActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText(e.getMessage());}});
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(layout);setContentView(scroll);
        connect.setOnClickListener(v -> {
            final ApiClient client;
            try { client = new ApiClient(url.getText().toString()); }
            catch (IllegalArgumentException e) { status.setText(e.getMessage()); return; }
            getSharedPreferences("server_connection",MODE_PRIVATE).edit().putString("server_url", client.getBaseUrl()).apply();
            TransferJobs.register(this,client.getBaseUrl());
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

    @Override protected void onResume() {
        super.onResume();
        updateAdminVisibility();
        String origin=getSharedPreferences("server_connection",MODE_PRIVATE).getString("server_url", getPreferences(MODE_PRIVATE).getString("server_url", ""));
        if(origin.isEmpty()) return;
        try{TransferJobs.register(this,new ApiClient(origin).getBaseUrl());}catch(Exception ignored){}
        network.execute(() -> {
            try {
                String token=new SessionStore(this,origin).token();
                if(token!=null){org.json.JSONObject account=new ApiClient(origin).request("GET","/account/status",null,token);
                    synchronized(SessionStore.class){SessionStore store=new SessionStore(this,origin);org.json.JSONObject current=store.read();if(current!=null&&token.equals(current.optString("token"))){current.put("role",account.getString("role")).put("status",account.getString("status"));store.save(current);}}
                    runOnUiThread(()->{if(!isDestroyed())updateAdminVisibility();});
                }
            } catch(ApiClient.ApiException e) {
                if(e.status==401||e.status==403)runOnUiThread(()->{if(!isDestroyed())adminCenter.setVisibility(android.view.View.GONE);});
                if(e.code.equals("session_revoked") || e.code.equals("account_unavailable"))
                    runOnUiThread(() -> {if(!isDestroyed()){status.setText("Sesión revocada. Se han retirado las descargas locales de esta cuenta.");adminCenter.setVisibility(android.view.View.GONE);}});
            } catch(Exception ignored) { /* Offline access remains usable until revocation is known. */ }
        });
    }

    private void updateAdminVisibility(){
        if(adminCenter==null||serverUrl==null||isDestroyed())return;adminCenter.setVisibility(android.view.View.GONE);final String selected=serverUrl.getText().toString();
        network.execute(()->{boolean allowed=AdminAccess.allowed(this,selected);runOnUiThread(()->{if(!isDestroyed()&&selected.equals(serverUrl.getText().toString()))adminCenter.setVisibility(allowed?android.view.View.VISIBLE:android.view.View.GONE);});});
    }

    @Override protected void onDestroy() {
        network.shutdownNow();
        super.onDestroy();
    }
}
