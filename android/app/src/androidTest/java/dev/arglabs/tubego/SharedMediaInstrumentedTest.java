package dev.arglabs.tubego;

import android.content.Context;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
public final class SharedMediaInstrumentedTest extends InstrumentationTestCase {
 private Context context;private JSONObject session;private TransferRecord record;private String origin="https://shared-file-fixture.example.invalid";
 @Override protected void setUp()throws Exception{
  super.setUp();context=getInstrumentation().getTargetContext();
  session=new JSONObject().put("token","shared-file-fixture").put("status","approved").put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString());new SessionStore(context,origin).save(session);
  byte[] data="fixture media bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))sha.append(String.format("%02x",b&255));
  File root=LocalLibraryStorage.root(context,origin,session.getString("user_id"),session.getString("device_id"));root.mkdirs();
  record=new TransferRecord(root,UUID.randomUUID().toString(),sha.toString(),data.length,"TubeGo test "+UUID.randomUUID(),"");record.state="complete";record.publishRequested=true;Files.write(record.media().toPath(),data);record.save();
 }
 @Override protected void tearDown()throws Exception{
  if(record!=null&&!record.sharedUri.isEmpty()){context.getContentResolver().delete(Uri.parse(record.sharedUri),null,null);SharedMedia.forget(context,record.sharedUri);}
  if(record!=null)LocalLibraryStorage.wipe(context,origin,session.getString("user_id"),session.getString("device_id"));
  new SessionStore(context,origin).clear();super.tearDown();
 }
 public void testSinglePublicCopyNamePathEditAndShare()throws Exception{
  SharedMedia.publish(context,origin,session,record);
  assertFalse("Private staging must be removed",record.media().exists());assertFalse(record.sharedUri.isEmpty());
  assertTrue(SharedMedia.available(context,record));assertTrue(SharedMedia.location(context,record).contains("Download/TubeGo/"));
  long original=SharedMedia.size(context,record);assertEquals(record.size,original);
  Uri uri=PrivateMediaContentProvider.create(context,origin,record.id);assertEquals(MediaFileName.display(record.title,false),uri.getLastPathSegment());
  try(OutputStream output=context.getContentResolver().openOutputStream(Uri.parse(record.sharedUri),"wt")){output.write("user-edited media".getBytes());}
  SharedMedia.verify(context,record);assertEquals("User edits are permitted",17,SharedMedia.size(context,record));
  try(InputStream input=context.getContentResolver().openInputStream(uri)){assertEquals("user-edited media",new String(input.readAllBytes()));}
  SharedMedia.publish(context,origin,session,record);assertFalse(record.media().exists());
  String saved=record.sharedUri;new SessionStore(context,origin).clear();LocalLibraryStorage.wipe(context,origin,session.getString("user_id"),session.getString("device_id"));
  try(InputStream input=context.getContentResolver().openInputStream(Uri.parse(saved))){assertNotNull("Published user files survive logout",input);}
 }
 public void testExplicitDeletionRemovesPublicFile()throws Exception{
  SharedMedia.publish(context,origin,session,record);SharedMedia.delete(context,record.root,record.id);
  assertFalse(SharedMedia.available(context,record));assertTrue(LocalResourceDeletion.deleted(record.root,record.id));
 }
 public void testLegacyFileIsNotMovedAutomatically()throws Exception{
  record.publishRequested=false;SharedMedia.publish(context,origin,session,record);
  assertTrue(record.media().isFile());assertEquals("",record.sharedUri);
 }
 public void testSameTitlesNeverOverwriteAnExistingFile()throws Exception{
  SharedMedia.publish(context,origin,session,record);
  byte[] original;try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(record.sharedUri))){original=in.readAllBytes();}
  TransferRecord other=new TransferRecord(record.root,UUID.randomUUID().toString(),record.sha256,record.size,record.title,"");other.state="complete";other.publishRequested=true;Files.write(other.media().toPath(),original);other.save();
  try{SharedMedia.publish(context,origin,session,other);assertFalse(record.sharedUri.equals(other.sharedUri));assertTrue(SharedMedia.location(context,other).endsWith(MediaFileName.numbered(record.title,false,2)));assertFalse(SharedMedia.location(context,record).equals(SharedMedia.location(context,other)));
   try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(record.sharedUri))){assertTrue(java.util.Arrays.equals(original,in.readAllBytes()));}
  }finally{if(!other.sharedUri.isEmpty())context.getContentResolver().delete(Uri.parse(other.sharedUri),null,null);SharedMedia.forget(context,other.sharedUri);}
 }
 public void testInterruptedPublicationUsesItsExistingPendingFile()throws Exception{
  android.content.ContentValues values=new android.content.ContentValues();values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,MediaFileName.display(record.title,false));values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"video/mp4");values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/TubeGo");values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,1);
  Uri pending=context.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);assertNotNull(pending);
  record.pendingUri=pending.toString();record.save();
  try(OutputStream output=context.getContentResolver().openOutputStream(pending,"w")){output.write(new byte[]{1});}
  SharedMedia.publish(context,origin,session,record);assertEquals(pending.toString(),record.sharedUri);assertEquals("",record.pendingUri);assertFalse(record.media().exists());assertEquals(record.size,SharedMedia.size(context,record));
 }
 public void testReloginRestoresThePublicFileWithoutDownloadingAnotherCopy()throws Exception{
  SharedMedia.publish(context,origin,session,record);String uri=record.sharedUri;
  LocalLibraryStorage.wipe(context,origin,session.getString("user_id"),session.getString("device_id"));assertFalse(record.manifest().exists());
  SharedMedia.restoreRecords(context,origin,session,record.root);TransferRecord restored=TransferRecord.read(record.manifest());
  assertEquals(uri,restored.sharedUri);assertFalse(restored.media().exists());assertTrue(SharedMedia.available(context,restored));
 }
}
