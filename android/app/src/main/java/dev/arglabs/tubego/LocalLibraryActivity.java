package dev.arglabs.tubego;

import android.app.*;import android.os.Bundle;import android.content.*;import android.content.pm.ResolveInfo;import android.net.Uri;import android.widget.*;
import org.json.*;import java.io.*;import java.util.*;import java.util.concurrent.*;

/** Permanent private history plus physical local files. Player opening never downloads media. */
public final class LocalLibraryActivity extends Activity {
 private final ExecutorService disk=Executors.newSingleThreadExecutor();
 private LinearLayout list;private TextView status;private EditText search;private Spinner filter;private Button more;
 private String origin,nextCursor="";private int visibleLimit=50;private File root;private String token;private int generation=0;
 private final Map<String,JSONObject> rows=new HashMap<>();
 @Override public void onCreate(Bundle state){super.onCreate(state);
  LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,24,24,24);
  TextView title=new TextView(this);title.setText("Biblioteca e historial");title.setTextSize(24);layout.addView(title);
  status=new TextView(this);layout.addView(status);search=new EditText(this);search.setHint("Buscar por título");search.setSingleLine(true);search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});layout.addView(search);
  filter=new Spinner(this);filter.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Todos","Descargados en este teléfono","Pendientes","Con error","Borrados / No disponibles"}));layout.addView(filter);
  Button refresh=new Button(this);refresh.setText("Buscar / Actualizar");refresh.setOnClickListener(v->load(false));layout.addView(refresh);
  more=new Button(this);more.setText("Cargar página siguiente");more.setEnabled(false);more.setOnClickListener(v->{visibleLimit+=50;if(nextCursor.isEmpty())render(search.getText().toString().trim(),filter.getSelectedItemPosition());else load(true);});layout.addView(more);
  ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);layout.addView(scroll);setContentView(layout);
  try{origin=new ApiClient(getIntent().getStringExtra("server_url")).getBaseUrl();}catch(Exception e){status.setText("Servidor inválido");return;}
  load(false);
 }
 private boolean sessionCurrent(){try{return token!=null&&token.equals(new SessionStore(this,origin).token());}catch(Exception e){return false;}}
 private void load(boolean append){if(origin==null)return;if(!append)visibleLimit=50;final int load=++generation;final String query=search.getText().toString().trim();final int selected=filter.getSelectedItemPosition();final String cursor=append?nextCursor:"";status.setText("Leyendo historial…");more.setEnabled(false);
  disk.execute(()->{
   Map<String,JSONObject> collected=new HashMap<>();String notice="",next="";
   try{
    synchronized(SessionStore.class){JSONObject session=new SessionStore(this,origin).read();if(session==null||!"approved".equals(session.optString("status")))throw new Exception("Inicia sesión con una cuenta aprobada.");
     token=session.getString("token");root=LocalLibraryStorage.root(this,origin,session.getString("user_id"),session.getString("device_id"));
     for(Map.Entry<String,String> entry:new HistoryCache(root).read().entrySet())try{collected.put(entry.getKey(),new JSONObject(entry.getValue()));}catch(JSONException ignored){}
     File[] files=root.listFiles((d,n)->n.endsWith(".properties"));if(files!=null)for(File file:files)try{
      TransferRecord r=OfflineMediaAccess.record(root,file.getName().replace(".properties",""));
      if(!collected.containsKey(r.id))collected.put(r.id,new JSONObject().put("id",r.id).put("title",r.title).put("media_format",r.mediaFormat).put("created_at",r.createdAt));
     }catch(Exception ignored){}
    }
    final Map<String,JSONObject> saved=new HashMap<>(collected);
    runOnUiThread(()->{if(isDestroyed()||load!=generation)return;if(!sessionCurrent()){rows.clear();list.removeAllViews();status.setText("Sesión no disponible.");return;}rows.clear();rows.putAll(saved);status.setText("Historial guardado en el teléfono. Actualizando si hay conexión…");render(query,selected);});
    final String currentToken=token;File currentRoot=root;
    try {
     HistoryOpened.flush(this,origin,currentToken,currentRoot);
     String path="/resources?order=newest&limit=50&search="+java.net.URLEncoder.encode(query,"UTF-8")+"&cursor="+java.net.URLEncoder.encode(cursor,"UTF-8");
     if(selected==2)path+="&status=pending";if(selected==3)path+="&status=error";if(selected==4)path+="&status=unavailable";
     JSONObject page=new ApiClient(origin).request("GET",path,null,currentToken);JSONArray items=page.getJSONArray("items");Map<String,String> cache=new HashMap<>();
     for(int i=0;i<items.length();i++){JSONObject row=items.getJSONObject(i);JSONObject local=collected.get(row.getString("id"));
      if(local!=null && local.optString("last_opened_at","").compareTo(row.optString("last_opened_at",""))>0)row.put("last_opened_at",local.getString("last_opened_at"));
      collected.put(row.getString("id"),row);cache.put(row.getString("id"),row.toString());}
     synchronized(SessionStore.class){if(!sessionCurrent())return;new HistoryCache(currentRoot).merge(cache);}
     next=page.isNull("next_cursor")?"":page.getString("next_cursor");notice="Historial privado sincronizado. Cache sin conexión: hasta 2000 fichas consultadas; el historial completo permanece en el servidor.";
    }catch(Exception e){if(!sessionCurrent())throw new Exception("La sesión dejó de estar disponible.");notice="Sin conexión al servidor. Mostrando fichas guardadas y archivos de este teléfono; las acciones quedarán pendientes hasta recuperar conexión.";}
   }catch(Exception e){collected.clear();notice=e.getMessage();}
   final Map<String,JSONObject> result=collected;final String message=notice,cursorNext=next;
   runOnUiThread(()->{if(isDestroyed()||load!=generation)return;if(!result.isEmpty()&&!sessionCurrent()){rows.clear();list.removeAllViews();status.setText("Sesión no disponible.");return;}rows.clear();rows.putAll(result);nextCursor=cursorNext;more.setEnabled(!nextCursor.isEmpty());status.setText(message);render(query,selected);});
  });
 }
 private TransferRecord local(String id){try{return root==null?null:OfflineMediaAccess.record(root,id);}catch(Exception e){return null;}}
 private static String phase(String phase){switch(phase){case "pending":case "queued":return "Pendiente";case "analysis":case "analyzing":return "Analizando";case "downloading":case "server_download":return "Descargando";case "postprocessing":return "Preparando archivo";case "cancelling":return "Cancelando";case "ready":return "Listo en servidor";case "paused":case "retry_wait":return "Pausado";case "complete":return "Completado";case "cancelled":return "Cancelado";case "error":case "failed":return "Error";default:return phase;}}
 private void render(String query,int selected){list.removeAllViews();int matches=0;List<JSONObject> ordered=new ArrayList<>(rows.values());ordered.sort(Comparator.comparing((JSONObject r)->r.optString("created_at")).reversed().thenComparing(r->r.optString("id")));
  for(JSONObject row:ordered){String id=row.optString("id"),title=row.optString("title","Sin título");if(!title.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))continue;
   TransferRecord record=local(id);boolean downloaded=false;try{downloaded=record!=null&&OfflineMediaAccess.available(record);}catch(Exception ignored){}
   JSONObject task=row.optJSONObject("latest_task");String taskState=task==null?"":task.optString("status"),localState=record==null?"":record.state;
   boolean pending=Arrays.asList("queued","running","paused").contains(taskState)||Arrays.asList("pending","downloading","paused","retry_wait").contains(localState);
   boolean error=taskState.equals("failed")||localState.equals("failed");JSONObject delivery=row.optJSONObject("device_delivery");
   boolean unavailable=!downloaded&&((row.has("server_deleted_at")&&!row.isNull("server_deleted_at"))||localState.equals("deleted")||localState.equals("unavailable")||delivery!=null&&!delivery.isNull("deleted_at")||!pending&&!error&&!row.optBoolean("server_available",false));
   if(selected==1&&!downloaded||selected==2&&!pending||selected==3&&!error||selected==4&&!unavailable)continue;
   if(++matches>visibleLimit)continue;
   TextView label=new TextView(this);String state=downloaded?"Descargado en este teléfono":record!=null?(localState.equals("downloading")?"Descargando en teléfono":phase(localState)):task!=null?phase(task.optString("phase",taskState)):"No disponible localmente";
   String details=title+"\n"+state+" · "+row.optString("media_format","video")+" "+row.optString("quality","")+"\n"+row.optString("source_url","")+"\n"+row.optString("created_at","");
   if(task!=null)details+="\nServidor: "+phase(task.optString("phase",taskState))+" · "+Math.round(task.optDouble("progress",0)*100)+"%";
   if(record!=null && !downloaded)details+="\nTeléfono: "+record.offset()+" / "+record.size+" bytes";
   if(record!=null&&!record.message.isEmpty())details+="\n"+record.message;
   if(!row.isNull("last_opened_at")&&row.has("last_opened_at"))details+="\nÚltimo abierto: "+row.optString("last_opened_at");
   if(task!=null&&!task.isNull("error_message"))details+="\n"+task.optString("error_message");label.setText(details);list.addView(label);
   if(downloaded)addButton("Reproducir archivo local",()->open(id));
   if(pending||row.optBoolean("server_available"))addButton("Poner siguiente en la cola",()->action("/resources/"+id+"/priority",true));
   if(task!=null && Arrays.asList("queued","running","paused").contains(taskState))addButton("Cancelar descarga del servidor",()->action("/tasks/"+task.optString("id")+"/cancel",false));
   if(task!=null && Arrays.asList("failed","cancelled").contains(taskState))addButton("Reintentar descarga del servidor",()->action("/tasks/"+task.optString("id")+"/retry",false));
   if(localState.equals("failed"))addButton("Reintentar descarga del teléfono…",()->launch("NetworkPolicyActivity",id));
   if(!downloaded)addButton("Volver a solicitar / Descargar",()->launch("ResubmitActivity",id));
   addButton("Borrar archivos…",()->launch("ResourceDeletionActivity",id));
  }
  more.setEnabled(matches>visibleLimit||!nextCursor.isEmpty());
 }
 private void addButton(String title,Runnable run){Button button=new Button(this);button.setText(title);button.setOnClickListener(v->run.run());list.addView(button);}
 private void launch(String name,String id){try{startActivity(new Intent().setClassName(this,getPackageName()+"."+name).putExtra("server_url",origin).putExtra("resource_id",id));}catch(ActivityNotFoundException e){status.setText("Esta acción todavía no está disponible en esta versión.");}}
 private void action(String path,boolean priority){status.setText("Guardando acción…");disk.execute(()->{try{
  String[] parts=path.split("/");String kind=priority?"resource_priority":"task_"+parts[3];JSONObject payload=new JSONObject().put(priority?"resource_id":"task_id",parts[2]);
  synchronized(SessionStore.class){if(!sessionCurrent())throw new Exception();CommandDispatch.enqueue(this,origin,kind,payload);}
  runOnUiThread(()->{if(!isDestroyed())status.setText("Acción guardada. Se enviará con cualquier conexión; consulta Acciones pendientes y errores.");});CommandDispatch.flush(this,origin);
 }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText("No se pudo guardar la acción. Revisa la sesión y el espacio del teléfono.");});}});}

 private void open(String id){status.setText("Verificando archivo local…");disk.execute(()->{
  try{String openingToken=new SessionStore(this,origin).token();Uri uri=PrivateMediaContentProvider.create(this,origin,id);if(!openingToken.equals(new SessionStore(this,origin).token()))throw new SecurityException("La sesión cambió");String mime=getContentResolver().getType(uri);
   runOnUiThread(()->{if(isDestroyed())return;Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);view.setClipData(ClipData.newRawUri("Tubego",uri));
    List<ResolveInfo> players=getPackageManager().queryIntentActivities(view,0);players.sort(Comparator.comparing(p->!"org.videolan.vlc".equals(p.activityInfo.packageName)));
    if(players.isEmpty()){new AlertDialog.Builder(this).setMessage("No hay un reproductor compatible. Instala VLC u otro reproductor de archivos locales.").setPositiveButton("Aceptar",null).show();return;}
    String[] names=new String[players.size()];for(int i=0;i<names.length;i++)names[i]=players.get(i).loadLabel(getPackageManager()).toString();
    new AlertDialog.Builder(this).setTitle("Reproducir archivo local").setItems(names,(dialog,which)->{ResolveInfo player=players.get(which);Intent explicit=new Intent(view).setComponent(new ComponentName(player.activityInfo.packageName,player.activityInfo.name));
     try{startActivity(explicit);disk.execute(()->{try{HistoryOpened.record(this,origin,id,openingToken);}catch(Exception ignored){}});status.setText("PiP y audio con pantalla bloqueada dependen del reproductor.");}catch(ActivityNotFoundException e){status.setText("El reproductor ya no está disponible.");}
    }).show();
   });
  }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText("No se pudo abrir el archivo local: "+e.getMessage());});}
 });}
 @Override protected void onResume(){super.onResume();if(origin!=null&&token!=null)disk.execute(()->{if(!sessionCurrent())runOnUiThread(()->{if(!isDestroyed()){rows.clear();list.removeAllViews();status.setText("Sesión no disponible.");}});});}
 @Override protected void onDestroy(){disk.shutdownNow();super.onDestroy();}
}
