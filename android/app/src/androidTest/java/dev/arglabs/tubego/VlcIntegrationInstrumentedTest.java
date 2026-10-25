package dev.arglabs.tubego;
import android.test.InstrumentationTestCase;
import android.content.*;import android.net.Uri;
import org.json.JSONObject;
import java.io.*;import java.nio.file.Files;import java.security.MessageDigest;
/** Optional explicit integration test. Requires the official VLC APK on the emulator. */
@SuppressWarnings("deprecation")
public final class VlcIntegrationInstrumentedTest extends InstrumentationTestCase {
 public void testVlcReadsVerifiedLocalClipWithoutBackend() throws Exception { runVlc(true); }
 public void testVlcVideoAudioWithScreenOff() throws Exception { runVlc(false); }
 private void runVlc(boolean pip) throws Exception {
  Context app=getInstrumentation().getTargetContext();Context test=getInstrumentation().getContext();
  String origin="https://vlc-offline.example.invalid",user="11111111-1111-4111-8111-111111111111",device="22222222-2222-4222-8222-222222222222",id="33333333-3333-4333-8333-333333333333";
  SessionStore store=new SessionStore(app,origin);File root=LocalLibraryStorage.root(app,origin,user,device);
  app.getPackageManager().getPackageInfo("org.videolan.vlc",0);
  try {
   store.save(new JSONObject().put("token","vlc-test-only-session").put("status","approved").put("user_id",user).put("device_id",device));
   root.mkdirs();int raw=test.getResources().getIdentifier("vlc_sample","raw",test.getPackageName());assertTrue(raw!=0);
   byte[] bytes;try(InputStream input=test.getResources().openRawResource(raw)){bytes=read(input);}
   StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
   TransferRecord record=new TransferRecord(root,id,hash.toString(),bytes.length,"Tubego offline VLC test","");record.state="complete";record.mediaFormat="video";Files.write(record.media().toPath(),bytes);record.save();
   Uri uri=PrivateMediaContentProvider.create(app,origin,id);
   Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/mp4").setPackage("org.videolan.vlc").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
   intent.setClipData(ClipData.newRawUri("Local Tubego file",uri));app.startActivity(intent);
   boolean playing=false;long until=System.currentTimeMillis()+15000;
   while(System.currentTimeMillis()<until){if(playing()){playing=true;break;}Thread.sleep(250);}
   assertTrue("VLC did not enter playback of the granted local clip",playing);
   Thread.sleep(2000);shell("input keyevent 3");Thread.sleep(2000);
   until=System.currentTimeMillis()+10000;while(!playing() && System.currentTimeMillis()<until)Thread.sleep(250);
   assertTrue("VLC stopped on leaving its activity; set its Background/PiP preference",playing());
   if(pip)assertTrue("VLC did not enter Android PiP",shell("dumpsys activity activities").contains("mode=pinned"));
   else {shell("input keyevent 223");Thread.sleep(2000);assertTrue("VLC audio stopped with screen off; choose Play videos in background",playing());}
  } finally {shell("input keyevent 224");shell("am force-stop org.videolan.vlc");store.clear();LocalLibraryStorage.wipe(app,origin,user,device);}
 }
 private android.media.session.MediaController observed;
 private boolean playing() {
  getInstrumentation().getUiAutomation().adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
  try {for(android.media.session.MediaController c:getInstrumentation().getTargetContext().getSystemService(android.media.session.MediaSessionManager.class).getActiveSessions(null)) {
   if("org.videolan.vlc".equals(c.getPackageName()))observed=c;
  }android.media.session.PlaybackState state=observed==null?null:observed.getPlaybackState();return state!=null && state.getState()==android.media.session.PlaybackState.STATE_PLAYING;}finally{getInstrumentation().getUiAutomation().dropShellPermissionIdentity();}
 }
 private byte[] read(InputStream input)throws IOException{ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))!=-1;)output.write(buffer,0,n);return output.toByteArray();}
 private String shell(String command)throws Exception{try(var fd=getInstrumentation().getUiAutomation().executeShellCommand(command);var in=new FileInputStream(fd.getFileDescriptor())){return new String(read(in),java.nio.charset.StandardCharsets.UTF_8);}}
}
