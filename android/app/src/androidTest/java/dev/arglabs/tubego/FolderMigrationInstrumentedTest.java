package dev.arglabs.tubego;

import android.content.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
public final class FolderMigrationInstrumentedTest extends InstrumentationTestCase {
 private Context context;private JSONObject session;private TransferRecord record;private final String origin="https://folder-fixture.example.invalid";
 private String namespace;
 @Override protected void setUp()throws Exception{
  super.setUp();namespace="folder-test-"+UUID.randomUUID();Context base=getInstrumentation().getTargetContext();
  context=new ContextWrapper(base){
   @Override public Context getApplicationContext(){return this;}
   @Override public File getFilesDir(){return new File(super.getFilesDir(),namespace);}
   @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(namespace+name,mode);}
  };
  session=new JSONObject().put("token","fixture").put("status","approved").put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString());new SessionStore(context,origin).save(session);
  byte[] data="existing video bytes".getBytes();StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))digest.append(String.format(Locale.ROOT,"%02x",b&255));
  File root=LocalLibraryStorage.root(context,origin,session.getString("user_id"),session.getString("device_id"));root.mkdirs();
  record=new TransferRecord(root,UUID.randomUUID().toString(),digest.toString(),data.length,"Folder fixture "+UUID.randomUUID(),"");record.state="complete";Files.write(record.media().toPath(),data);record.save();
 }
 @Override protected void tearDown()throws Exception{
  for(Object value:context.getSharedPreferences("tubego_public_files",0).getAll().values())try{context.getContentResolver().delete(Uri.parse(new JSONObject(String.valueOf(value)).getString("uri")),null,null);}catch(Exception ignored){}
  if(record.manifest().exists()){TransferRecord latest=TransferRecord.read(record.manifest());for(String uri:new String[]{latest.sharedUri,latest.moveUri})if(!uri.isEmpty())try{context.getContentResolver().delete(Uri.parse(uri),null,null);}catch(Exception ignored){}}
  LocalLibraryStorage.wipeDirectory(context.getFilesDir());new SessionStore(context,origin).clear();super.tearDown();
 }
 public void testLegacyMoveIsGlobalAndRestoresAfterLogout()throws Exception{
  SharedMedia.folder(context,origin,session,"");record=TransferRecord.read(record.manifest());assertFalse(record.media().exists());assertTrue(SharedMedia.available(context,record));assertTrue(SharedMedia.location(context,record).contains("Download/TubeGo/"));
  JSONObject other=new JSONObject(session.toString()).put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString());
  assertEquals(SharedMedia.tree(context,origin,session),SharedMedia.tree(context,"https://other.example.invalid",other));assertTrue(context.getSharedPreferences("tubego_download_folder",0).contains("global"));
  String uri=record.sharedUri;LocalLibraryStorage.wipe(context,origin,session.getString("user_id"),session.getString("device_id"));SharedMedia.restoreRecords(context,origin,session,record.root);assertEquals(uri,TransferRecord.read(record.manifest()).sharedUri);
 }
 private Uri publicFile(String path,byte[] bytes)throws Exception{
  ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,MediaFileName.display(record.title,false));values.put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");values.put(MediaStore.MediaColumns.RELATIVE_PATH,path);
  Uri uri=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);try(OutputStream out=context.getContentResolver().openOutputStream(uri)){out.write(bytes);}return uri;
 }
 public void testPublicMovePreservesExternalEditsAndDoesNotDuplicateOnRetry()throws Exception{
  byte[] edited="user edited these video bytes".getBytes();Uri old=publicFile("Download/TubeGoMigrationFixture",edited);record.sharedUri=old.toString();Files.delete(record.media().toPath());record.save();
  SharedMedia.folder(context,origin,session,"");record=TransferRecord.read(record.manifest());assertFalse(old.toString().equals(record.sharedUri));try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(record.sharedUri))){assertTrue(Arrays.equals(edited,in.readAllBytes()));}
  String uri=record.sharedUri;SharedMedia.folder(context,origin,session,"");assertEquals(uri,TransferRecord.read(record.manifest()).sharedUri);
  try(InputStream in=context.getContentResolver().openInputStream(old)){fail("Original must be removed");}catch(FileNotFoundException expected){}
 }
 public void testFailureKeepsOriginalAndPreviousSetting()throws Exception{
  try{SharedMedia.folder(context,origin,session,"content://missing.provider/tree/invalid");fail("Unavailable destination must fail");}catch(Exception expected){}
  assertTrue(record.media().exists());assertFalse(context.getSharedPreferences("tubego_download_folder",0).contains("global"));SharedMedia.folder(context,origin,session,"");record=TransferRecord.read(record.manifest());assertTrue(SharedMedia.available(context,record));assertFalse(record.media().exists());
 }
 public void testInterruptedCopyReusesDestination()throws Exception{
  Uri pending=publicFile("Download/TubeGo",new byte[]{1});record.moveUri=pending.toString();record.moveTarget="";record.save();SharedMedia.folder(context,origin,session,"");record=TransferRecord.read(record.manifest());assertEquals(pending.toString(),record.sharedUri);assertEquals("",record.moveUri);assertFalse(record.media().exists());
 }
 public void testMovesAllLibrariesIncludingAnotherAccount()throws Exception{
  File root=LocalLibraryStorage.root(context,origin,UUID.randomUUID().toString(),UUID.randomUUID().toString());root.mkdirs();
  TransferRecord other=new TransferRecord(root,UUID.randomUUID().toString(),record.sha256,record.size,record.title,"");other.state="complete";Files.write(other.media().toPath(),Files.readAllBytes(record.media().toPath()));other.save();
  SharedMedia.folder(context,origin,session,"");record=TransferRecord.read(record.manifest());other=TransferRecord.read(other.manifest());assertTrue(SharedMedia.available(context,record));assertTrue(SharedMedia.available(context,other));assertFalse(other.media().exists());assertFalse(record.sharedUri.equals(other.sharedUri));
 }
 public void testMovesIndexedFileAfterPrivateManifestWasWiped()throws Exception{
  record.publishRequested=true;record.save();SharedMedia.publish(context,origin,session,record);String old=record.sharedUri;
  ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/TubeGoMigrationFixture");context.getContentResolver().update(Uri.parse(old),values,null,null);
  LocalLibraryStorage.wipe(context,origin,session.getString("user_id"),session.getString("device_id"));assertFalse(record.manifest().exists());
  SharedMedia.folder(context,origin,session,"");SharedMedia.restoreRecords(context,origin,session,record.root);record=TransferRecord.read(record.manifest());assertFalse(old.equals(record.sharedUri));assertTrue(SharedMedia.available(context,record));assertTrue(SharedMedia.location(context,record).contains("Download/TubeGo/"));
 }

 public void testMigrationIgnoresCommandAndLinkOutboxRecords()throws Exception{
  for(String directory:new String[]{"command-outbox","link-outbox","recovery-outbox","deletion-outbox","outbox"}){
   File outbox=new File(record.root,directory);outbox.mkdirs();
   Files.write(new File(outbox,UUID.randomUUID()+".properties").toPath(),"id=queued-command\naction=delete\n".getBytes());
  }
  SharedMedia.folder(context,origin,session,"");record=TransferRecord.read(record.manifest());
  assertFalse(record.media().exists());assertTrue(SharedMedia.available(context,record));
  for(String directory:new String[]{"command-outbox","link-outbox","recovery-outbox","deletion-outbox","outbox"})assertEquals(1,new File(record.root,directory).list().length);
 }

 public void testExistingSpecialCharactersAreNormalizedInTheSameFolder()throws Exception{
  Uri old=publicFile("Download/TubeGo",Files.readAllBytes(record.media().toPath()));
  ContentValues rename=new ContentValues();rename.put(MediaStore.MediaColumns.DISPLAY_NAME,"Canción del niño (2026) "+UUID.randomUUID()+".mp4");context.getContentResolver().update(old,rename,null,null);
  record.title="Canción del niño (2026)";record.sharedUri=old.toString();Files.delete(record.media().toPath());record.save();
  SharedMedia.folder(context,origin,session,"");record=TransferRecord.read(record.manifest());
  try(android.database.Cursor cursor=context.getContentResolver().query(Uri.parse(record.sharedUri),new String[]{MediaStore.MediaColumns.DISPLAY_NAME},null,null,null)){
   assertTrue("Expected a readable destination",cursor.moveToFirst());assertTrue("Unexpected filename: "+cursor.getString(0),cursor.getString(0).matches("Cancion_del_nino_2026(?:_[0-9]+)?\\.mp4"));
  }
  assertFalse(old.toString().equals(record.sharedUri));assertTrue(SharedMedia.available(context,record));
 }

}
