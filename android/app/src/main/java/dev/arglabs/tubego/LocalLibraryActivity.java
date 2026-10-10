package dev.arglabs.tubego;

import android.app.*;import android.os.Bundle;import android.content.*;import android.content.pm.ResolveInfo;import android.net.Uri;import android.widget.*;
import org.json.*;import java.io.*;import java.util.*;import java.util.concurrent.*;

/** Permanent private history plus physical local files. Player opening never downloads media. */
public final class LocalLibraryActivity extends LocalizedActivity {
 private final ExecutorService disk=Executors.newSingleThreadExecutor();
 private LinearLayout actionHost;private PopupMenu cardMenu;
 private LinearLayout list;private TextView status;private EditText search;private Spinner filter;private Button more;
 private String origin,nextCursor="";private int visibleLimit=50;private File root;private String token;private int generation=0;
 private final Map<String,JSONObject> rows=new HashMap<>();
 @Override public void onCreate(Bundle state){super.onCreate(state);
  LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,24,24,24);
  TextView title=new TextView(this);title.setText(Texts.text(LocalLibraryActivity.this,"Biblioteca e historial"));title.setTextSize(25);layout.addView(title);
  TextView subtitle=UiKit.text(this,Texts.text(this,"Tu contenido, siempre contigo."),14,UiKit.MUTED);layout.addView(subtitle);
  status=new TextView(this);status.setTextSize(12);status.setMaxLines(2);status.setEllipsize(android.text.TextUtils.TruncateAt.END);layout.addView(status);search=new EditText(this);search.setHint(Texts.text(LocalLibraryActivity.this,"Buscar por título"));search.setSingleLine(true);search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});layout.addView(search);
  filter=new Spinner(this);filter.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{Texts.text(LocalLibraryActivity.this,"Todos"),Texts.text(LocalLibraryActivity.this,"Descargados en este teléfono"),Texts.text(LocalLibraryActivity.this,"Pendientes"),Texts.text(LocalLibraryActivity.this,"Con error"),Texts.text(LocalLibraryActivity.this,"Borrados / No disponibles")}));layout.addView(filter);
  Button refresh=new Button(this);refresh.setText(Texts.text(LocalLibraryActivity.this,"Actualizar"));refresh.setOnClickListener(v->load(false));layout.removeView(filter);LinearLayout controls=new LinearLayout(this);UiKit.custom(controls);controls.addView(filter,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams refreshParams=new LinearLayout.LayoutParams(UiKit.dp(this,148),-2);refreshParams.leftMargin=UiKit.dp(this,8);controls.addView(refresh,refreshParams);layout.addView(controls);
  more=new Button(this);more.setText(Texts.text(LocalLibraryActivity.this,"Cargar página siguiente"));more.setEnabled(false);more.setVisibility(android.view.View.GONE);more.setOnClickListener(v->{visibleLimit+=50;if(nextCursor.isEmpty())render(search.getText().toString().trim(),filter.getSelectedItemPosition());else load(true);});layout.addView(more);
  list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);layout.addView(list,new LinearLayout.LayoutParams(-1,-2));ScrollView page=new ScrollView(this);page.addView(layout);setContentView(page);
  try{origin=new ApiClient(getIntent().getStringExtra("server_url")).getBaseUrl();}catch(Exception e){status.setText(Texts.text(LocalLibraryActivity.this,"Servidor inválido"));return;}
  search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);search.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){load(false);return true;}return false;});
  load(false);
 }
 private boolean sessionCurrent(){try{return token!=null&&token.equals(new SessionStore(this,origin).token());}catch(Exception e){return false;}}
 private void load(boolean append){if(origin==null)return;if(!append)visibleLimit=50;final int load=++generation;final String query=search.getText().toString().trim();final int selected=filter.getSelectedItemPosition();final String cursor=append?nextCursor:"";status.setText(Texts.text(LocalLibraryActivity.this,"Leyendo historial…"));more.setEnabled(false);
  disk.execute(()->{
   Map<String,JSONObject> collected=new HashMap<>();String notice="",next="";
   try{
    synchronized(SessionStore.class){JSONObject session=new SessionStore(this,origin).read();if(session==null||!"approved".equals(session.optString("status")))throw new Exception(Texts.text(LocalLibraryActivity.this,"Inicia sesión con una cuenta aprobada."));
     token=session.getString("token");root=LocalLibraryStorage.root(this,origin,session.getString("user_id"),session.getString("device_id"));
     for(Map.Entry<String,String> entry:new HistoryCache(root).read().entrySet())try{collected.put(entry.getKey(),new JSONObject(entry.getValue()));}catch(JSONException ignored){}
     File[] files=root.listFiles((d,n)->n.endsWith(".properties"));if(files!=null)for(File file:files)try{
      TransferRecord r=OfflineMediaAccess.record(root,file.getName().replace(".properties",""));
      if(!collected.containsKey(r.id))collected.put(r.id,new JSONObject().put("id",r.id).put("title",r.title).put("media_format",r.mediaFormat).put("created_at",r.createdAt));
     }catch(Exception ignored){}
    }
    final Map<String,JSONObject> saved=new HashMap<>(collected);
    runOnUiThread(()->{if(isDestroyed()||load!=generation)return;if(!sessionCurrent()){rows.clear();list.removeAllViews();status.setText(Texts.text(LocalLibraryActivity.this,"Sesión no disponible."));return;}rows.clear();rows.putAll(saved);status.setText(Texts.text(LocalLibraryActivity.this,"Historial guardado en el teléfono. Actualizando si hay conexión…"));render(query,selected);});
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
     next=page.isNull("next_cursor")?"":page.getString("next_cursor");notice=Texts.text(LocalLibraryActivity.this,"Historial privado sincronizado. Cache sin conexión: hasta 2000 fichas consultadas; el historial completo permanece en el servidor.");
    }catch(Exception e){if(!sessionCurrent())throw new Exception(Texts.text(LocalLibraryActivity.this,"La sesión dejó de estar disponible."));notice=Texts.text(LocalLibraryActivity.this,"Sin conexión al servidor. Mostrando fichas guardadas y archivos de este teléfono; las acciones quedarán pendientes hasta recuperar conexión.");}
   }catch(Exception e){collected.clear();notice=Texts.error(this,e);}
   final Map<String,JSONObject> result=collected;final String message=notice,cursorNext=next;
   runOnUiThread(()->{if(isDestroyed()||load!=generation)return;if(!result.isEmpty()&&!sessionCurrent()){rows.clear();list.removeAllViews();status.setText(Texts.text(LocalLibraryActivity.this,"Sesión no disponible."));return;}rows.clear();rows.putAll(result);nextCursor=cursorNext;more.setEnabled(!nextCursor.isEmpty());status.setText(message);render(query,selected);});
  });
 }
 private TransferRecord local(String id){try{return root==null?null:OfflineMediaAccess.record(root,id);}catch(Exception e){return null;}}
 private String phase(String phase){switch(phase){case "pending":case "queued":return Texts.text(LocalLibraryActivity.this,"Pendiente");case "analysis":case "analyzing":return Texts.text(LocalLibraryActivity.this,"Analizando");case "downloading":case "server_download":return Texts.text(LocalLibraryActivity.this,"Descargando");case "postprocessing":return Texts.text(LocalLibraryActivity.this,"Preparando archivo");case "cancelling":return Texts.text(LocalLibraryActivity.this,"Cancelando");case "ready":return Texts.text(LocalLibraryActivity.this,"Listo en servidor");case "paused":case "retry_wait":return Texts.text(LocalLibraryActivity.this,"Pausado");case "complete":return Texts.text(LocalLibraryActivity.this,"Completado");case "cancelled":return Texts.text(LocalLibraryActivity.this,"Cancelado");case "error":case "failed":return Texts.text(LocalLibraryActivity.this,"Error");default:return Texts.state(this,phase);}}
 private void render(String query,int selected){list.removeAllViews();int matches=0;List<JSONObject> ordered=new ArrayList<>(rows.values());ordered.sort(Comparator.comparing((JSONObject r)->r.optString("created_at")).reversed().thenComparing(r->r.optString("id")));
  for(JSONObject row:ordered){String id=row.optString("id"),title=row.optString("title",Texts.text(LocalLibraryActivity.this,"Sin título"));if(!title.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))continue;
   TransferRecord record=local(id);boolean downloaded=false;try{downloaded=record!=null&&OfflineMediaAccess.available(record);}catch(Exception ignored){}
   JSONObject task=row.optJSONObject("latest_task");String taskState=task==null?"":task.optString("status"),localState=record==null?"":record.state;
   boolean pending=Arrays.asList("queued","running","paused").contains(taskState)||Arrays.asList("pending","downloading","paused","retry_wait").contains(localState);
   boolean error=taskState.equals("failed")||localState.equals("failed");JSONObject delivery=row.optJSONObject("device_delivery");
   boolean unavailable=!downloaded&&((row.has("server_deleted_at")&&!row.isNull("server_deleted_at"))||localState.equals("deleted")||localState.equals("unavailable")||delivery!=null&&!delivery.isNull("deleted_at")||!pending&&!error&&!row.optBoolean("server_available",false));
   if(selected==1&&!downloaded||selected==2&&!pending||selected==3&&!error||selected==4&&!unavailable)continue;
   if(++matches>visibleLimit)continue;
   TextView label=new TextView(this);String state=unavailable?Texts.text(this,"Sin archivo disponible"):downloaded?Texts.text(LocalLibraryActivity.this,"Descargado en este teléfono"):record!=null?(localState.equals("downloading")?Texts.text(LocalLibraryActivity.this,"Descargando en teléfono"):phase(localState)):task!=null?phase(task.optString("phase",taskState)):Texts.text(LocalLibraryActivity.this,"No disponible localmente");
   String details=title+"\n"+state+" · "+Texts.quality(this,"audio".equals(row.optString("media_format"))?"audio":row.optString("quality","best"))+"\n"+row.optString("source_url","")+"\n"+row.optString("created_at","");
   if(task!=null){String qualityNotice=Texts.qualityNotice(this,task.optString("quality_notice"));if(!qualityNotice.isEmpty())details+="\n"+qualityNotice;}
   if(task!=null)details+=Texts.text(LocalLibraryActivity.this,"\nServidor: ")+phase(task.optString("phase",taskState))+" · "+Math.round(task.optDouble("progress",0)*100)+"%";
   if(record!=null && !downloaded)details+=Texts.text(LocalLibraryActivity.this,"\nTeléfono: ")+record.offset()+" / "+record.size+Texts.text(LocalLibraryActivity.this," bytes");
   if(record!=null&&!record.failureCode.isEmpty())details+="\n"+Texts.failure(this,record.failureCode);
   if(record!=null&&"device_storage".equals(record.pauseReason))details+="\n"+Texts.text(this,"Espacio insuficiente");
   if(!row.isNull("last_opened_at")&&row.has("last_opened_at"))details+=Texts.text(LocalLibraryActivity.this,"\nÚltimo abierto: ")+row.optString("last_opened_at");
   if(task!=null&&!task.isNull("error_message"))details+="\n"+Texts.failure(this,task.optString("error_code"));
   LinearLayout card=UiKit.card(this);list.addView(card,new LinearLayout.LayoutParams(-1,-2));UiKit.margin(card,0,14);actionHost=card;
   LinearLayout header=new LinearLayout(this);header.setGravity(android.view.Gravity.TOP);UiKit.custom(header);card.addView(header);
   UiKit.Cover cover=new UiKit.Cover(this,id,row.optString("media_format"));header.addView(cover,new LinearLayout.LayoutParams(UiKit.dp(this,70),UiKit.dp(this,84)));
   LinearLayout info=UiKit.column(this);UiKit.custom(info);LinearLayout.LayoutParams infoParams=new LinearLayout.LayoutParams(0,-2,1);infoParams.leftMargin=UiKit.dp(this,14);header.addView(info,infoParams);
   TextView name=UiKit.text(this,title,17,UiKit.INK);name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));name.setMaxLines(3);name.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(name);
   TextView statePill=UiKit.badge(this,state,downloaded);if(error){statePill.setBackground(UiKit.rounded(0xFFFCE8E5,0,20,this));statePill.setTextColor(0xFF9B322C);}else if(pending){statePill.setBackground(UiKit.rounded(0xFFFCF0D7,0,20,this));statePill.setTextColor(0xFF825319);}info.addView(statePill);String quality=Texts.quality(this,"audio".equals(row.optString("media_format"))?"audio":row.optString("quality","best"));TextView variant=UiKit.text(this,quality,12,UiKit.MUTED);info.addView(variant);UiKit.margin(variant,6,0);
   ImageButton options=new ImageButton(this);options.setImageDrawable(new UiKit.Glyph("settings",UiKit.MUTED,this));options.setBackground(UiKit.rounded(UiKit.WHITE,0,16,this));options.setContentDescription(Texts.text(this,"Más opciones"));header.addView(options,new LinearLayout.LayoutParams(UiKit.dp(this,48),UiKit.dp(this,48)));
   cardMenu=new PopupMenu(this,options);PopupMenu menu=cardMenu;options.setOnClickListener(v->menu.show());
   String host=Uri.parse(row.optString("source_url","")).getHost();if(host!=null){TextView source=UiKit.text(this,host,12,UiKit.MUTED);card.addView(source);UiKit.margin(source,12,0);}
   if(task!=null){String warning=Texts.qualityNotice(this,task.optString("quality_notice"));if(!warning.isEmpty()){TextView notice=UiKit.text(this,warning,12,UiKit.MUTED);card.addView(notice);UiKit.margin(notice,10,0);}}
   if(pending){ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgress(record!=null&&record.size>0?(int)Math.min(100,record.offset()*100/record.size):(int)Math.round(task==null?0:task.optDouble("progress",0)*100));card.addView(progress,new LinearLayout.LayoutParams(-1,UiKit.dp(this,5)));UiKit.margin(progress,14,4);}
   label.setText(details);label.setTextIsSelectable(true);label.setVisibility(android.view.View.GONE);card.addView(label);UiKit.margin(label,12,4);
   menu.getMenu().add(Texts.text(this,"Detalles")).setOnMenuItemClickListener(item->{label.setVisibility(label.getVisibility()==android.view.View.GONE?android.view.View.VISIBLE:android.view.View.GONE);return true;});
   if(downloaded)addButton(Texts.text(LocalLibraryActivity.this,"Reproducir archivo local"),()->open(id));
   if(pending||row.optBoolean("server_available"))addButton(Texts.text(LocalLibraryActivity.this,"Poner siguiente en la cola"),()->action("/resources/"+id+"/priority",true));
   if(task!=null && Arrays.asList("queued","running","paused").contains(taskState))addButton(Texts.text(LocalLibraryActivity.this,"Cancelar descarga del servidor"),()->action("/tasks/"+task.optString("id")+"/cancel",false));
   if(task!=null && Arrays.asList("failed","cancelled").contains(taskState))addButton(Texts.text(LocalLibraryActivity.this,"Reintentar descarga del servidor"),()->action("/tasks/"+task.optString("id")+"/retry",false));
   if(localState.equals("failed"))addButton(Texts.text(LocalLibraryActivity.this,"Reintentar descarga del teléfono…"),()->launch("NetworkPolicyActivity",id));
   if(!downloaded)addButton(Texts.text(LocalLibraryActivity.this,"Volver a solicitar / Descargar"),()->launch("ResubmitActivity",id));
   addButton(Texts.text(LocalLibraryActivity.this,"Borrar archivos…"),()->launch("ResourceDeletionActivity",id));
  }
  if(matches==0){LinearLayout empty=UiKit.card(this);empty.setGravity(android.view.Gravity.CENTER);UiKit.Cover art=new UiKit.Cover(this,"empty","video");empty.addView(art,new LinearLayout.LayoutParams(UiKit.dp(this,112),UiKit.dp(this,112)));TextView headline=UiKit.heading(this,Texts.text(this,rows.isEmpty()?"Tu biblioteca empieza aquí":"Sin resultados"));headline.setGravity(android.view.Gravity.CENTER);empty.addView(headline);UiKit.margin(headline,18,8);TextView description=UiKit.text(this,Texts.text(this,rows.isEmpty()?"Agrega un enlace y lleva tus ideas favoritas contigo.":"Prueba otra búsqueda o filtro."),14,UiKit.MUTED);description.setGravity(android.view.Gravity.CENTER);empty.addView(description);list.addView(empty,new LinearLayout.LayoutParams(-1,-2));}
  actionHost=null;cardMenu=null;
  more.setEnabled(matches>visibleLimit||!nextCursor.isEmpty());more.setVisibility(more.isEnabled()?android.view.View.VISIBLE:android.view.View.GONE);
 }
 private void addButton(String title,Runnable run){
  boolean primary=title.equals(Texts.text(this,"Reproducir archivo local"))||title.equals(Texts.text(this,"Volver a solicitar / Descargar"));
  if(!primary&&cardMenu!=null){cardMenu.getMenu().add(title).setOnMenuItemClickListener(item->{run.run();return true;});return;}
  Button button=new Button(this);button.setText(title);UiKit.button(button,primary);button.setOnClickListener(v->run.run());(actionHost==null?list:actionHost).addView(button,new LinearLayout.LayoutParams(-1,-2));UiKit.margin(button,14,0);
 }
 private void launch(String name,String id){try{startActivity(new Intent().setClassName(this,getPackageName()+"."+name).putExtra("server_url",origin).putExtra("resource_id",id));}catch(ActivityNotFoundException e){status.setText(Texts.text(LocalLibraryActivity.this,"Esta acción todavía no está disponible en esta versión."));}}
 private void action(String path,boolean priority){status.setText(Texts.text(LocalLibraryActivity.this,"Guardando acción…"));disk.execute(()->{try{
  String[] parts=path.split("/");String kind=priority?"resource_priority":"task_"+parts[3];JSONObject payload=new JSONObject().put(priority?"resource_id":"task_id",parts[2]);
  synchronized(SessionStore.class){if(!sessionCurrent())throw new Exception();CommandDispatch.enqueue(this,origin,kind,payload);}
  runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.text(LocalLibraryActivity.this,"Acción guardada. Se enviará con cualquier conexión; consulta Acciones pendientes y errores."));});CommandDispatch.flush(this,origin);
 }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.text(LocalLibraryActivity.this,"No se pudo guardar la acción. Revisa la sesión y el espacio del teléfono."));});}});}

 private void open(String id){status.setText(Texts.text(LocalLibraryActivity.this,"Verificando archivo local…"));disk.execute(()->{
  try{String openingToken=new SessionStore(this,origin).token();Uri uri=PrivateMediaContentProvider.create(this,origin,id);if(!openingToken.equals(new SessionStore(this,origin).token()))throw new SecurityException(Texts.text(LocalLibraryActivity.this,"La sesión cambió"));String mime=getContentResolver().getType(uri);
   runOnUiThread(()->{if(isDestroyed())return;Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);view.setClipData(ClipData.newRawUri("Tubego",uri));
    List<ResolveInfo> players=getPackageManager().queryIntentActivities(view,0);players.sort(Comparator.comparing(p->!"org.videolan.vlc".equals(p.activityInfo.packageName)));
    if(players.isEmpty()){new AlertDialog.Builder(this).setMessage(Texts.text(LocalLibraryActivity.this,"No hay un reproductor compatible. Instala VLC u otro reproductor de archivos locales.")).setPositiveButton(Texts.text(LocalLibraryActivity.this,"Aceptar"),null).show();return;}
    String[] names=new String[players.size()];for(int i=0;i<names.length;i++)names[i]=players.get(i).loadLabel(getPackageManager()).toString();
    new AlertDialog.Builder(this).setTitle(Texts.text(LocalLibraryActivity.this,"Reproducir archivo local")).setItems(names,(dialog,which)->{ResolveInfo player=players.get(which);Intent explicit=new Intent(view).setComponent(new ComponentName(player.activityInfo.packageName,player.activityInfo.name));
     try{startActivity(explicit);disk.execute(()->{try{HistoryOpened.record(this,origin,id,openingToken);}catch(Exception ignored){}});status.setText(Texts.text(LocalLibraryActivity.this,"PiP y audio con pantalla bloqueada dependen del reproductor."));}catch(ActivityNotFoundException e){status.setText(Texts.text(LocalLibraryActivity.this,"El reproductor ya no está disponible."));}
    }).show();
   });
  }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.text(LocalLibraryActivity.this,"No se pudo abrir el archivo local: ")+Texts.error(this,e));});}
 });}
 @Override protected void onResume(){super.onResume();if(origin!=null&&token!=null)disk.execute(()->{if(!sessionCurrent())runOnUiThread(()->{if(!isDestroyed()){rows.clear();list.removeAllViews();status.setText(Texts.text(LocalLibraryActivity.this,"Sesión no disponible."));}});});}
 @Override protected void onDestroy(){disk.shutdownNow();super.onDestroy();}
}
