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

public final class MainActivity extends LocalizedActivity {
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
        description.setText(Texts.text(MainActivity.this,"Configura tu servidor para comenzar."));
        layout.addView(description);
        EditText url = new EditText(this);serverUrl=url;
        url.setSingleLine(true);
        url.setHint("https://tubego.example.com");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(getSharedPreferences("server_connection",MODE_PRIVATE).getString("server_url", getPreferences(MODE_PRIVATE).getString("server_url", "")));
        layout.addView(url);
        connect = new Button(this);
        connect.setText(Texts.text(MainActivity.this,"Guardar y comprobar conexión"));
        layout.addView(connect);
        status = new TextView(this);
        status.setText(Texts.text(MainActivity.this,"Sin conexión comprobada"));
        layout.addView(status);
        Button account = new Button(this); account.setText(Texts.text(MainActivity.this,"Iniciar sesión / Mi cuenta")); layout.addView(account);
        account.setOnClickListener(v -> {
            try { String origin = new ApiClient(url.getText().toString()).getBaseUrl();
                startActivity(new android.content.Intent(this, LoginActivity.class).putExtra("server_url",origin));
            } catch (IllegalArgumentException e) { status.setText(Texts.error(this,e)); }
        });
        Button registration = new Button(this); registration.setText(Texts.text(MainActivity.this,"Crear cuenta")); layout.addView(registration);
        registration.setOnClickListener(v -> {
            try { String origin = new ApiClient(url.getText().toString()).getBaseUrl();
                startActivity(new android.content.Intent(this, RegistrationActivity.class).putExtra("server_url",origin));
            } catch (IllegalArgumentException e) { status.setText(Texts.error(this,e)); }
        });
        Button addLink=new Button(this);addLink.setText(Texts.text(MainActivity.this,"Agregar enlace / Pendientes de envío"));layout.addView(addLink);
        addLink.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();
            getSharedPreferences("server_connection",MODE_PRIVATE).edit().putString("server_url",origin).apply();
            startActivity(new android.content.Intent(this,LinkEntryActivity.class).putExtra("server_url",origin));
        }catch(Exception e){status.setText(Texts.text(MainActivity.this,"Configura una URL HTTPS de servidor válida."));}});
        Button commands=new Button(this);commands.setText(Texts.text(MainActivity.this,"Acciones pendientes y errores"));layout.addView(commands);
        commands.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();startActivity(new android.content.Intent(this,CommandsActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText(Texts.text(MainActivity.this,"Configura una URL HTTPS válida."));}});
        Button mediaPrefs=new Button(this);mediaPrefs.setText(Texts.text(MainActivity.this,"Preferencias de reproducción y descarga"));layout.addView(mediaPrefs);
        mediaPrefs.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();startActivity(new android.content.Intent(this,MediaPreferencesActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText(Texts.text(MainActivity.this,"Configura una URL HTTPS de servidor válida."));}});
        Button dataPolicy = new Button(this); dataPolicy.setText(Texts.text(MainActivity.this,"Permisos de datos por descarga")); layout.addView(dataPolicy);
        dataPolicy.setOnClickListener(v -> {
            try {String origin=new ApiClient(url.getText().toString()).getBaseUrl();
                startActivity(new android.content.Intent(this,NetworkPolicyActivity.class).putExtra("server_url",origin));
            } catch(IllegalArgumentException e) {status.setText(Texts.error(this,e));}
        });
        Button recovery=new Button(this);recovery.setText(Texts.text(MainActivity.this,"Historial / Volver a solicitar"));layout.addView(recovery);
        recovery.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();startActivity(new android.content.Intent(this,ResubmitActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText(Texts.text(MainActivity.this,"Configura una URL HTTPS válida."));}});
        Button serverCleanup=new Button(this);serverCleanup.setText(Texts.text(MainActivity.this,"Limpiar archivos del servidor"));layout.addView(serverCleanup);
        serverCleanup.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();startActivity(new android.content.Intent(this,ServerCleanupActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText(Texts.text(MainActivity.this,"Configura una URL HTTPS válida."));}});
        adminCenter=new Button(this);adminCenter.setText(Texts.text(MainActivity.this,"Administración"));adminCenter.setVisibility(android.view.View.GONE);layout.addView(adminCenter);
        adminCenter.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();if(!AdminAccess.allowed(this,origin)){adminCenter.setVisibility(android.view.View.GONE);return;}startActivity(new android.content.Intent(this,AdminCenterActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText(Texts.text(MainActivity.this,"Inicia sesión como administrador."));}});
        url.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){updateAdminVisibility();}public void afterTextChanged(android.text.Editable e){}});
        Button deletion=new Button(this);deletion.setText(Texts.text(MainActivity.this,"Biblioteca / Borrar recursos"));layout.addView(deletion);
        deletion.setOnClickListener(v->{try{String origin=new ApiClient(url.getText().toString()).getBaseUrl();startActivity(new android.content.Intent(this,ResourceDeletionActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText(Texts.text(MainActivity.this,"Configura una URL HTTPS válida."));}});
        Button library = new Button(this); library.setText(Texts.text(MainActivity.this,"Biblioteca / Historial")); layout.addView(library);
        library.setOnClickListener(v -> {try {String origin=new ApiClient(url.getText().toString()).getBaseUrl();startActivity(new android.content.Intent(this,LocalLibraryActivity.class).putExtra("server_url",origin));}catch(Exception e){status.setText(Texts.error(this,e));}});
        Button help=new Button(this);help.setText(Texts.text(this,"Ayuda de Tubego"));layout.addView(help);
        help.setOnClickListener(v->startActivity(new android.content.Intent(this,HelpActivity.class).putExtra("server_url",getSharedPreferences("server_connection",MODE_PRIVATE).getString("server_url",""))));
        Button language=new Button(this);language.setText(Texts.text(this,"Idioma"));layout.addView(language);
        language.setOnClickListener(v->{try{startActivity(new android.content.Intent(this,LanguageActivity.class).putExtra("server_url",new ApiClient(url.getText().toString()).getBaseUrl()));}catch(Exception e){status.setText(Texts.error(this,e));}});
        Button diagnostics=new Button(this);diagnostics.setText(Texts.text(this,"Diagnóstico de esta cuenta"));layout.addView(diagnostics);
        diagnostics.setOnClickListener(v->{try{startActivity(new android.content.Intent(this,DiagnosticsActivity.class).putExtra("server_url",new ApiClient(url.getText().toString()).getBaseUrl()));}catch(Exception e){status.setText(Texts.error(this,e));}});
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(layout);setContentView(scroll);
        connect.setOnClickListener(v -> {
            final ApiClient client;
            try { client = new ApiClient(url.getText().toString()); }
            catch (IllegalArgumentException e) { status.setText(Texts.error(this,e)); return; }
            getSharedPreferences("server_connection",MODE_PRIVATE).edit().putString("server_url", client.getBaseUrl()).apply();
            TransferJobs.register(this,client.getBaseUrl());
            status.setText(Texts.text(MainActivity.this,"Comprobando conexión…"));
            connect.setEnabled(false);
            network.execute(() -> {
                String message;
                try {
                    var health = client.health();
                    if (!"ok".equals(health.optString("status"))) throw new Exception(Texts.text(MainActivity.this,"Servidor no disponible"));
                    message = Texts.text(MainActivity.this,"Conectado · versión ") + health.optString("version");
                } catch (Exception e) { message = Texts.text(MainActivity.this,"No se pudo conectar. Revisa la URL y tu conexión."); }
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
                    String languageBefore=LanguagePreferences.language(this,origin);
                    try{LanguagePreferences.refresh(this,origin);}catch(Exception ignored){/* Keep saved language offline. */}
                    runOnUiThread(()->{if(!isDestroyed()){updateAdminVisibility();if(!languageBefore.equals(LanguagePreferences.language(this,origin)))recreate();}});
                }
            } catch(ApiClient.ApiException e) {
                if(e.status==401||e.status==403)runOnUiThread(()->{if(!isDestroyed())adminCenter.setVisibility(android.view.View.GONE);});
                if(e.code.equals("session_revoked") || e.code.equals("account_unavailable"))
                    runOnUiThread(() -> {if(!isDestroyed()){status.setText(Texts.text(MainActivity.this,"Sesión revocada. Se han retirado las descargas locales de esta cuenta."));adminCenter.setVisibility(android.view.View.GONE);}});
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
