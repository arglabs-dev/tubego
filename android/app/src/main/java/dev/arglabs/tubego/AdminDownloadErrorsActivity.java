package dev.arglabs.tubego;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Sanitized diagnostic codes only; no extractor output, tokens or file paths. */
public final class AdminDownloadErrorsActivity extends LocalizedActivity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private LinearLayout layout;private TextView status;private String origin;private long cursor;
    @Override public void onCreate(Bundle state){super.onCreate(state);origin=getIntent().getStringExtra("server_url");layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(32,64,32,32);status=new TextView(this);layout.addView(status);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);load();}
    private void load(){status.setText(Texts.text(AdminDownloadErrorsActivity.this,"Consultando diagnósticos…"));network.execute(()->{try{
        JSONObject response=new ApiClient(origin).request("GET","/admin/download-errors?after="+cursor,null,new SessionStore(this,origin).token());
        runOnUiThread(()->{if(isDestroyed())return;layout.removeAllViews();layout.addView(status);status.setText(Texts.text(AdminDownloadErrorsActivity.this,"Fallos definitivos. Los errores temporales se reintentan hasta tres veces."));
            var items=response.optJSONArray("items");for(int i=0;items!=null&&i<items.length();i++){JSONObject item=items.optJSONObject(i),diagnostic=item.optJSONObject("diagnostic");TextView text=new TextView(this);text.setText(item.optString("created_at")+" · "+item.optString("task_id")+"\n"+Texts.failure(this,diagnostic.optString("error_code"))+Texts.text(AdminDownloadErrorsActivity.this," · intentos: ")+diagnostic.optInt("attempts"));layout.addView(text);}
            if(!response.isNull("next_cursor")){Button next=new Button(this);next.setText(Texts.text(AdminDownloadErrorsActivity.this,"Más diagnósticos"));layout.addView(next);next.setOnClickListener(v->{cursor=response.optLong("next_cursor");load();});}
        });
    }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText(Texts.text(AdminDownloadErrorsActivity.this,"Se requiere una sesión activa de administrador para consultar diagnósticos."));});}});}
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
