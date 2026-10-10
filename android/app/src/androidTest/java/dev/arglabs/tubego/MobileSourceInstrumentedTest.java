package dev.arglabs.tubego;

import android.test.InstrumentationTestCase;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import org.json.JSONObject;
import javax.net.ssl.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.io.*;

/** Opt-in full public-source APK UI→durable commands→worker→TLS transfer test. */
public final class MobileSourceInstrumentedTest extends InstrumentationTestCase {
 private static final String ORIGIN="https://10.0.2.2:9443";
 private static final String SOURCE="https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4";
 public void testPasteAndConfirmPublicSourceReachesVerifiedOfflineFile()throws Exception {
  Context context=getInstrumentation().getTargetContext();
  SSLSocketFactory original=HttpsURLConnection.getDefaultSSLSocketFactory();
  SSLContext tls=SSLContext.getInstance("TLS");KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null);
  Context test=getInstrumentation().getContext();int id=test.getResources().getIdentifier("fixture_cert","raw",test.getPackageName());assertTrue(id!=0);
  try(InputStream cert=test.getResources().openRawResource(id)){trust.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509").generateCertificate(cert));}
  TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);tls.init(null,tm.getTrustManagers(),null);
  HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());Activity activity=null;
  try {
   shell("input keyevent 224");shell("wm dismiss-keyguard");TransferJobs.stop(context,ORIGIN);
   ApiClient api=new ApiClient(ORIGIN);
   // Approved disposable fixture account: this test does not claim SMTP verification.
   JSONObject session=api.request("POST","/auth/login",new JSONObject().put("email","tubego-test@example.invalid").put("password","MVP test password 2026").put("device_name","Public-source APK E2E"),null);
   new SessionStore(context,ORIGIN).save(session);LanguagePreferences.saveLocal(context,ORIGIN,"es");
   File root=LocalLibraryStorage.root(context,ORIGIN,session.getString("user_id"),session.getString("device_id"));
   final Activity screen=getInstrumentation().startActivitySync(new Intent(context,LinkEntryActivity.class).putExtra("server_url",ORIGIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=screen;getInstrumentation().waitForIdleSync();
   getInstrumentation().runOnMainSync(()->{EditText input=findInput(screen.getWindow().getDecorView());assertNotNull(input);input.setText(SOURCE);Button add=findButton(screen.getWindow().getDecorView(),"Agregar a la cola");assertNotNull(add);assertTrue("Add button disabled; session="+session.optString("status"),add.isEnabled());assertTrue(add.performClick());});
   clickAccessible("Usar selección");clickAccessible("Agregar");
   // No POST/resources from the test: the actual UI stores and schedules its command.
   LinkOutbox.Entry entry=null;long until=System.currentTimeMillis()+15000;
   while(System.currentTimeMillis()<until){for(LinkOutbox.Entry e:LinkOutboxDispatch.box(context,ORIGIN,session).entries())if(SOURCE.equals(e.url))entry=e;if(entry!=null)break;Thread.sleep(100);}
   assertNotNull("UI did not persist its URL",entry);assertEquals(SOURCE,entry.url);
   shell("cmd jobscheduler run -f dev.arglabs.tubego 228246");
   until=System.currentTimeMillis()+180000;
   String resource="";boolean complete=false,startedTransfer=false;
   while(System.currentTimeMillis()<until){
    for(LinkOutbox.Entry e:LinkOutboxDispatch.box(context,ORIGIN,session).entries())if(entry.id.equals(e.id)){
     if("error".equals(e.state))fail("Submission failed: "+e.error);
     if("submitted".equals(e.state))resource=e.resourceId;
    }
    if(!resource.isEmpty()){
     JSONObject snapshot=api.request("GET","/device/sync",null,session.getString("token"));
     var deliveries=snapshot.getJSONArray("deliveries");
     for(int i=0;i<deliveries.length();i++){JSONObject item=deliveries.getJSONObject(i);if(resource.equals(item.getString("id"))&&"complete".equals(item.optString("delivery_status")))complete=true;}
     if(!startedTransfer&&deliveries.length()>0){TransferJobs.wake(context,ORIGIN,false);shell("cmd jobscheduler run -f dev.arglabs.tubego "+TransferJobs.jobId(ORIGIN,false));startedTransfer=true;}
     if(complete)break;
    }
    Thread.sleep(1000);
   }
   assertFalse("Command did not return a resource",resource.isEmpty());assertTrue("No server-confirmed native download",complete);
   TransferRecord record=TransferRecord.read(new File(root,resource+".properties"));assertEquals("complete",record.state);assertTrue(record.size>0);
   assertTrue(ResumableTransfer.verifies(record.media(),record.size,record.sha256));assertFalse(record.part().exists());assertEquals("video",record.mediaFormat);
   JSONObject detail=api.request("GET","/resources/"+resource,null,session.getString("token"));assertEquals(SOURCE,detail.getString("source_url"));assertEquals("completed",detail.getJSONObject("latest_task").getString("status"));
   android.util.Log.i("TubegoMobileSourceE2E","PASS resource="+resource+" bytes="+record.size+" sha256="+record.sha256);
  }finally{if(activity!=null){Activity close=activity;getInstrumentation().runOnMainSync(close::finish);}TransferJobs.stop(context,ORIGIN);HttpsURLConnection.setDefaultSSLSocketFactory(original);}
 }
 private EditText findInput(View view){if(view instanceof EditText)return(EditText)view;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){EditText match=findInput(group.getChildAt(i));if(match!=null)return match;}return null;}
 private Button findButton(View view,String text){if(view instanceof Button button&&text.equals(button.getText().toString()))return button;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){Button match=findButton(group.getChildAt(i),text);if(match!=null)return match;}return null;}
 private void clickAccessible(String text)throws Exception{long until=System.currentTimeMillis()+30000;while(System.currentTimeMillis()<until){AccessibilityNodeInfo root=getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(text.contentEquals(node.getText())&&node.isClickable()&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;Thread.sleep(100);}String dump=dump(getInstrumentation().getUiAutomation().getRootInActiveWindow(),0);android.util.Log.e("TubegoMobileSourceE2E",dump);fail("Missing dialog button: "+text+"; accessibility="+dump);}
 private String dump(AccessibilityNodeInfo node,int depth){if(node==null)return "<null-root>";if(depth>12)return "<depth-limit>";StringBuilder out=new StringBuilder("["+node.getClassName()+" text="+node.getText()+" clickable="+node.isClickable()+" enabled="+node.isEnabled()+"]");for(int i=0;i<node.getChildCount();i++)out.append(dump(node.getChild(i),depth+1));return out.toString();}
 private void shell(String command)throws Exception{try(var fd=getInstrumentation().getUiAutomation().executeShellCommand(command);var input=new FileInputStream(fd.getFileDescriptor())){while(input.read()!=-1){}}}
}
