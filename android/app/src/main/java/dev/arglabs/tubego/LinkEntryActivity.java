package dev.arglabs.tubego;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Manual and external sharing use the same validation/quality/durable outbox path. */
public final class LinkEntryActivity extends LocalizedActivity {
 private final ExecutorService network=Executors.newSingleThreadExecutor();
 private final ExecutorService storage=Executors.newSingleThreadExecutor();
 private volatile boolean qualityOverride;
 private String origin;private JSONObject session,prefs;private TextView status,history;private LinearLayout resourceCards;private Button submit,analyze;private EditText input;private Spinner quality;
 @Override public void onCreate(Bundle state){super.onCreate(state);
  origin=getIntent().getStringExtra("server_url");
  if(origin==null)origin=getSharedPreferences("server_connection",MODE_PRIVATE).getString("server_url","");
  LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);
  TextView title=new TextView(this);title.setText(Texts.text(LinkEntryActivity.this,"Agregar enlace"));title.setTextSize(29);layout.addView(title);
  TextView description=UiKit.text(this,Texts.text(this,"De un enlace a tu biblioteca. Así de simple."),15,UiKit.MUTED);layout.addView(description);
  LinearLayout form=UiKit.card(this);layout.addView(form,new LinearLayout.LayoutParams(-1,-2));UiKit.margin(form,18,14);
  form.addView(UiKit.text(this,Texts.text(this,"ENLACE"),11,UiKit.MUTED));
  input=new EditText(this);input.setHint(Texts.text(LinkEntryActivity.this,"Pega un enlace HTTP o HTTPS"));input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);input.setSingleLine(true);form.addView(input,new LinearLayout.LayoutParams(-1,-2));
  quality=new Spinner(this);quality.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,Texts.labels(this,MediaSelection.LABELS)));quality.setSelection(MediaSelection.index("720"));quality.setOnTouchListener((v,event)->{qualityOverride=true;return false;});form.addView(quality,new LinearLayout.LayoutParams(-1,-2));
  analyze=new Button(this);analyze.setText(Texts.text(LinkEntryActivity.this,"Consultar título y duración (con conexión)"));form.addView(analyze,new LinearLayout.LayoutParams(-1,-2));
  submit=new Button(this);submit.setText(Texts.text(LinkEntryActivity.this,"Agregar a la cola"));UiKit.button(submit,true);form.addView(submit,new LinearLayout.LayoutParams(-1,-2));UiKit.margin(submit,10,0);
  form.removeView(analyze);form.addView(analyze,new LinearLayout.LayoutParams(-1,-2));
  UiKit.section(layout,"TU COLA");
  Button refresh=new Button(this);refresh.setText(Texts.text(LinkEntryActivity.this,"Actualizar pendientes e intentar envío"));layout.addView(refresh);
  Button preferences=new Button(this);preferences.setText(Texts.text(LinkEntryActivity.this,"Preferencias de calidad"));layout.addView(preferences);
  Button library=new Button(this);library.setText(Texts.text(LinkEntryActivity.this,"Ver fichas existentes / Historial"));layout.addView(library);
  library.setOnClickListener(v->startActivity(new Intent(this,ResubmitActivity.class).putExtra("server_url",origin)));
  Button account=new Button(this);account.setText(Texts.text(LinkEntryActivity.this,"Mi cuenta / Estado de aprobación"));layout.addView(account);
  account.setOnClickListener(v->{try{new ApiClient(origin);startActivity(new Intent(this,LoginActivity.class).putExtra("server_url",origin));finish();}catch(Exception e){startActivity(new Intent(this,MainActivity.class));finish();}});
  status=new TextView(this);layout.addView(status);history=new TextView(this);layout.addView(history);resourceCards=new LinearLayout(this);resourceCards.setOrientation(LinearLayout.VERTICAL);layout.addView(resourceCards);
  ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
  try{
   origin=new ApiClient(origin).getBaseUrl();session=new SessionStore(this,origin).read();
   if(session==null||!"approved".equals(session.optString("status")))throw new Exception();
   prefs=new LocalMediaPreferences(LinkOutboxDispatch.root(this,origin,session)).read();quality.setSelection(MediaSelection.index(prefs.optString("selection","720")));
  }catch(Exception e){submit.setEnabled(false);analyze.setEnabled(false);quality.setEnabled(false);refresh.setEnabled(false);preferences.setEnabled(false);library.setEnabled(false);
   String accountState=session==null?Texts.text(LinkEntryActivity.this,"Sin sesión"):session.optString("status",Texts.text(LinkEntryActivity.this,"Sin sesión"));
   status.setText(accountState.equals("pending_verification")?Texts.text(LinkEntryActivity.this,"Verifica tu correo antes de utilizar el servicio."):accountState.equals("pending_approval")?Texts.text(LinkEntryActivity.this,"Tu cuenta espera aprobación del administrador."):Texts.text(LinkEntryActivity.this,"Configura el servidor e inicia sesión con una cuenta aprobada antes de agregar enlaces."));return;}
  String shared=getIntent().getStringExtra("shared_url");
  if(shared!=null)try{input.setText(SharedUrl.parse(shared));}catch(Exception e){status.setText(Texts.error(this,e));}
  submit.setOnClickListener(v->{final String url;try{url=SharedUrl.parse(input.getText().toString());}catch(Exception e){status.setText(Texts.error(this,e));return;}
   String selected=MediaSelection.VALUES[quality.getSelectedItemPosition()];
   if(prefs.optBoolean("ask_every_time",true))QualityPicker.show(this,selected,value->confirm(url,value));else confirm(url,selected);
  });
  analyze.setOnClickListener(v->analyze());refresh.setOnClickListener(v->{LinkOutboxDispatch.schedule(this,origin);refresh();});
  preferences.setOnClickListener(v->startActivity(new Intent(this,MediaPreferencesActivity.class).putExtra("server_url",origin)));
  status.setText(Texts.text(LinkEntryActivity.this,"Puedes agregar enlaces sin conexión; el envío usará cualquier red disponible."));refresh();syncPreferences();
 }
 private void confirm(String url,String selection){new AlertDialog.Builder(this).setMessage(Texts.text(LinkEntryActivity.this,"Agregar este enlace con ")+Texts.labels(this,MediaSelection.LABELS)[MediaSelection.index(selection)]+Texts.text(LinkEntryActivity.this,"? Se enviará cuando haya conexión.")).setNegativeButton(Texts.text(LinkEntryActivity.this,"Cancelar"),null).setPositiveButton(Texts.text(LinkEntryActivity.this,"Agregar"),(d,w)->save(url,selection)).show();}
 private void save(String url,String selection){submit.setEnabled(false);storage.execute(()->{
  try{synchronized(SessionStore.class){JSONObject current=new SessionStore(this,origin).read();if(current==null||!current.getString("token").equals(session.getString("token")))throw new Exception();
   LinkOutbox.Entry entry=LinkOutboxDispatch.box(this,origin,session).add(url,selection);LinkOutboxDispatch.schedule(this,origin);show(Texts.text(LinkEntryActivity.this,"Enlace guardado · pendiente de envío: ")+entry.id);}
  }catch(Exception e){show(Texts.text(LinkEntryActivity.this,"No se pudo guardar. Revisa sesión y almacenamiento."));}
  runOnUiThread(()->{if(!isDestroyed()){submit.setEnabled(true);refresh();}});
 });}
 private void refresh(){storage.execute(()->{try{
  java.util.List<LinkOutbox.Entry> entries=LinkOutboxDispatch.box(this,origin,session).entries();StringBuilder rows=new StringBuilder();
  for(LinkOutbox.Entry entry:entries)rows.append(entry.state.equals("submitted")?Texts.text(LinkEntryActivity.this,"Enviado · consulta su ficha existente"):entry.state.equals("error")?Texts.text(LinkEntryActivity.this,"Error"):Texts.text(LinkEntryActivity.this,"Pendiente de envío")).append(" · ").append(Texts.quality(this,entry.selection)).append("\n").append(entry.url).append(entry.error.isEmpty()?"":"\n"+Texts.failure(this,entry.error)).append("\n\n");
  runOnUiThread(()->{try{if(isDestroyed()||!session.getString("token").equals(new SessionStore(this,origin).token()))return;
   history.setText(rows.length()==0?Texts.text(LinkEntryActivity.this,"Sin enlaces enviados desde este dispositivo"):rows.toString());resourceCards.removeAllViews();java.util.Set<String> shown=new java.util.HashSet<>();
   for(LinkOutbox.Entry entry:entries)if(!entry.resourceId.isEmpty()&&shown.add(entry.resourceId)){Button card=new Button(this);card.setText(Texts.text(LinkEntryActivity.this,"Ver ficha · ")+Texts.quality(this,entry.selection));resourceCards.addView(card);card.setOnClickListener(v->startActivity(new Intent(this,ResubmitActivity.class).putExtra("server_url",origin).putExtra("resource_id",entry.resourceId)));}
  }catch(Exception ignored){resourceCards.removeAllViews();history.setText(Texts.text(LinkEntryActivity.this,"Inicia sesión para ver los enlaces de esta cuenta."));}});
 }catch(Exception e){show(Texts.text(LinkEntryActivity.this,"No se pudo leer la cola local."));}});}

 private void syncPreferences(){network.execute(()->{try{
  java.io.File root;CommandQueue commands;long expectedSequence;
  synchronized(SessionStore.class){JSONObject current=new SessionStore(this,origin).read();if(current==null||!session.getString("token").equals(current.getString("token")))return;root=LinkOutboxDispatch.root(this,origin,session);commands=new CommandQueue(root);expectedSequence=commands.latestSequence("preferences");}
  JSONObject values=new ApiClient(origin).request("GET","/account/preferences",null,session.getString("token"));
  synchronized(SessionStore.class){JSONObject current=new SessionStore(this,origin).read();if(current==null||!session.getString("token").equals(current.getString("token")))return;
   if(!new LocalMediaPreferences(root).saveServerSnapshotIfCurrent(commands,expectedSequence,values))return;prefs=values;}
  runOnUiThread(()->{if(!isDestroyed()&&!qualityOverride)quality.setSelection(MediaSelection.index(values.optString("selection","720")));});
 }catch(Exception ignored){/* offline cached selection stays usable */}});}

 private void analyze(){final String url;try{url=SharedUrl.parse(input.getText().toString());}catch(Exception e){status.setText(Texts.error(this,e));return;}analyze.setEnabled(false);status.setText(Texts.text(LinkEntryActivity.this,"Consultando metadatos…"));network.execute(()->{
  try{MediaInfoClient.Info info=new MediaInfoClient(new ApiClient(origin)::request).analyze(url,session.getString("token"));show((info.title==null?Texts.text(LinkEntryActivity.this,"Sin título disponible"):info.title)+(info.durationSeconds==null?"":Texts.text(LinkEntryActivity.this," · duración ")+Math.round(info.durationSeconds)+Texts.text(LinkEntryActivity.this," segundos")));}
  catch(Exception e){show(Texts.error(this,e)+"\n"+Texts.text(LinkEntryActivity.this,"No se pudieron consultar metadatos. Puedes guardar el enlace para validarlo cuando vuelva la conexión."));}
  runOnUiThread(()->{if(!isDestroyed())analyze.setEnabled(true);});
 });}
 private void show(String text){runOnUiThread(()->{if(!isDestroyed())status.setText(text);});}
 @Override protected void onDestroy(){network.shutdownNow();storage.shutdownNow();super.onDestroy();}
}
