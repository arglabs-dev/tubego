package dev.arglabs.tubego;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import java.io.*;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Published files belong to the user. Private staging is removed only after verified publication. */
final class SharedMedia {
 private SharedMedia(){}
 private static final Object[] LOCKS=new Object[32];static{for(int i=0;i<32;i++)LOCKS[i]=new Object();}
 private static String key(String origin,JSONObject session)throws Exception{return TransferKey.accountPrefix(origin,session.getString("user_id"),session.getString("device_id"));}
 static String tree(Context c,String origin,JSONObject s)throws Exception{return c.getSharedPreferences("tubego_download_folder",0).getString(key(origin,s),"");}
 static void folder(Context c,String origin,JSONObject s,String uri)throws Exception{if(!c.getSharedPreferences("tubego_download_folder",0).edit().putString(key(origin,s),uri).commit())throw new IOException("No se pudo guardar la carpeta");}
 static String folderLocation(String uri){
  if(uri.isEmpty())return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"TubeGo").getAbsolutePath();
  Uri tree=Uri.parse(uri);
  if("com.android.externalstorage.documents".equals(tree.getAuthority()))try{String[] id=DocumentsContract.getTreeDocumentId(tree).split(":",2);if(id.length==2&&id[0].equals("primary"))return new File(Environment.getExternalStorageDirectory(),id[1]).getAbsolutePath();}catch(Exception ignored){}
  return uri;
 }
 private static String indexPrefix(String origin,JSONObject s)throws Exception{return TransferKey.accountPrefix(origin,s.getString("user_id"),"public-files");}
 private static void remember(Context c,String origin,JSONObject s,TransferRecord r)throws Exception{
  JSONObject data=new JSONObject().put("id",r.id).put("uri",r.sharedUri).put("sha",r.sha256).put("size",r.size).put("title",r.title).put("format",r.mediaFormat).put("created",r.createdAt);
  synchronized(SessionStore.class){
   if(!s.getString("token").equals(new SessionStore(c,origin).token()))throw new IOException("La sesión cambió");
   if(!c.getSharedPreferences("tubego_public_files",0).edit().putString(indexPrefix(origin,s)+r.id,data.toString()).commit())throw new IOException("No se pudo guardar la ubicación del archivo");
  }
 }
 static void forget(Context c,String uri){
  android.content.SharedPreferences prefs=c.getSharedPreferences("tubego_public_files",0);android.content.SharedPreferences.Editor edit=prefs.edit();
  for(java.util.Map.Entry<String,?> e:prefs.getAll().entrySet())try{if(uri.equals(new JSONObject(String.valueOf(e.getValue())).optString("uri")))edit.remove(e.getKey());}catch(Exception ignored){}
  edit.commit();
 }
 static void restoreRecords(Context c,String origin,JSONObject s,File root)throws Exception{
  String prefix=indexPrefix(origin,s);
  for(java.util.Map.Entry<String,?> e:c.getSharedPreferences("tubego_public_files",0).getAll().entrySet()){
   if(!e.getKey().startsWith(prefix))continue;
   JSONObject d=new JSONObject(String.valueOf(e.getValue()));String id=java.util.UUID.fromString(d.getString("id")).toString();
   if(new File(root,id+".properties").exists()||LocalResourceDeletion.deleted(root,id))continue;
   TransferRecord r=new TransferRecord(root,id,d.getString("sha"),d.getLong("size"),d.getString("title"),d.optString("created"));
   r.sharedUri=d.getString("uri");r.mediaFormat=d.optString("format","video");r.state="complete";r.publishRequested=true;r.save();
  }
 }
 static boolean available(Context c,TransferRecord r)throws IOException {
  if(r.sharedUri.isEmpty())return OfflineMediaAccess.available(r);
  if("deleted".equals(r.state)||LocalResourceDeletion.deleted(r.root,r.id))return false;
  try(android.os.ParcelFileDescriptor fd=c.getContentResolver().openFileDescriptor(Uri.parse(r.sharedUri),"r")){return fd!=null&&fd.getStatSize()!=0;}
  catch(Exception missing){return false;}
 }
 static void verify(Context c,TransferRecord r)throws IOException {
  if(r.sharedUri.isEmpty())OfflineMediaAccess.verify(r);
  else if(!available(c,r))throw new IOException("El archivo fue movido, borrado o su carpeta ya no está disponible");
 }
 static ParcelFileDescriptor open(Context c,TransferRecord r)throws IOException {
  verify(c,r);return r.sharedUri.isEmpty()?ParcelFileDescriptor.open(r.media(),ParcelFileDescriptor.MODE_READ_ONLY):c.getContentResolver().openFileDescriptor(Uri.parse(r.sharedUri),"r");
 }
 static String location(Context c,TransferRecord r){
  if(r.sharedUri.isEmpty())return r.media().getAbsolutePath();
  Uri uri=Uri.parse(r.sharedUri);
  if("com.android.externalstorage.documents".equals(uri.getAuthority())){
   try{String id=DocumentsContract.getDocumentId(uri);String[] parts=id.split(":",2);if(parts.length==2&&parts[0].equals("primary"))return new File(Environment.getExternalStorageDirectory(),parts[1]).getAbsolutePath();}catch(Exception ignored){}
  }
  try(Cursor cursor=c.getContentResolver().query(uri,new String[]{MediaStore.MediaColumns.DATA},null,null,null)){
   if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))return cursor.getString(0);
  }catch(Exception ignored){}
  return r.sharedUri;
 }
 static void delete(Context c,File root,String id)throws IOException{
  File manifest=new File(root,id+".properties");
  if(manifest.isFile()){
   TransferRecord r=OfflineMediaAccess.record(root,id);
   for(String target:new String[]{r.sharedUri,r.pendingUri})if(!target.isEmpty()){
    Uri uri=Uri.parse(target);
    try{if(DocumentsContract.isDocumentUri(c,uri))DocumentsContract.deleteDocument(c.getContentResolver(),uri);else c.getContentResolver().delete(uri,null,null);}
    catch(FileNotFoundException gone){/* Already deleted outside TubeGo. */}
    catch(Exception denied){throw new IOException("No se pudo borrar el archivo de la carpeta elegida",denied);}
    forget(c,target);
   }
  }
  LocalResourceDeletion.apply(root,id);
 }
 static void cleanupPending(Context c,File root)throws IOException {
  File[] manifests=root.listFiles((d,n)->n.endsWith(".properties"));if(manifests==null)return;
  for(File file:manifests){
   if(!file.getName().matches("[0-9a-f-]{36}\\.properties")||java.nio.file.Files.isSymbolicLink(file.toPath()))continue;
   try{TransferRecord r=TransferRecord.read(file);if(!r.pendingUri.isEmpty()){
    Uri uri=Uri.parse(r.pendingUri);if(DocumentsContract.isDocumentUri(c,uri))DocumentsContract.deleteDocument(c.getContentResolver(),uri);else c.getContentResolver().delete(uri,null,null);
   }}catch(Exception error){throw new IOException("No se pudo limpiar un archivo temporal de la carpeta elegida",error);}
  }
 }
 static long size(Context c,TransferRecord r)throws IOException{
  if(r.sharedUri.isEmpty())return r.size;
  try(ParcelFileDescriptor fd=c.getContentResolver().openFileDescriptor(Uri.parse(r.sharedUri),"r")){return fd==null?-1:fd.getStatSize();}
 }
 static void publish(Context c,String origin,JSONObject expected,TransferRecord r)throws Exception {
  synchronized(LOCKS[((r.root.getAbsolutePath()+r.id).hashCode()&0x7fffffff)%LOCKS.length]) {
   TransferRecord latest=OfflineMediaAccess.record(r.root,r.id);
   if(!latest.sharedUri.isEmpty()){r.sharedUri=latest.sharedUri;r.pendingUri="";remember(c,origin,expected,r);if(available(c,r))java.nio.file.Files.deleteIfExists(r.media().toPath());return;}
   r.pendingUri=latest.pendingUri;
   publishFile(c,origin,expected,r);
  }
 }
 private static void publishFile(Context c,String origin,JSONObject expected,TransferRecord r)throws Exception {
  if(!r.publishRequested||!r.sharedUri.isEmpty())return;
  File source=r.media();if(!source.isFile())return;
  SessionStore store=new SessionStore(c,origin);String token=expected.getString("token");
  long generation=store.generation();
  String selected=tree(c,origin,expected),name=MediaFileName.display(r.title,"audio".equals(r.mediaFormat));
  if(selected.isEmpty()&&!new AndroidStorageGuard(c,origin,expected.getString("user_id"),expected.getString("device_id")).allows(r.size))throw new ResumableTransfer.StoragePause();
  ContentResolver resolver=c.getContentResolver();Uri destination;
  synchronized(SessionStore.class){
   if(!token.equals(store.token())||LocalResourceDeletion.deleted(r.root,r.id))throw new IOException("La sesión cambió");
   if(!r.pendingUri.isEmpty())destination=Uri.parse(r.pendingUri);
   else {
    if(selected.isEmpty()){
     ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,OfflineMediaAccess.mime(r));
     values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/TubeGo");values.put(MediaStore.MediaColumns.IS_PENDING,1);
     destination=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
    }else{
     Uri tree=Uri.parse(selected),parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
     destination=DocumentsContract.createDocument(resolver,parent,OfflineMediaAccess.mime(r),name);
    }
    if(destination==null)throw new IOException("No se pudo crear el archivo en la carpeta elegida");
    r.pendingUri=destination.toString();r.save();
   }
  }
  // Resume interrupted publication using the already recorded destination; never duplicate it.
  MessageDigest digest=MessageDigest.getInstance("SHA-256");long copied=0;
  try(ParcelFileDescriptor descriptor=resolver.openFileDescriptor(destination,"rwt");FileInputStream input=new FileInputStream(source)){
   if(descriptor==null)throw new IOException("Carpeta no disponible");
   try(FileOutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)){
    byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){
     if(generation!=store.generation())throw new IOException("La sesión cambió");
     output.write(buffer,0,n);digest.update(buffer,0,n);copied+=n;
    }
    output.flush();output.getFD().sync();
   }
  }
  catch(Exception interrupted){
   // Do not leave a hidden partial consuming the volume after a failed publication.
   try(ParcelFileDescriptor partial=resolver.openFileDescriptor(destination,"rwt")){/* truncate pending output */}catch(Exception ignored){}
   throw interrupted;
  }
  StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
  if(copied!=r.size||!sha.toString().equals(r.sha256))throw new IOException("El archivo no pasó la verificación");
  synchronized(SessionStore.class){
   if(!token.equals(store.token())||LocalResourceDeletion.deleted(r.root,r.id))throw new IOException("La sesión cambió");
   if("media".equals(destination.getAuthority())){ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);resolver.update(destination,done,null,null);}
   r.sharedUri=destination.toString();r.pendingUri="";r.state="complete";r.save();remember(c,origin,expected,r);
   if(!source.delete()&&source.exists())throw new IOException("No se pudo eliminar el temporal privado");
  }
 }
}
