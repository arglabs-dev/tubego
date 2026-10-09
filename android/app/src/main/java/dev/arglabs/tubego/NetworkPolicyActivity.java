package dev.arglabs.tubego;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit file-specific mobile-data consent; transfer worker consumes the grant. */
public final class NetworkPolicyActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private String origin;
    private LinearLayout layout;
    private TextView status;
    private NetworkMonitor monitor;
    private DownloadPermissions permissions;
    private JSONObject session;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {origin=new ApiClient(getIntent().getStringExtra("server_url")).getBaseUrl();}
        catch(Exception e) {finish();return;}
        permissions=AndroidDownloadPermissions.create(this);
        layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);
        status=new TextView(this);layout.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        monitor=new NetworkMonitor(this,network->{
            if(!isDestroyed()) status.setText(DownloadPolicy.mediaDecision(network,false)==DownloadPolicy.Decision.ALLOW_WIFI
                ?"Wi-Fi disponible. Las descargas pueden continuar."
                :DownloadPolicy.allowsControl(network)?"Las descargas esperan Wi-Fi salvo autorización individual.":"Sin conexión validada. Las descargas quedan en espera.");
        });
        load("");
    }
    private void load(String cursor) {
        executor.execute(()->{
            try {
                session=new SessionStore(this,origin).read();
                if(session==null || !"approved".equals(session.optString("status"))) throw new Exception("Sesión aprobada requerida");
                JSONObject result=new ApiClient(origin).request("GET","/device/sync?delivery_cursor="+java.net.URLEncoder.encode(cursor,"UTF-8"),null,session.getString("token"));
                JSONObject account=session;
                runOnUiThread(()->{
                    if(isDestroyed()) return;
                    if(cursor.isEmpty()) {layout.removeAllViews();layout.addView(status);addFailedTransfers(account);}
                    var rows=result.optJSONArray("deliveries");
                    for(int i=0;rows!=null && i<rows.length();i++) {
                        JSONObject row=rows.optJSONObject(i);
                        try {
                            TransferKey key=new TransferKey(origin,account.getString("user_id"),account.getString("device_id"),row.getString("id"),row.getString("sha256"));
                            if("complete".equals(row.optString("delivery_status"))) {permissions.revoke(key);continue;}
                            if(!row.optBoolean("server_available") || !"pending".equals(row.optString("delivery_status"))) continue;
                            TextView description=new TextView(this);
                            String title=row.optString("title","Archivo");
                            String size=row.isNull("size_bytes")?"tamaño desconocido":row.optLong("size_bytes")+" bytes";
                            description.setText(title+" · "+size);layout.addView(description);
                            Button allow=new Button(this);allow.setText(permissions.authorized(key)?"Datos móviles autorizados":"Permitir datos móviles");layout.addView(allow);
                            allow.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Usar datos móviles")
                                .setMessage("¿Autorizar "+title+" ("+size+") en este teléfono? El permiso dura hasta completar o cancelar esta descarga. No incluye otros archivos ni dispositivos.")
                                .setNegativeButton("Cancelar",null).setPositiveButton("Autorizar",(d,w)->{
                                    try {
                                        synchronized(SessionStore.class) {
                                            JSONObject current=new SessionStore(this,origin).read();
                                            if(current==null || !key.userId.equals(current.optString("user_id")) || !key.deviceId.equals(current.optString("device_id"))) throw new Exception("Cuenta cambió");
                                            java.io.File root=LocalLibraryStorage.root(this,origin,key.userId,key.deviceId);
                                            if(new java.io.File(root,key.resourceId+".deleted").exists())throw new Exception("Archivo borrado");
                                            permissions.authorize(key);
                                        }
                                        TransferJobs.wake(this,origin,false);allow.setText("Datos móviles autorizados");status.setText("Autorización guardada para este archivo.");
                                    } catch(Exception e) {status.setText("No se pudo guardar el permiso. Revisa tu sesión.");}
                                }).show());
                            Button remove=new Button(this);remove.setText("Retirar permiso de datos");layout.addView(remove);
                            remove.setOnClickListener(v->{try {permissions.revoke(key);TransferRuntime.permissionRemoved(origin,key);allow.setText("Permitir datos móviles");status.setText("Este archivo vuelve a esperar Wi-Fi.");}catch(Exception e){status.setText("No se pudo retirar el permiso.");}});
                        } catch(Exception ignored) {status.setText("Algunos archivos todavía no están listos.");}
                    }
                    if(!result.isNull("next_delivery_cursor")) {
                        Button next=new Button(this);next.setText("Más archivos");layout.addView(next);
                        next.setOnClickListener(v->{next.setEnabled(false);load(result.optString("next_delivery_cursor"));});
                    }
                });
            } catch(Exception e) {
                if(e instanceof ApiClient.ApiException) {
                    ApiClient.ApiException apiError=(ApiClient.ApiException)e;
                    if((apiError.code.equals("session_revoked") || apiError.code.equals("account_unavailable")) && session!=null) {
                        try {permissions.revokeAccount(origin,session.getString("user_id"),session.getString("device_id"));}catch(Exception ignored){}
                    }
                }
                runOnUiThread(()->{if(!isDestroyed()) status.setText("No se pudieron consultar descargas. Revisa tu conexión y tu sesión.");});
            }
        });
    }
    private void addFailedTransfers(JSONObject account){
        try{
            java.io.File root=LocalLibraryStorage.root(this,origin,account.getString("user_id"),account.getString("device_id"));
            java.io.File[] files=root.listFiles((dir,name)->name.endsWith(".properties"));
            if(files==null)return;
            for(java.io.File file:files){TransferRecord record=TransferRecord.read(file);if(!"failed".equals(record.state))continue;
                TextView title=new TextView(this);title.setText(record.title+" · "+record.message);layout.addView(title);
                Button retry=new Button(this);retry.setText("Reintentar descarga");layout.addView(retry);
                retry.setOnClickListener(v->{try{synchronized(SessionStore.class){JSONObject current=new SessionStore(this,origin).read();
                    if(current==null||!current.optString("user_id").equals(account.optString("user_id"))||!current.optString("device_id").equals(account.optString("device_id")))throw new Exception();
                    if(new java.io.File(root,record.id+".deleted").exists())throw new Exception();
                    TransferRecord latest=TransferRecord.read(file);if("failed".equals(latest.state))TransferRetry.reset(latest);
                }TransferJobs.wake(this,origin,false);retry.setEnabled(false);status.setText("Reintento solicitado. Se mantienen los permisos de red de este archivo.");}catch(Exception e){status.setText("No se pudo reintentar. Revisa tu sesión.");}});
            }
        }catch(Exception e){status.setText("No se pudieron consultar las descargas fallidas.");}
    }
    @Override protected void onDestroy() {if(monitor!=null) monitor.close();executor.shutdownNow();super.onDestroy();}
}
