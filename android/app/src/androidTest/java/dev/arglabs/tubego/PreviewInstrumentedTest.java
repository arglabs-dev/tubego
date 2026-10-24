package dev.arglabs.tubego;

import android.test.InstrumentationTestCase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import java.io.ByteArrayOutputStream;

@SuppressWarnings("deprecation")
public final class PreviewInstrumentedTest extends InstrumentationTestCase {
 public void testSourcePreviewDecodesAndBoundsUntrustedData()throws Exception{
  Bitmap source=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);source.eraseColor(Color.BLUE);
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.JPEG,80,bytes);
  String encoded=Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP);
  MediaInfoClient.Info info=MediaInfoClient.Info.fromJson(new org.json.JSONObject().put("url","https://example.com/video").put("title","Mi video").put("duration_seconds",12).put("thumbnail_base64",encoded));
  Bitmap preview=Thumbnails.decode(info.thumbnailBase64);assertNotNull(preview);assertEquals(320,preview.getWidth());assertEquals(180,preview.getHeight());
  assertNull(Thumbnails.decode("invalid"));assertNull(Thumbnails.decode("a".repeat(44001)));assertNull(Thumbnails.decode((String)null));
 }
 public void testLibraryDisplaysCachedPreviewOffline()throws Exception {
  android.content.Context context=getInstrumentation().getTargetContext();
  String origin="https://thumbnail-test.example.invalid",uid=java.util.UUID.randomUUID().toString(),did=java.util.UUID.randomUUID().toString(),rid=java.util.UUID.randomUUID().toString();
  android.content.SharedPreferences prefs=context.getSharedPreferences("server_connection",0);String previous=prefs.getString("server_url",null);
  org.json.JSONObject session=new org.json.JSONObject().put("token","preview-fixture").put("status","approved").put("role","user").put("user_id",uid).put("device_id",did);
  java.io.File root=LocalLibraryStorage.root(context,origin,uid,did);root.mkdirs();android.app.Activity activity=null;
  try {
   new SessionStore(context,origin).save(session);prefs.edit().putString("server_url",origin).commit();
   byte[] media;try(java.io.InputStream in=getInstrumentation().getContext().getResources().openRawResource(getInstrumentation().getContext().getResources().getIdentifier("vlc_sample","raw",getInstrumentation().getContext().getPackageName()))){media=in.readAllBytes();}
   StringBuilder hash=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(media))hash.append(String.format("%02x",b&255));
   TransferRecord record=new TransferRecord(root,rid,hash.toString(),media.length,"Clip de prueba: vista previa","");record.state="complete";java.nio.file.Files.write(record.media().toPath(),media);record.save();
   android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();Bitmap cover;
   try{retriever.setDataSource(record.media().getAbsolutePath());cover=retriever.getScaledFrameAtTime(0,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,180);}finally{retriever.release();}
   assertNotNull(cover);try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(root,rid+".thumbnail"))){cover.compress(Bitmap.CompressFormat.JPEG,80,out);}
   activity=getInstrumentation().startActivitySync(new android.content.Intent(context,LibraryActivity.class).putExtra("server_url",origin).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
   android.app.Activity shown=activity;boolean[] visible={false};
   for(int attempt=0;attempt<60&&!visible[0];attempt++) {
    getInstrumentation().runOnMainSync(()->visible[0]=hasBitmap(shown.getWindow().getDecorView()));if(!visible[0])Thread.sleep(100);
   }
   assertTrue("The cached source preview must appear in the library",visible[0]);
   Bitmap shot=getInstrumentation().getUiAutomation().takeScreenshot();
   try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"preview-feature.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}
  } finally {
   if(activity!=null){android.app.Activity shown=activity;getInstrumentation().runOnMainSync(shown::finish);}
   TransferJobs.stop(context,origin);new SessionStore(context,origin).clear();LocalLibraryStorage.wipe(context,origin,uid,did);
   if(previous==null)prefs.edit().remove("server_url").commit();else prefs.edit().putString("server_url",previous).commit();
  }
 }
 private boolean hasBitmap(android.view.View view) {
  if(view instanceof android.widget.ImageView && ((android.widget.ImageView)view).getDrawable() instanceof android.graphics.drawable.BitmapDrawable)return true;
  if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasBitmap(group.getChildAt(i)))return true;}
  return false;
 }
}
