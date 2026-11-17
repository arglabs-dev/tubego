package dev.arglabs.tubego;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class MaintenanceFeedbackInstrumentedTest extends InstrumentationTestCase {
    public void testOfflineScreenDistinguishesUnsentRequestTargetAndObservedVersion() throws Exception {
        Context context=getInstrumentation().getTargetContext();
        String origin="https://maintenance-feedback.example.invalid",user=UUID.randomUUID().toString(),device=UUID.randomUUID().toString();
        JSONObject session=new JSONObject().put("token","feedback-fixture").put("status","approved").put("role","admin").put("user_id",user).put("device_id",device);
        SessionStore store=new SessionStore(context,origin);store.save(session);LanguagePreferences.saveLocal(context,origin,"es");
        JSONObject installed=new JSONObject().put("yt_dlp","2026.8.19").put("tubego","0.1.0").put("backend_revision","a".repeat(40));
        JSONObject job=new JSONObject().put("id",UUID.randomUUID().toString()).put("action","update_ytdlp").put("status","succeeded").put("phase","finished")
            .put("created_at","2026-11-07T08:00:00+00:00").put("updated_at","2026-11-07T08:01:00+00:00").put("target",new JSONObject().put("version","2026.8.19"))
            .put("result",new JSONObject().put("installed",installed));
        MaintenanceCommands.cache(context,origin,session,new JSONObject().put("installed",installed).put("capabilities",new JSONObject().put("mutation_enabled",false))
            .put("versions",new JSONObject().put("available",new JSONObject().put("yt_dlp","2026.10.26").put("backend_revision","b".repeat(40))))
            .put("jobs",new JSONArray().put(job)));
        java.io.File root=LocalLibraryStorage.root(context,origin,user,device);
        MaintenanceCommands.write(root,"maintenance-pending.json",new JSONObject().put("action","check_versions").put("request_id",UUID.randomUUID().toString()).put("created_at","2026-11-08T08:00:00Z"));
        Activity screen=null;
        try {
            screen=getInstrumentation().startActivitySync(new Intent(context,MaintenanceActivity.class).putExtra("server_url",origin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            getInstrumentation().waitForIdleSync();View view=screen.getWindow().getDecorView();
            for(String text:new String[]{"Instalado en el servidor","Disponible en Internet","2026.10.26","Destino solicitado: 2026.8.19","Aceptada por el servidor:","Último cambio en el servidor:"})assertTrue(text,contains(view,text));
            assertTrue(contains(view,"sin confirmación del servidor")||contains(view,"Envío sin confirmar")||contains(view,"Enviando; esperando confirmación"));
            assertNotNull(MaintenanceCommands.pending(context,origin));
            android.graphics.Bitmap capture=getInstrumentation().getUiAutomation().takeScreenshot();
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(context.getCacheDir(),"maintenance-feedback.png"))){capture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{capture.recycle();}
        } finally {
            if(screen!=null){Activity current=screen;getInstrumentation().runOnMainSync(current::finish);}
            store.clear();LocalLibraryStorage.wipe(context,origin,user,device);
        }
    }
    public void testTransferLabelsDistinguishQueueRetryAndActualBytes() throws Exception {
        Context context=getInstrumentation().getTargetContext();
        String origin="https://feedback-labels.example.invalid";
        SessionStore store=new SessionStore(context,origin);
        store.save(new JSONObject().put("token","labels-fixture").put("status","approved").put("role","user").put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString()));
        LanguagePreferences.saveLocal(context,origin,"es");
        Activity screen=getInstrumentation().startActivitySync(new Intent(context,OnboardingActivity.class).putExtra("server_url","https://feedback-labels.example.invalid").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            Item item=new Item();item.state=ItemState.SAVING;item.localState="pending";item.sizeBytes=100;item.offsetBytes=25;
            assertTrue(ItemFlows.stateLabel((DsActivity)screen,item).contains("cola"));
            item.localState="retry_wait";assertTrue(ItemFlows.stateLabel((DsActivity)screen,item).contains("reintento"));
            item.localState="downloading";assertTrue(ItemFlows.stateLabel((DsActivity)screen,item).contains("cola"));
            item.transferring=true;assertTrue(ItemFlows.stateLabel((DsActivity)screen,item).contains("25 %"));
            java.util.concurrent.atomic.AtomicReference<Intent> opened=new java.util.concurrent.atomic.AtomicReference<>();
            android.app.Instrumentation.ActivityMonitor monitor=new android.app.Instrumentation.ActivityMonitor(){
                @Override public android.app.Instrumentation.ActivityResult onStartActivity(Intent intent){opened.set(intent);return new android.app.Instrumentation.ActivityResult(Activity.RESULT_OK,null);}
            };
            getInstrumentation().addMonitor(monitor);
            try {
                item.sourceUrl="https://youtu.be/example";
                Host host=new Host(){public DsActivity act(){return (DsActivity)screen;}public String origin(){return "https://feedback-labels.example.invalid";}public void message(String value){}public void refresh(){}public java.util.concurrent.ExecutorService io(){return null;}public DownloadPolicy.NetworkState network(){return null;}};
                getInstrumentation().runOnMainSync(()->ItemFlows.openSource(host,item));
                assertNotNull(opened.get());assertEquals(Intent.ACTION_VIEW,opened.get().getAction());assertEquals(item.sourceUrl,opened.get().getDataString());assertTrue(opened.get().hasCategory(Intent.CATEGORY_BROWSABLE));
            } finally {getInstrumentation().removeMonitor(monitor);}
            assertFalse(ItemFlows.validSource("javascript:alert(1)"));assertFalse(ItemFlows.validSource("file:///data/local/file"));assertTrue(ItemFlows.validSource("https://youtu.be/example"));
        } finally {getInstrumentation().runOnMainSync(screen::finish);store.clear();}
    }
    public void testRetryJournalRecordsDispatchAndAcknowledgementWithoutChangingRequestId() throws Exception {
        Context c=getInstrumentation().getTargetContext();String origin="https://journal.example.invalid";
        JSONObject session=new JSONObject().put("token","journal-secret-token").put("status","approved").put("role","admin").put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString());
        SessionStore store=new SessionStore(c,origin);store.save(session);
        java.io.File root=LocalLibraryStorage.root(c,origin,session.getString("user_id"),session.getString("device_id"));
        String id=UUID.randomUUID().toString();
        MaintenanceCommands.write(root,"maintenance-pending.json",new JSONObject().put("request_id",id).put("action","update_ytdlp").put("confirmed",true).put("version","2026.10.26").put("created_at","2026-11-10T10:00:00Z"));
        try {
            try{MaintenanceCommands.flush(c,origin,(payload,token)->{
                assertEquals(id,payload.getString("request_id"));assertEquals(4,payload.length());
                JSONObject during=MaintenanceCommands.journal(c,origin).getJSONObject(0);
                assertEquals("sending",during.getString("transport_status"));assertTrue(during.has("sent_at"));
                throw new java.net.SocketTimeoutException("response lost");
            });fail("Expected timeout");}catch(java.net.SocketTimeoutException expected){}
            JSONObject first=MaintenanceCommands.journal(c,origin).getJSONObject(0);
            assertEquals("unconfirmed",first.getString("transport_status"));assertEquals(1,first.getInt("attempts"));
            String sent=first.getString("sent_at");
            assertTrue(MaintenanceCommands.flush(c,origin,(payload,token)->{
                assertEquals(id,payload.getString("request_id"));assertFalse(payload.has("sent_at"));assertFalse(payload.has("transport_status"));
                return new JSONObject().put("id",id).put("status","queued");
            }));
            JSONArray entries=MaintenanceCommands.journal(c,origin);assertEquals(1,entries.length());
            JSONObject accepted=entries.getJSONObject(0);assertEquals("accepted",accepted.getString("transport_status"));assertEquals(2,accepted.getInt("attempts"));assertEquals(sent,accepted.getString("sent_at"));assertTrue(accepted.has("received_at"));assertNull(MaintenanceCommands.pending(c,origin));assertFalse(entries.toString().contains("journal-secret-token"));
            JSONObject finished=new JSONObject().put("id",id).put("status","succeeded");
            MaintenanceCommands.cache(c,origin,session,new JSONObject().put("jobs",new JSONArray().put(finished)));
            assertEquals("succeeded",MaintenanceCommands.journal(c,origin).getJSONObject(0).getJSONObject("server_job").getString("status"));
        } finally {store.clear();LocalLibraryStorage.wipe(c,origin,session.getString("user_id"),session.getString("device_id"));}
    }
    private boolean contains(View view,String text){
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++)if(contains(group.getChildAt(n),text))return true;}
        return false;
    }
}
