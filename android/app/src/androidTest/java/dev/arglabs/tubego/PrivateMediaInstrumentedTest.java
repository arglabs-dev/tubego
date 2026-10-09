package dev.arglabs.tubego;
import android.test.InstrumentationTestCase;
import android.content.Context;import android.net.Uri;import android.os.ParcelFileDescriptor;
import org.json.JSONObject;import java.io.*;import java.nio.file.Files;import java.security.MessageDigest;
@SuppressWarnings("deprecation")
public final class PrivateMediaInstrumentedTest extends InstrumentationTestCase {
 static final String ORIGIN="https://offline-player.example.invalid",USER="11111111-1111-4111-8111-111111111111",DEVICE="22222222-2222-4222-8222-222222222222",RESOURCE="33333333-3333-4333-8333-333333333333";
 Context context;SessionStore sessions;TransferRecord record;JSONObject session;
 @Override protected void setUp() throws Exception {super.setUp();context=getInstrumentation().getTargetContext();sessions=new SessionStore(context,ORIGIN);
  session=new JSONObject().put("token","offline-test-session").put("status","approved").put("user_id",USER).put("device_id",DEVICE);sessions.save(session);
  byte[] bytes=new byte[262144];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i%251);StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",b&255));
  File root=LocalLibraryStorage.root(context,ORIGIN,USER,DEVICE);root.mkdirs();record=new TransferRecord(root,RESOURCE,hash.toString(),bytes.length,"Offline fixture","");record.state="complete";Files.write(record.media().toPath(),bytes);record.save();
 }
 @Override protected void tearDown() throws Exception {sessions.clear();LocalLibraryStorage.wipe(context,ORIGIN,USER,DEVICE);super.tearDown();}
 public void testSeekableReadOnlyLocalFileAndKnownRevocationStopsOpenReader() throws Exception {
  Uri uri=PrivateMediaContentProvider.create(context,ORIGIN,RESOURCE);assertEquals("video/mp4",context.getContentResolver().getType(uri));
  try(ParcelFileDescriptor pfd=context.getContentResolver().openFileDescriptor(uri,"r");FileInputStream stream=new FileInputStream(pfd.getFileDescriptor())) {
   stream.getChannel().position(65536);byte[] bytes=new byte[128];assertEquals(128,stream.read(bytes));assertEquals((byte)(65536%251),bytes[0]);
   synchronized(SessionStore.class){sessions.clear();}
   stream.getChannel().position(131072);
   try{stream.read(bytes);fail("Existing proxy must stop after revocation");}catch(IOException expected){}
  }
 }
 public void testRejectsArbitraryUriAndWriteModes() throws Exception {
  Uri uri=PrivateMediaContentProvider.create(context,ORIGIN,RESOURCE);
  try{context.getContentResolver().openFileDescriptor(uri,"rw");fail("write");}catch(SecurityException expected){}
  try{context.getContentResolver().openFileDescriptor(uri.buildUpon().appendQueryParameter("path","/data/data").build(),"r");fail("query");}catch(SecurityException expected){}
  try{context.getContentResolver().openFileDescriptor(Uri.parse("content://"+PrivateMediaContentProvider.AUTHORITY+"/media/../../../sessions"),"r");fail("path");}catch(SecurityException expected){}
 }
 public void testOtherAccountAndSameOwnerNewSessionCannotReuseGrant() throws Exception {
  Uri uri=PrivateMediaContentProvider.create(context,ORIGIN,RESOURCE);
  sessions.save(new JSONObject(session.toString()).put("user_id","44444444-4444-4444-8444-444444444444"));
  try{context.getContentResolver().openFileDescriptor(uri,"r");fail("foreign owner");}catch(FileNotFoundException expected){}
  sessions.save(new JSONObject(session.toString()).put("token","new-session"));
  try{context.getContentResolver().openFileDescriptor(uri,"r");fail("old session grant");}catch(FileNotFoundException expected){}
 }
 public void testTombstoneAndChecksumBlockReadsWithoutHiddenDownload() throws Exception {
  Uri uri=PrivateMediaContentProvider.create(context,ORIGIN,RESOURCE);Files.write(new File(record.root,RESOURCE+".deleted").toPath(),new byte[0]);
  try{context.getContentResolver().openFileDescriptor(uri,"r");fail("deleted");}catch(FileNotFoundException expected){}
  new File(record.root,RESOURCE+".deleted").delete();byte[] bytes=Files.readAllBytes(record.media().toPath());bytes[0]^=1;Files.write(record.media().toPath(),bytes);
  try{PrivateMediaContentProvider.create(context,ORIGIN,RESOURCE);fail("corrupted");}catch(IOException expected){}
  assertTrue(record.media().exists());assertFalse(record.part().exists());
 }
}
