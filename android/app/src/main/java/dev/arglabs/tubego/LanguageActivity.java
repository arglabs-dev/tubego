package dev.arglabs.tubego;
import android.os.Bundle;import android.widget.*;import java.util.concurrent.*;
public final class LanguageActivity extends LocalizedActivity {
 private final ExecutorService network=Executors.newSingleThreadExecutor();private String origin;private TextView status;
 @Override public void onCreate(Bundle state){super.onCreate(state);origin=LanguagePreferences.origin(this);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,48,32,32);TextView title=new TextView(this);title.setText(Texts.text(this,"Idioma de tu cuenta"));title.setTextSize(24);layout.addView(title);
  Spinner language=new Spinner(this);language.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Español","English"}));language.setSelection("es".equals(LanguagePreferences.language(this,origin))?0:1);layout.addView(language);
  status=new TextView(this);layout.addView(status);Button save=new Button(this);save.setText(Texts.text(this,"Guardar idioma"));layout.addView(save);Button retry=new Button(this);retry.setText(Texts.text(this,"Enviar cambio pendiente"));layout.addView(retry);
  TextView details=new TextView(this);details.setText(Texts.text(this,"El idioma se guarda para tu cuenta aunque no haya conexión. Se sincroniza con tus otros dispositivos cuando vuelve la red, sin usar datos para descargar videos."));layout.addView(details);setContentView(layout);
  try{status.setText(Texts.text(this,LanguagePreferences.pending(this,origin)==null?"Idioma de tu cuenta":"Idioma guardado en este teléfono; pendiente de sincronizar."));}catch(Exception e){status.setText(Texts.text(this,"Inicia sesión con una cuenta aprobada para guardar tu idioma."));}
  save.setOnClickListener(v->{try{LanguagePreferences.saveLocal(this,origin,language.getSelectedItemPosition()==0?"es":"en");recreate();}catch(Exception e){status.setText(Texts.text(this,"No se pudo guardar el idioma. Revisa tu sesión y almacenamiento."));}});
  retry.setOnClickListener(v->{try{boolean scheduled=LanguagePreferences.dispatchPending(this,origin);status.setText(Texts.text(this,scheduled?"Cambio pendiente preparado para enviar cuando haya conexión.":"Idioma guardado en este teléfono; pendiente de sincronizar."));}catch(Exception e){status.setText(Texts.text(this,"No se pudo preparar el cambio. Tu idioma sigue guardado localmente."));}});
  network.execute(()->{try{String before=LanguagePreferences.language(this,origin);LanguagePreferences.refresh(this,origin);runOnUiThread(()->{if(isDestroyed())return;if(!before.equals(LanguagePreferences.language(this,origin)))recreate();});}catch(Exception ignored){/* Local language remains usable offline. */}});
 }
 @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
