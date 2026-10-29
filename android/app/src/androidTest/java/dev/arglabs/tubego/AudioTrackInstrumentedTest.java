package dev.arglabs.tubego;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.InputStream;
import java.io.File;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.UUID;
import javax.net.ssl.*;
import org.json.JSONObject;

/** Disposable HTTPS metadata; submission is inspected locally without sending a download. */
@SuppressWarnings("deprecation")
public final class AudioTrackInstrumentedTest extends InstrumentationTestCase {
 private final String origin="https://10.0.2.2:9445";
 private Context context;private JSONObject session;private SSLSocketFactory original;private String previous;
 @Override protected void setUp() throws Exception {
  super.setUp();context=getInstrumentation().getTargetContext();
  previous=context.getSharedPreferences("server_connection",0).getString("server_url",null);
  context.getSharedPreferences("server_connection",0).edit().putString("server_url",origin).commit();
  original=HttpsURLConnection.getDefaultSSLSocketFactory();
  KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null);
  Context test=getInstrumentation().getContext();int cert=test.getResources().getIdentifier("visual_cert","raw",test.getPackageName());
  try(InputStream input=test.getResources().openRawResource(cert)){trust.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509").generateCertificate(input));}
  TrustManagerFactory managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(trust);
  SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,managers.getTrustManagers(),null);HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
  session=new JSONObject().put("token","ui-user-es").put("role","user").put("status","approved").put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString());
  new SessionStore(context,origin).save(session);LanguagePreferences.saveLocal(context,origin,"es");TransferJobs.stop(context,origin);
 }
 @Override protected void tearDown() throws Exception {
  TransferJobs.stop(context,origin);new SessionStore(context,origin).clear();LocalLibraryStorage.wipe(context,origin,session.getString("user_id"),session.getString("device_id"));
  var preferences=context.getSharedPreferences("server_connection",0).edit();if(previous==null)preferences.remove("server_url");else preferences.putString("server_url",previous);preferences.commit();
  HttpsURLConnection.setDefaultSSLSocketFactory(original);super.tearDown();
 }
 private void capture(String name) throws Exception {
  Thread.sleep(300);File folder=context.getExternalFilesDir("ui-audio-tracks");folder.mkdirs();
  android.graphics.Bitmap bitmap=getInstrumentation().getUiAutomation().takeScreenshot();
  try(java.io.FileOutputStream output=new java.io.FileOutputStream(new File(folder,name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}bitmap.recycle();
 }
 private AccessibilityNodeInfo find(String text) {
  var root=getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null)return null;
  for(var node:root.findAccessibilityNodeInfosByText(text))if(node.getText()!=null&&node.getText().toString().equalsIgnoreCase(text))return node;
  return null;
 }
 private AccessibilityNodeInfo waitText(String text) throws Exception {
  long end=System.currentTimeMillis()+8000;AccessibilityNodeInfo node;
  while((node=find(text))==null&&System.currentTimeMillis()<end){Thread.sleep(100);getInstrumentation().waitForIdleSync();}
  assertNotNull(text,node);return node;
 }
 private void click(String text) throws Exception {var node=waitText(text);node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());getInstrumentation().waitForIdleSync();assertTrue(text,node.performAction(AccessibilityNodeInfo.ACTION_CLICK));getInstrumentation().waitForIdleSync();}
 private Activity open(String url) {
  Activity activity=getInstrumentation().startActivitySync(new Intent(context,LibraryActivity.class).putExtra("server_url",origin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  getInstrumentation().runOnMainSync(()->AddLinkSheet.show((LibraryActivity)activity,url,"720",""));return activity;
 }
 public void testAvailableRegionalTracksAndAutoDubbingPersistThroughAudioOnlyOutbox() throws Exception {
  Activity activity=open("https://video.example/with-spanish");
  try{
   waitText("Elige una pista disponible. Se conservará también al descargar solo audio.");
   click("Pista de audio: Original");
   var regional=waitText("español · es-419 · Doblaje automático");
   assertNotNull(waitText("español · es-es · Doblaje"));
   capture("audio-track-choices");
   assertTrue(regional.performAction(AccessibilityNodeInfo.ACTION_CLICK));getInstrumentation().waitForIdleSync();
   click("Solo audio");
   click("Agregar");
   File root=LinkOutboxDispatch.root(context,origin,session);
   long until=System.currentTimeMillis()+5000;java.util.List<LinkOutbox.Entry> entries;
   do{entries=LinkOutboxDispatch.box(context,origin,session).entries();if(!entries.isEmpty())break;Thread.sleep(100);}while(System.currentTimeMillis()<until);
   assertEquals(1,entries.size());assertEquals("es-419~auto",entries.get(0).audioTrack);assertEquals("audio",entries.get(0).selection);
   assertTrue(new CommandQueue(root).find(entries.get(0).id).payload.contains("es-419~auto"));
  }finally{getInstrumentation().runOnMainSync(activity::finish);}
 }
 public void testNoSpanishOffersOriginalAndExplicitCancellation() throws Exception {
  Activity activity=open("https://video.example/no-spanish");
  try{
   waitText("La fuente no expone audio en español. Puedes continuar con Original o cerrar para cancelar.");
   click("Pista de audio: Original");waitText("Original");assertNull(find("español · es-419 · Doblaje automático"));click("Cancelar");
   assertTrue(LinkOutboxDispatch.box(context,origin,session).entries().isEmpty());
  }finally{getInstrumentation().runOnMainSync(activity::finish);}
 }
}
