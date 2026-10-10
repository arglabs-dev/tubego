package dev.arglabs.tubego;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Administrative scheduling preferences. Backend enforces all authorization. */
public final class AdminPriorityActivity extends LocalizedActivity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private LinearLayout layout;
    private TextView status;
    private String cursor = "";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32,64,32,32);
        status = new TextView(this);
        layout.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        if(!AdminAccess.allowed(this,getIntent().getStringExtra("server_url"))){status.setText("Se requiere una cuenta aprobada de administrador.");return;}
        load();
    }
    private ApiClient api() { return new ApiClient(getIntent().getStringExtra("server_url")); }
    private String token() throws Exception { return AdminAccess.remoteToken(this,api().getBaseUrl()); }
    private void load() {
        status.setText(Texts.text(AdminPriorityActivity.this,"Cargando prioridades…"));
        network.execute(() -> {
            try {
                JSONObject response = api().request("GET", "/admin/users/priorities?after="+java.net.URLEncoder.encode(cursor,"UTF-8"), null, token());
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    layout.removeAllViews(); layout.addView(status);
                    status.setText(Texts.text(AdminPriorityActivity.this,"Prioridad persistente: 3 turnos frente a 1. No interrumpe descargas."));
                    var items = response.optJSONArray("items");
                    for (int i=0;items!=null && i<items.length();i++) {
                        JSONObject item=items.optJSONObject(i);
                        TextView email=new TextView(this); email.setText(item.optString("email")); layout.addView(email);
                        Spinner level=new Spinner(this);
                        level.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{Texts.text(AdminPriorityActivity.this,"Normal"),Texts.text(AdminPriorityActivity.this,"Prioritario")}));
                        level.setSelection("prioritario".equals(item.optString("level")) ? 1 : 0);
                        layout.addView(level);
                        Button save=new Button(this); save.setText(Texts.text(AdminPriorityActivity.this,"Guardar prioridad")); layout.addView(save);
                        save.setOnClickListener(v -> {
                            save.setEnabled(false);
                            String value=level.getSelectedItemPosition()==1 ? "prioritario" : "normal";
                            network.execute(() -> {
                                String result;
                                try { api().request("PUT","/admin/users/"+item.optString("id")+"/priority",new JSONObject().put("level",value),token()); result=Texts.text(AdminPriorityActivity.this,"Prioridad guardada"); }
                                catch(Exception e) { result=Texts.text(AdminPriorityActivity.this,"No se pudo guardar. Se requiere una sesión administrativa activa."); }
                                final String message=result;
                                runOnUiThread(() -> { if (!isDestroyed()) { status.setText(message); save.setEnabled(true); } });
                            });
                        });
                    }
                    if (!response.isNull("next_cursor")) {
                        Button next=new Button(this); next.setText(Texts.text(AdminPriorityActivity.this,"Siguiente página")); layout.addView(next);
                        next.setOnClickListener(v -> { cursor=response.optString("next_cursor"); load(); });
                    }
                    if (!cursor.isEmpty()) {
                        Button first=new Button(this); first.setText(Texts.text(AdminPriorityActivity.this,"Volver al inicio")); layout.addView(first);
                        first.setOnClickListener(v -> { cursor=""; load(); });
                    }
                });
            } catch(Exception e) { runOnUiThread(() -> { if(!isDestroyed()) status.setText(Texts.text(AdminPriorityActivity.this,"No se pudieron consultar usuarios. Se requiere una sesión administrativa activa.")); }); }
        });
    }
    @Override protected void onDestroy() { network.shutdownNow(); super.onDestroy(); }
}
