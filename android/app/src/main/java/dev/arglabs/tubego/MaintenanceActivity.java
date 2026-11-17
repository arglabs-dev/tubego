package dev.arglabs.tubego;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Administrative operations are server jobs; reconnecting never means assumed success. */
public final class MaintenanceActivity extends LocalizedActivity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean refreshing=new java.util.concurrent.atomic.AtomicBoolean();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout layout;private TextView message;private String origin;private JSONObject snapshot;private boolean active;private boolean listening;private String lastRefresh="";private ScrollView scroll;
    private final java.util.function.Consumer<String> updates=changedOrigin->{if(active&&origin!=null&&origin.equals(changedOrigin))render();};
    private final Runnable poll=new Runnable(){@Override public void run(){if(active){refresh();handler.postDelayed(this,5000);}}};
    @Override public void onCreate(Bundle state){super.onCreate(state);layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,48,24,24);message=new TextView(this);layout.addView(message);scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        try{origin=new ApiClient(getIntent().getStringExtra("server_url")).getBaseUrl();JSONObject session=new SessionStore(this,origin).read();if(session==null||!"admin".equals(session.optString("role"))||!"approved".equals(session.optString("status"))){message.setText(Texts.text(MaintenanceActivity.this,"Se requiere una cuenta de administrador aprobada."));origin=null;return;}snapshot=MaintenanceCommands.cached(this,origin);render();}catch(Exception e){message.setText(Texts.text(MaintenanceActivity.this,"No se pudo abrir la administración."));}}
    private void button(String text,Runnable action,boolean enabled){Button b=new Button(this);b.setText(text);b.setEnabled(enabled);b.setOnClickListener(v->action.run());layout.addView(b);}
    private void render(){
        layout.removeAllViews();layout.addView(message);
        try{JSONObject session=new SessionStore(this,origin).read();if(session==null||!"admin".equals(session.optString("role"))||!"approved".equals(session.optString("status"))){snapshot=null;message.setText(Texts.text(this,"Se requiere una cuenta de administrador aprobada."));return;}}catch(Exception e){snapshot=null;return;}
        message.setText(lastRefresh.isEmpty()?Texts.text(this,"Mostrando el último estado guardado; esperando respuesta del servidor."):Texts.text(this,"Estado recibido: ")+lastRefresh);
        JSONObject pending=null;JSONArray local=new JSONArray();
        try{pending=MaintenanceCommands.pending(this,origin);local=MaintenanceCommands.journal(this,origin);}catch(Exception ignored){}
        JSONObject latest=local.length()>0?local.optJSONObject(local.length()-1):pending;
        if(latest!=null){info(layout,Texts.text(this,"Última solicitud de este teléfono"));renderLocal(latest);}
        boolean canSend=pending==null;
        if(!canSend)info(layout,Texts.text(this,"Hay una solicitud sin confirmar. Los reintentos conservan su identificador; espera la respuesta antes de enviar otra."));
        JSONObject installed=snapshot==null?null:snapshot.optJSONObject("installed");
        card(Texts.text(this,"Instalado en el servidor"),installed==null?Texts.text(this,"Conecta para consultar las versiones."):Texts.text(this,"Tubego: ")+installed.optString("tubego")+Texts.text(this,"\nyt-dlp: ")+installed.optString("yt_dlp")+Texts.text(this,"\nRevisión: ")+installed.optString("backend_revision"),false);
        JSONObject versions=snapshot==null?null:snapshot.optJSONObject("versions");
        if(versions!=null)card(Texts.text(this,"Disponible en Internet"),describe(versions)+"\n"+Texts.text(this,"Consultar versiones no instala actualizaciones. Solo se pueden instalar las versiones aprobadas por el operador."),false);
        UiKit.sectionCard(layout,"Consulta y diagnóstico","Estas opciones consultan el servicio. La prueba de velocidad usa la conexión del servidor.");
        button(Texts.text(this,"Actualizar estado"),this::refresh,true);
        button(Texts.text(this,"Consultar versiones disponibles"),()->enqueue("check_versions",null,null,false),canSend);
        button(Texts.text(this,"Medir velocidad del servidor"),()->confirm(Texts.text(this,"La medición descarga 5 MB en el servidor. El teléfono recibe solo el resultado."),()->enqueue("speed_test",null,null,true)),canSend);
        UiKit.sectionCard(layout,"Cambios en el servicio","Actualizar o reiniciar afecta a todos los usuarios y requiere confirmación.");
        JSONObject caps=snapshot==null?null:snapshot.optJSONObject("capabilities");boolean enabled=caps!=null&&caps.optBoolean("mutation_enabled");
        if(!enabled)info(layout,Texts.text(this,"Actualizar y reiniciar requieren un supervisor habilitado por el operador del despliegue."));
        if(enabled)card(Texts.text(this,"Aprobado para instalar"),Texts.text(this,"yt-dlp: ")+approved(caps.optJSONArray("yt_dlp_versions"))+Texts.text(this,"\nRevisión: ")+approved(caps.optJSONArray("backend_revisions")),false);
        button(Texts.text(this,"Actualizar backend"),()->choose("backend_revisions","update_backend"),enabled&&canSend);
        button(Texts.text(this,"Actualizar yt-dlp"),()->choose("yt_dlp_versions","update_ytdlp"),enabled&&canSend);
        button(Texts.text(this,"Reiniciar servicio"),()->confirm(Texts.text(this,"Se esperará a que terminen las descargas del servidor. ¿Reiniciar el servicio?"),()->enqueue("restart",null,null,true)),enabled&&canSend);
        UiKit.sectionCard(layout,"Actividad reciente","Consulta el resultado de las solicitudes antes de repetir una acción.");
        info(layout,Texts.text(this,"Orden: solicitudes más recientes primero, según su creación. Las consultas y los cambios del servicio se procesan por separado; este orden no indica el orden de finalización."));
        java.util.Set<String> localIds=new java.util.HashSet<>();
        for(int n=local.length()-1;n>=0;n--){JSONObject entry=local.optJSONObject(n);if(entry==null)continue;localIds.add(entry.optString("request_id"));}
        if(latest!=null)localIds.add(latest.optString("request_id"));
        java.util.List<JSONObject> history=new java.util.ArrayList<>();
        for(int n=local.length()-2;n>=0;n--)history.add(local.optJSONObject(n));
        JSONArray jobs=snapshot==null?null:snapshot.optJSONArray("jobs");
        if(jobs!=null)for(int n=0;n<jobs.length();n++){JSONObject job=jobs.optJSONObject(n);if(job!=null&&!localIds.contains(job.optString("id")))history.add(job);}
        history.sort((a,b)->b.optString("created_at").compareTo(a.optString("created_at")));
        for(JSONObject row:history){if(row.has("request_id"))renderLocal(row);else renderJob(row);}
        // Compatibility for requests written by earlier app versions without a journal.
        if(local.length()==0)try{JSONObject last=MaintenanceCommands.last(this,origin);if(last!=null&&!last.has("id"))card(Texts.text(this,"Solicitud rechazada"),errorLabel(last.optString("error_code"))+"\n"+last.optString("error_code"),true);}catch(Exception ignored){}
    }
    private JSONObject serverJob(String id){
        JSONArray jobs=snapshot==null?null:snapshot.optJSONArray("jobs");
        if(jobs!=null)for(int n=0;n<jobs.length();n++){JSONObject job=jobs.optJSONObject(n);if(job!=null&&id.equals(job.optString("id")))return job;}
        return null;
    }
    private void renderLocal(JSONObject entry){
        JSONObject job=serverJob(entry.optString("request_id"));if(job==null)job=entry.optJSONObject("server_job");
        String transport=entry.optString("transport_status","saved");
        String label=switch(transport){
            case "sending"->Texts.text(this,"Enviando; esperando confirmación del servidor");
            case "retry_wait"->Texts.text(this,"El servidor pidió esperar; reintento pendiente");
            case "unconfirmed"->Texts.text(this,"Envío sin confirmar; se reintentará con el mismo identificador");
            case "accepted"->job==null?Texts.text(this,"Aceptada por el servidor"):stateLabel(job.optString("status"));
            case "rejected"->Texts.text(this,"Solicitud rechazada");
            default->Texts.text(this,"Guardada en el teléfono; sin confirmación del servidor");
        };
        String detail=Texts.text(this,"Solicitud: ")+entry.optString("request_id")+"\n"+Texts.text(this,"Creada: ")+date(entry.optString("created_at"));
        if(entry.has("version"))detail+="\n"+Texts.text(this,"Destino solicitado: ")+entry.optString("version");
        if(entry.has("sent_at"))detail+="\n"+Texts.text(this,"Primer intento de envío: ")+date(entry.optString("sent_at"));
        if(entry.has("last_attempt_at"))detail+="\n"+Texts.text(this,"Último intento de envío: ")+date(entry.optString("last_attempt_at"))+" · "+entry.optInt("attempts");
        if(entry.has("received_at"))detail+="\n"+Texts.text(this,"Respuesta recibida: ")+date(entry.optString("received_at"));
        if(entry.has("last_response_at"))detail+="\n"+Texts.text(this,"Respuesta recibida: ")+date(entry.optString("last_response_at"))+" · HTTP "+entry.optInt("last_http_status");
        if(job!=null)detail+="\n"+jobDetail(job);
        card(actionLabel(entry.optString("action"))+" · "+label,detail,"rejected".equals(transport)||job!=null&&"failed".equals(job.optString("status")));
    }
    private String jobDetail(JSONObject job){
        String status=job.optString("status");
        String detail=Texts.text(this,"Aceptada por el servidor: ")+date(job.optString("created_at"))+"\n"+Texts.text(this,"Último cambio en el servidor: ")+date(job.optString("updated_at"));
        JSONObject target=job.optJSONObject("target");
        if(target!=null&&target.length()>0)detail+="\n"+Texts.text(this,"Destino solicitado: ")+target.optString("version",target.optString("revision"));
        JSONArray events=job.optJSONArray("events");
        if(events!=null)for(int n=0;n<events.length();n++){JSONObject event=events.optJSONObject(n);if(event!=null)detail+="\n"+date(event.optString("at"))+" · "+stateLabel(event.optString("status"))+" · "+phaseLabel(event.optString("phase"));}
        if(!"succeeded".equals(status)&&!"failed".equals(status))detail+="\n"+phaseLabel(job.optString("phase"));
        if(!job.isNull("result"))detail+="\n"+describe(job.optJSONObject("result"));
        if(!job.isNull("error_code"))detail+="\n"+errorLabel(job.optString("error_code"))+"\n"+Texts.text(this,"Código: ")+job.optString("error_code");
        return detail;
    }
    private void renderJob(JSONObject job){card(actionLabel(job.optString("action"))+" · "+stateLabel(job.optString("status")),Texts.text(this,"Solicitud: ")+job.optString("id")+"\n"+jobDetail(job),"failed".equals(job.optString("status")));}
    private String actionLabel(String value){return switch(value){case "check_versions"->Texts.text(MaintenanceActivity.this,"Consulta de versiones");case "speed_test"->Texts.text(MaintenanceActivity.this,"Velocidad del servidor");case "update_backend"->Texts.text(MaintenanceActivity.this,"Actualización de Tubego");case "update_ytdlp"->Texts.text(MaintenanceActivity.this,"Actualización de yt-dlp");case "restart"->Texts.text(MaintenanceActivity.this,"Reinicio del servicio");default->Texts.text(this,"Mantenimiento");};}
    private String stateLabel(String value){return switch(value){case "queued"->Texts.text(MaintenanceActivity.this,"Pendiente");case "waiting_worker"->Texts.text(MaintenanceActivity.this,"Esperando descargas activas");case "running"->Texts.text(MaintenanceActivity.this,"En curso");case "succeeded"->Texts.text(MaintenanceActivity.this,"Completado");case "failed"->Texts.text(MaintenanceActivity.this,"Falló");default->Texts.text(MaintenanceActivity.this,"Estado pendiente");};}
    private String errorLabel(String code){if(code.isEmpty()||code.equals("null"))return "";return switch(code){case "capability_unavailable"->Texts.text(MaintenanceActivity.this,"El despliegue no tiene mantenimiento habilitado.");case "administrator_session_unavailable"->Texts.text(MaintenanceActivity.this,"La sesión de administrador ya no está disponible.");case "deployment_verification_failed","supervisor_command_failed","operation_failed"->Texts.text(MaintenanceActivity.this,"No se pudo verificar el servicio. El operador debe revisar el despliegue.");case "revision_not_staged_for_installed_backend","revision_not_staged"->Texts.text(this,"El operador debe preparar la revisión del backend antes de actualizar.");case "maintenance_conflict"->Texts.text(this,"Hay otra operación pendiente o el servicio requiere recuperación. Consulta el historial antes de repetirla.");case "version_not_approved"->Texts.text(this,"La versión solicitada no está aprobada por el operador.");case "operator_recovered_interrupted_operation"->Texts.text(MaintenanceActivity.this,"El operador recuperó el servicio; la operación anterior quedó interrumpida.");default->Texts.text(MaintenanceActivity.this,"No se pudo completar la operación. Revisa los permisos o la configuración del servidor.");};}
    private String describe(JSONObject result){if(result==null)return "";if(result.has("version_changed")&&!result.optBoolean("version_changed"))return Texts.text(this,"Sin cambio de versión; el servidor ya tenía la versión solicitada.")+"\n"+describeInstalled(result);return describeInstalled(result);}
    private String describeInstalled(JSONObject result){if(result.has("server_download_mbps"))return Texts.text(MaintenanceActivity.this,"Descarga del servidor: ")+result.optDouble("server_download_mbps")+Texts.text(MaintenanceActivity.this," Mbps (5 MB)");JSONObject available=result.optJSONObject("available");JSONObject installed=result.optJSONObject("installed");JSONObject values=available!=null?available:installed;return values==null?"": Texts.text(MaintenanceActivity.this,"yt-dlp: ")+values.optString("yt_dlp")+Texts.text(MaintenanceActivity.this,"\nRevisión Tubego: ")+values.optString("backend_revision");}
    private void choose(String field,String action){
        JSONArray choices=snapshot.optJSONObject("capabilities").optJSONArray(field);
        JSONObject installed=snapshot.optJSONObject("installed");
        String current=installed==null?"":installed.optString(action.equals("update_backend")?"backend_revision":"yt_dlp");
        java.util.List<String> candidates=new java.util.ArrayList<>();
        if(choices!=null)for(int n=0;n<choices.length();n++){String value=choices.optString(n);if(!value.equals(current))candidates.add(value);}
        if(candidates.isEmpty()){message.setText(Texts.text(this,"No hay otra versión aprobada para instalar. El operador debe aprobar la versión disponible y preparar el despliegue."));return;}
        String[] values=candidates.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(Texts.text(this,"Versión aprobada")).setItems(values,(d,w)->confirm(Texts.text(this,"Actualizar a ")+values[w]+Texts.text(this,". Se esperará a las descargas activas y se comprobará el servicio al terminar."),()->enqueue(action,action.equals("update_backend")?values[w]:null,action.equals("update_ytdlp")?values[w]:null,true))).setNegativeButton(Texts.text(this,"Cancelar"),null).show();
    }
    private void info(LinearLayout parent,String text){TextView v=new TextView(this);v.setText(text);v.setTextSize(14);v.setTextIsSelectable(true);parent.addView(v);}
    private void card(String title,String detail,boolean failed){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);int padding=Ds.dp(this,16);box.setPadding(padding,padding,padding,padding);
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(failed?0xFFFFF0F0:0xFFFFFFFF);bg.setCornerRadius(Ds.dp(this,16));bg.setStroke(Ds.dp(this,1),failed?0xFFB3261E:0xFFDADADA);box.setBackground(bg);
        TextView heading=new TextView(this);heading.setText(title);heading.setTextSize(17);heading.setTypeface(null,android.graphics.Typeface.BOLD);box.addView(heading);info(box,detail);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=Ds.dp(this,12);layout.addView(box,params);
    }
    private String approved(JSONArray values){java.util.List<String> labels=new java.util.ArrayList<>();if(values!=null)for(int i=0;i<values.length();i++)labels.add(values.optString(i));return android.text.TextUtils.join(", ",labels);}
    private String date(String value){try{return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.MEDIUM).format(java.util.Date.from(java.time.Instant.parse(value)));}catch(Exception e){return value.isEmpty()?"—":value;}}
    private String phaseLabel(String value){return switch(value){case "finished"->Texts.text(this,"Finalizada");case "pending"->Texts.text(this,"Recibida; pendiente de ejecución");case "checking"->Texts.text(this,"Consultando versiones en Internet");case "measuring_server"->Texts.text(this,"Midiendo la conexión del servidor");case "waiting_for_downloads"->Texts.text(this,"Esperando a que terminen las descargas activas");case "deploying"->Texts.text(this,"Aplicando cambios y verificando el servicio");default->value;};}
    private void confirm(String text,Runnable yes){new AlertDialog.Builder(this).setMessage(text).setNegativeButton(Texts.text(MaintenanceActivity.this,"Cancelar"),null).setPositiveButton(Texts.text(MaintenanceActivity.this,"Confirmar"),(d,w)->yes.run()).show();}
    private void enqueue(String action,String revision,String version,boolean confirmed){try{JSONObject command=new JSONObject().put("action",action).put("confirmed",confirmed);if(revision!=null)command.put("revision",revision);if(version!=null)command.put("version",version);MaintenanceCommands.enqueue(this,origin,command);render();scroll.smoothScrollTo(0,0);refresh();}catch(Exception e){message.setText(Texts.text(MaintenanceActivity.this,"No se pudo guardar: verifica tu sesión o espera a que se envíe la solicitud pendiente."));}}
    private void refresh(){if(origin==null||!refreshing.compareAndSet(false,true))return;network.execute(()->{try{MaintenanceCommands.flush(this,origin);JSONObject session=new SessionStore(this,origin).read();if(session==null)return;JSONObject value=new ApiClient(origin).request("GET","/admin/maintenance",null,session.getString("token"));MaintenanceCommands.cache(this,origin,session,value);runOnUiThread(()->{if(!isDestroyed()){try{if(MaintenanceCommands.same(session,new SessionStore(this,origin).read())){snapshot=value;lastRefresh=java.text.DateFormat.getTimeInstance().format(new java.util.Date());render();}else{snapshot=null;layout.removeAllViews();layout.addView(message);message.setText(Texts.text(MaintenanceActivity.this,"La sesión cambió. Abre nuevamente la administración."));}}catch(Exception ignored){}}});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){try{snapshot=MaintenanceCommands.cached(this,origin);}catch(Exception ignored){snapshot=null;}render();message.setText(Texts.text(MaintenanceActivity.this,"Sin conexión o servicio reiniciándose. La solicitud conserva su identificador; consulta su estado al recuperar conexión."));}});}finally{refreshing.set(false);}});}
    @Override protected void onResume(){super.onResume();active=origin!=null;if(active){if(!listening){MaintenanceCommands.observe(updates);listening=true;}try{snapshot=MaintenanceCommands.cached(this,origin);render();}catch(Exception ignored){}handler.post(poll);}}
    @Override protected void onPause(){active=false;if(listening){MaintenanceCommands.unobserve(updates);listening=false;}handler.removeCallbacksAndMessages(null);super.onPause();}
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
