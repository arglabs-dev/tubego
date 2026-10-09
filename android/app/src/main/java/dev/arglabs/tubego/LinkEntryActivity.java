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
public final class LinkEntryActivity extends Activity {
 private final ExecutorService network=Executors.newSingleThreadExecutor();
 private final ExecutorService storage=Executors.newSingleThreadExecutor();
 private volatile boolean qualityOverride;
 private String origin;private JSONObject session,prefs;private TextView status,history;private Button submit,analyze;private EditText input;private Spinner quality;
 @Override public void onCreate(Bundle state){super.onCreate(state);
  origin=getIntent().getStringExtra("server_url");
  if(origin==null)origin=getSharedPreferences("server_connection",MODE_PRIVATE).getString("server_url","");
  LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);
  TextView title=new TextView(this);title.setText("Agregar enlace · Tubego");title.setTextSize(24);layout.addView(title);
  input=new EditText(this);input.setHint("Pega un enlace HTTP o HTTPS");input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);layout.addView(input);
  quality=new Spinner(this);quality.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,MediaSelection.LABELS));quality.setSelection(MediaSelection.index("720"));quality.setOnTouchListener((v,event)->{qualityOverride=true;return false;});layout.addView(quality);
  analyze=new Button(this);analyze.setText("Consultar título y duración (con conexión)");layout.addView(analyze);
  submit=new Button(this);submit.setText("Agregar a la cola");layout.addView(submit);
  Button refresh=new Button(this);refresh.setText("Actualizar pendientes e intentar envío");layout.addView(refresh);
  Button preferences=new Button(this);preferences.setText("Preferencias de calidad");layout.addView(preferences);
  Button account=new Button(this);account.setText("Mi cuenta / Estado de aprobación");layout.addView(account);
  account.setOnClickListener(v->{try{new ApiClient(origin);startActivity(new Intent(this,LoginActivity.class).putExtra("server_url",origin));finish();}catch(Exception e){startActivity(new Intent(this,MainActivity.class));finish();}});
  status=new TextView(this);layout.addView(status);history=new TextView(this);layout.addView(history);
  ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
  try{
   origin=new ApiClient(origin).getBaseUrl();session=new SessionStore(this,origin).read();
   if(session==null||!"approved".equals(session.optString("status")))throw new Exception();
   prefs=new LocalMediaPreferences(LinkOutboxDispatch.root(this,origin,session)).read();quality.setSelection(MediaSelection.index(prefs.optString("selection","720")));
  }catch(Exception e){submit.setEnabled(false);analyze.setEnabled(false);quality.setEnabled(false);refresh.setEnabled(false);preferences.setEnabled(false);
   String accountState=session==null?"Sin sesión":session.optString("status","Sin sesión");
   status.setText(accountState.equals("pending_verification")?"Verifica tu correo antes de utilizar el servicio.":accountState.equals("pending_approval")?"Tu cuenta espera aprobación del administrador.":"Configura el servidor e inicia sesión con una cuenta aprobada antes de agregar enlaces.");return;}
  String shared=getIntent().getStringExtra("shared_url");
  if(shared!=null)try{input.setText(SharedUrl.parse(shared));}catch(Exception e){status.setText(e.getMessage());}
  submit.setOnClickListener(v->{final String url;try{url=SharedUrl.parse(input.getText().toString());}catch(Exception e){status.setText(e.getMessage());return;}
   String selected=MediaSelection.VALUES[quality.getSelectedItemPosition()];
   if(prefs.optBoolean("ask_every_time",true))QualityPicker.show(this,selected,value->confirm(url,value));else confirm(url,selected);
  });
  analyze.setOnClickListener(v->analyze());refresh.setOnClickListener(v->{LinkOutboxDispatch.schedule(this,origin);refresh();});
  preferences.setOnClickListener(v->startActivity(new Intent(this,MediaPreferencesActivity.class).putExtra("server_url",origin)));
  status.setText("Puedes agregar enlaces sin conexión; el envío usará cualquier red disponible.");refresh();syncPreferences();
 }
 private void confirm(String url,String selection){new AlertDialog.Builder(this).setMessage("Agregar este enlace con "+MediaSelection.LABELS[MediaSelection.index(selection)]+"? Se enviará cuando haya conexión.").setNegativeButton("Cancelar",null).setPositiveButton("Agregar",(d,w)->save(url,selection)).show();}
 private void save(String url,String selection){submit.setEnabled(false);storage.execute(()->{
  try{synchronized(SessionStore.class){JSONObject current=new SessionStore(this,origin).read();if(current==null||!current.getString("token").equals(session.getString("token")))throw new Exception();
   LinkOutbox.Entry entry=LinkOutboxDispatch.box(this,origin,session).add(url,selection);LinkOutboxDispatch.schedule(this,origin);show("Enlace guardado · pendiente de envío: "+entry.id);}
  }catch(Exception e){show("No se pudo guardar. Revisa sesión y almacenamiento.");}
  runOnUiThread(()->{if(!isDestroyed()){submit.setEnabled(true);refresh();}});
 });}
 private void refresh(){storage.execute(()->{try{StringBuilder rows=new StringBuilder();for(LinkOutbox.Entry entry:LinkOutboxDispatch.box(this,origin,session).entries())rows.append(entry.state.equals("submitted")?"Enviado":entry.state.equals("error")?"Error":"Pendiente de envío").append(" · ").append(entry.selection).append("\n").append(entry.url).append(entry.error.isEmpty()?"":"\n"+entry.error).append("\n\n");
  runOnUiThread(()->{if(!isDestroyed())history.setText(rows.length()==0?"Sin enlaces enviados desde este dispositivo":rows.toString());});}catch(Exception e){show("No se pudo leer la cola local.");}});}
 private void syncPreferences(){network.execute(()->{try{JSONObject values=new ApiClient(origin).request("GET","/account/preferences",null,session.getString("token"));synchronized(SessionStore.class){JSONObject current=new SessionStore(this,origin).read();if(current==null||!session.getString("token").equals(current.getString("token")))return;new LocalMediaPreferences(LinkOutboxDispatch.root(this,origin,session)).save(values);prefs=values;}runOnUiThread(()->{if(!isDestroyed()&&!qualityOverride)quality.setSelection(MediaSelection.index(values.optString("selection","720")));});}catch(Exception ignored){/* offline cached selection stays usable */}});}
 private void analyze(){final String url;try{url=SharedUrl.parse(input.getText().toString());}catch(Exception e){status.setText(e.getMessage());return;}analyze.setEnabled(false);status.setText("Consultando metadatos…");network.execute(()->{
  try{MediaInfoClient.Info info=new MediaInfoClient(new ApiClient(origin)::request).analyze(url,session.getString("token"));show((info.title==null?"Sin título disponible":info.title)+(info.durationSeconds==null?"":" · duración "+Math.round(info.durationSeconds)+" segundos"));}
  catch(Exception e){show("No se pudieron consultar metadatos. Puedes guardar el enlace para validarlo cuando vuelva la conexión.");}
  runOnUiThread(()->{if(!isDestroyed())analyze.setEnabled(true);});
 });}
 private void show(String text){runOnUiThread(()->{if(!isDestroyed())status.setText(text);});}
 @Override protected void onDestroy(){network.shutdownNow();storage.shutdownNow();super.onDestroy();}
}
