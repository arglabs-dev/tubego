package dev.arglabs.tubego;
import android.app.Activity;import android.content.*;import android.test.InstrumentationTestCase;import android.view.*;import android.widget.TextView;import org.json.JSONObject;import java.io.File;import java.util.*;
@SuppressWarnings("deprecation")
public final class HistoryLibraryInstrumentedTest extends InstrumentationTestCase {
 public void testHistoryWithoutAnyMediaIsVisibleOfflineAndPrivateToAccount() throws Exception {
  Context context=getInstrumentation().getTargetContext();String origin="https://history.example.invalid",user=UUID.randomUUID().toString(),device=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();SessionStore session=new SessionStore(context,origin);
  File root=LocalLibraryStorage.root(context,origin,user,device);root.mkdirs();JSONObject saved=new JSONObject().put("id",id).put("title","Offline permanent lesson").put("source_url","https://video.example.invalid/lesson").put("created_at","2026-01-01").put("server_deleted_at","gone").put("server_available",false);
  new HistoryCache(root).merge(Collections.singletonMap(id,saved.toString()));session.save(new JSONObject().put("token","offline-history-session").put("status","approved").put("user_id",user).put("device_id",device));
  Activity first=null,second=null;
  try {
   first=getInstrumentation().startActivitySync(new Intent(context,LocalLibraryActivity.class).putExtra("server_url",origin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity activity=first;
   boolean found=false;for(int i=0;i<100&&!found;i++){getInstrumentation().waitForIdleSync();found=contains(activity.getWindow().getDecorView(),"Offline permanent lesson");if(!found)Thread.sleep(50);}
   assertTrue("Saved metadata must render before network timeout and without a media file",found);assertFalse(new File(root,id+".media").exists());
   getInstrumentation().runOnMainSync(first::finish);
   session.save(new JSONObject().put("token","other-account-session").put("status","approved").put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString()));
   second=getInstrumentation().startActivitySync(new Intent(context,LocalLibraryActivity.class).putExtra("server_url",origin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));getInstrumentation().waitForIdleSync();assertFalse(contains(second.getWindow().getDecorView(),"Offline permanent lesson"));
  } finally {if(first!=null){Activity a=first;getInstrumentation().runOnMainSync(a::finish);}if(second!=null){Activity a=second;getInstrumentation().runOnMainSync(a::finish);}session.clear();LocalLibraryStorage.wipe(context,origin,user,device);}
 }
 private boolean contains(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),text))return true;}return false;}
}
