package dev.arglabs.tubego;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Real native navigation, offline card rendering and reviewer screenshots with disposable data. */
@SuppressWarnings("deprecation")
public final class VisualRedesignInstrumentedTest extends InstrumentationTestCase {
 public void testDashboardNavigationKeepsServerAndHidesAdmin()throws Exception{
  Context c=getInstrumentation().getTargetContext();String origin="https://visual.example.invalid";var prefs=c.getSharedPreferences("server_connection",Context.MODE_PRIVATE);String old=prefs.getString("server_url",null);SessionStore sessions=new SessionStore(c,origin);Activity a=null;
  prefs.edit().putString("server_url",origin).commit();sessions.save(new JSONObject().put("token","visual-dashboard-fixture").put("status","approved").put("role","user").put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString()));LanguagePreferences.saveLocal(c,origin,"es");
  try{a=getInstrumentation().startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));getInstrumentation().waitForIdleSync();Activity screen=a;
   assertNotNull(find(a.getWindow().getDecorView(),"Mi biblioteca"));assertEquals(View.GONE,find(a.getWindow().getDecorView(),"Administración").getVisibility());capture("inicio");
   getInstrumentation().runOnMainSync(()->described(screen.getWindow().getDecorView(),"Ajustes").performClick());getInstrumentation().waitForIdleSync();assertNotNull(find(a.getWindow().getDecorView(),"Servidor de Tubego"));assertEquals(origin,input(a.getWindow().getDecorView()).getText().toString());capture("ajustes");
   getInstrumentation().runOnMainSync(()->described(screen.getWindow().getDecorView(),"Inicio").performClick());getInstrumentation().waitForIdleSync();assertEquals(origin,input(a.getWindow().getDecorView()).getText().toString());
  }finally{if(a!=null){Activity screen=a;getInstrumentation().runOnMainSync(screen::finish);}sessions.clear();TransferJobs.stop(c,origin);if(old==null)prefs.edit().remove("server_url").commit();else prefs.edit().putString("server_url",old).commit();}
 }
 public void testOfflineCardsKeepHistoryAndSecondaryActions()throws Exception{
  Context c=getInstrumentation().getTargetContext();String origin="https://visual-library.example.invalid",user=UUID.randomUUID().toString(),device=UUID.randomUUID().toString();SessionStore sessions=new SessionStore(c,origin);File root=LocalLibraryStorage.root(c,origin,user,device);root.mkdirs();Map<String,String> records=new LinkedHashMap<>();
  for(int i=0;i<3;i++){String id=UUID.randomUUID().toString();JSONObject row=new JSONObject().put("id",id).put("title",new String[]{"El arte de aprender algo nuevo","Ideas que cambian tu forma de pensar","Una pausa para volver a empezar"}[i]).put("source_url","https://video.example.invalid/lesson").put("media_format",i==0?"audio":"video").put("quality","720").put("created_at","2026-10-10T08:00:0"+i+"Z").put("server_available",i!=2).put("latest_task",new JSONObject().put("status",i==1?"paused":"completed").put("phase",i==1?"paused":"ready").put("progress",i==1?.45:1));records.put(id,row.toString());}
  new HistoryCache(root).merge(records);sessions.save(new JSONObject().put("token","visual-fixture").put("status","approved").put("role","user").put("user_id",user).put("device_id",device));LanguagePreferences.saveLocal(c,origin,"es");Activity a=null;
  try{a=getInstrumentation().startActivitySync(new Intent(c,LocalLibraryActivity.class).putExtra("server_url",origin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));long until=System.currentTimeMillis()+5000;while(find(a.getWindow().getDecorView(),"El arte de aprender algo nuevo")==null&&System.currentTimeMillis()<until){getInstrumentation().waitForIdleSync();Thread.sleep(50);}assertNotNull(find(a.getWindow().getDecorView(),"El arte de aprender algo nuevo"));assertNotNull(described(a.getWindow().getDecorView(),"Más opciones"));capture("biblioteca");
   Activity screen=a;getInstrumentation().runOnMainSync(()->described(screen.getWindow().getDecorView(),"Más opciones").performClick());getInstrumentation().waitForIdleSync();var nodes=getInstrumentation().getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("Detalles");assertFalse("Secondary actions remain accessible",nodes.isEmpty());
  }finally{if(a!=null){Activity screen=a;getInstrumentation().runOnMainSync(screen::finish);}sessions.clear();LocalLibraryStorage.wipe(c,origin,user,device);}
 }
 public void testLinkFormKeepsSubmissionControl()throws Exception{
  Context c=getInstrumentation().getTargetContext();String origin="https://visual-add.example.invalid",user=UUID.randomUUID().toString(),device=UUID.randomUUID().toString();SessionStore sessions=new SessionStore(c,origin);sessions.save(new JSONObject().put("token","visual-add-fixture").put("status","approved").put("role","user").put("user_id",user).put("device_id",device));LanguagePreferences.saveLocal(c,origin,"es");Activity a=null;
  try{a=getInstrumentation().startActivitySync(new Intent(c,LinkEntryActivity.class).putExtra("server_url",origin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));getInstrumentation().waitForIdleSync();assertNotNull(find(a.getWindow().getDecorView(),"Agregar a la cola"));assertTrue(find(a.getWindow().getDecorView(),"Agregar a la cola").isEnabled());capture("agregar");}
  finally{if(a!=null){Activity screen=a;getInstrumentation().runOnMainSync(screen::finish);}sessions.clear();LocalLibraryStorage.wipe(c,origin,user,device);}
 }
 private void capture(String name)throws Exception{getInstrumentation().waitForIdleSync();Thread.sleep(200);File folder=getInstrumentation().getTargetContext().getExternalFilesDir("ui-previews");folder.mkdirs();Bitmap image=getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
 private View find(View v,String label){if(v instanceof TextView t&&label.equals(t.getText().toString()))return v;if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){View match=find(g.getChildAt(i),label);if(match!=null)return match;}return null;}
 private View described(View v,String label){if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){View match=described(g.getChildAt(i),label);if(match!=null)return match;}return null;}
 private EditText input(View v){if(v instanceof EditText e)return e;if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){EditText match=input(g.getChildAt(i));if(match!=null)return match;}return null;}
}
