package dev.arglabs.tubego;

import android.app.Activity;
import android.content.*;
import android.test.InstrumentationTestCase;
import android.widget.Button;
import android.widget.SeekBar;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

/** Opt-in native engine tests. Hard guard prevents synthetic sessions in productive TubeGo. */
@SuppressWarnings("deprecation")
public abstract class PlaybackFixture extends InstrumentationTestCase {
    private static Activity active;
    protected Context app;
    protected final String origin="https://playback-test.example.invalid", user="11111111-1111-4111-8111-111111111111",device="22222222-2222-4222-8222-222222222222",id="33333333-3333-4333-8333-333333333333",token="isolated-playback-session";
    protected File root;
    @Override protected void setUp() throws Exception {
        super.setUp();app=getInstrumentation().getTargetContext();
        assertEquals("Build with -PplaybackHarness; never run against production", "dev.arglabs.tubego.playbacktest",app.getPackageName());
        new SessionStore(app,origin).save(new JSONObject().put("token",token).put("status","approved").put("user_id",user).put("device_id",device));
        root=LocalLibraryStorage.root(app,origin,user,device);root.mkdirs();
        Context test=getInstrumentation().getContext();int raw=test.getResources().getIdentifier("vlc_sample","raw",test.getPackageName());assertTrue(raw!=0);
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream input=test.getResources().openRawResource(raw)){byte[] block=new byte[8192];for(int n;(n=input.read(block))!=-1;)out.write(block,0,n);}
        byte[] bytes=out.toByteArray();StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(Locale.ROOT,"%02x",b&255));
        TransferRecord record=new TransferRecord(root,id,hash.toString(),bytes.length,"Synthetic playback test","");record.state="complete";record.mediaFormat="video";Files.write(record.media().toPath(),bytes);record.save();
    }
    protected Activity open() throws Exception {if(active!=null&&!active.isDestroyed())close(active);active=getInstrumentation().startActivitySync(new Intent(app,PlayerActivity.class).putExtra("server_url",origin).putExtra("resource_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return active;}
    protected void close(Activity activity) throws Exception {getInstrumentation().runOnMainSync(activity::finish);getInstrumentation().waitForIdleSync();long until=System.currentTimeMillis()+4000;while(!activity.isDestroyed()&&System.currentTimeMillis()<until)Thread.sleep(50);assertTrue("Previous playback Activity did not finish",activity.isDestroyed());}
    @Override protected void tearDown() throws Exception {try{if(active!=null&&!active.isDestroyed())close(active);}finally{super.tearDown();}}
    protected JSONObject saved() throws Exception {return new PlaybackStore(root).read(id);}
    protected void reset() throws Exception {if(active!=null&&!active.isDestroyed())close(active);new PlaybackStore(root).save(id,PlaybackState.empty(),true);}
    protected JSONObject waitForPosition(long minimum,long timeout) throws Exception {
        long until=System.currentTimeMillis()+timeout;JSONObject latest;
        do{Thread.sleep(250);latest=saved();if(latest.optLong("position_ms")>=minimum)return latest;}while(System.currentTimeMillis()<until);
        fail("No durable native checkpoint at "+minimum+": "+latest);return latest;
    }
}
