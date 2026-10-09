package dev.arglabs.tubego;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.widget.*;
public final class ShareReceiverActivity extends Activity {
 @Override public void onCreate(Bundle state){super.onCreate(state);
  try{
   if(!Intent.ACTION_SEND.equals(getIntent().getAction())||!"text/plain".equals(getIntent().getType()))throw new IllegalArgumentException("Comparte un solo enlace como texto, no archivos ni lotes.");
   CharSequence text=getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);String url=SharedUrl.parse(text==null?null:text.toString());
   String origin=getSharedPreferences("server_connection",MODE_PRIVATE).getString("server_url","");
   startActivity(new Intent(this,LinkEntryActivity.class).putExtra("server_url",origin).putExtra("shared_url",url));finish();
  }catch(Exception e){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);TextView error=new TextView(this);error.setText(e instanceof IllegalArgumentException?e.getMessage():"No se pudo leer el enlace compartido.");layout.addView(error);Button close=new Button(this);close.setText("Cerrar");close.setOnClickListener(v->finish());layout.addView(close);setContentView(layout);}
 }
}
