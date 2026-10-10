package dev.arglabs.tubego;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class AdminRegistrationsActivity extends LocalizedActivity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private LinearLayout layout; private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32,64,32,32);
        status = new TextView(this); layout.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll); if(!AdminAccess.allowed(this,getIntent().getStringExtra("server_url"))){status.setText("Se requiere una cuenta aprobada de administrador.");return;} load();
    }
    private String token() throws Exception { return AdminAccess.remoteToken(this,getIntent().getStringExtra("server_url")); }
    private ApiClient api() { return new ApiClient(getIntent().getStringExtra("server_url")); }
    private void load() {
        layout.removeAllViews(); layout.addView(status); status.setText(Texts.text(AdminRegistrationsActivity.this,"Cargando solicitudes verificadas…"));
        network.execute(() -> {
            try {
                var items = api().request("GET","/admin/registrations",null,token()).getJSONArray("items");
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    status.setText(items.length()==0 ? Texts.text(AdminRegistrationsActivity.this,"Sin solicitudes pendientes") : Texts.text(AdminRegistrationsActivity.this,"Solicitudes verificadas pendientes"));
                    for (int i=0;i<items.length();i++) {
                        JSONObject item = items.optJSONObject(i); String id=item.optString("id");
                        TextView text=new TextView(this); text.setText(item.optString("email")); layout.addView(text);
                        for (boolean approve : new boolean[]{true,false}) {
                            Button button=new Button(this); button.setText(approve ? Texts.text(AdminRegistrationsActivity.this,"Aprobar") : Texts.text(AdminRegistrationsActivity.this,"Rechazar")); layout.addView(button);
                            button.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage((approve?Texts.text(AdminRegistrationsActivity.this,"Aprobar "):Texts.text(AdminRegistrationsActivity.this,"Rechazar "))+item.optString("email")+"?").setNegativeButton(Texts.text(AdminRegistrationsActivity.this,"Cancelar"),null).setPositiveButton(Texts.text(AdminRegistrationsActivity.this,"Confirmar"),(d,w)->decide(id,approve)).show());
                        }
                    }
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) status.setText(Texts.text(AdminRegistrationsActivity.this,"Acceso no disponible. Se requiere una sesión de administrador aprobada.")); }); }
        });
    }
    private void decide(String id, boolean approve) {
        network.execute(() -> { try { api().request("POST","/admin/registrations/"+id+"/decision",new JSONObject().put("approve",approve),token()); runOnUiThread(() -> { if (!isDestroyed()) load(); }); }
            catch(Exception e) { runOnUiThread(() -> { if (!isDestroyed()) status.setText(Texts.text(AdminRegistrationsActivity.this,"No se pudo cambiar la solicitud. Actualiza e inténtalo nuevamente.")); }); } });
    }
    @Override protected void onDestroy() { network.shutdownNow(); super.onDestroy(); }
}
