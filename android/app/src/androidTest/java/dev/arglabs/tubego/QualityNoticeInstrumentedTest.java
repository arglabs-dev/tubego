package dev.arglabs.tubego;
import android.app.Activity;import android.content.*;import android.test.InstrumentationTestCase;import android.view.*;import android.widget.*;import org.json.JSONObject;import java.util.*;import java.io.File;
@SuppressWarnings("deprecation")
public final class QualityNoticeInstrumentedTest extends InstrumentationTestCase {
 public void testCachedHistoryDisplaysLowerAndUnknownQualityOffline()throws Exception{
  Context context=getInstrumentation().getTargetContext();String origin="https://quality-notice.example.invalid",user=UUID.randomUUID().toString(),device=UUID.randomUUID().toString();SessionStore session=new SessionStore(context,origin);File root=LocalLibraryStorage.root(context,origin,user,device);root.mkdirs();
  Map<String,String> items=new LinkedHashMap<>();for(String code:new String[]{"lower_quality_available","quality_unknown"}){String id=UUID.randomUUID().toString();JSONObject task=new JSONObject().put("status","completed").put("phase","ready").put("quality_notice",code);items.put(id,new JSONObject().put("id",id).put("title","Cached lesson "+code).put("media_format","video").put("quality","1080").put("server_available",false).put("latest_task",task).toString());}
  new HistoryCache(root).merge(items);session.save(new JSONObject().put("token","quality-notice-fixture").put("status","approved").put("user_id",user).put("device_id",device));LanguagePreferences.saveLocal(context,origin,"en");Activity activity=null;
  try{activity=getInstrumentation().startActivitySync(new Intent(context,LocalLibraryActivity.class).putExtra("server_url",origin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));boolean found=false;for(int i=0;i<100&&!found;i++){getInstrumentation().waitForIdleSync();found=contains(activity.getWindow().getDecorView(),"quality lower than requested")&&contains(activity.getWindow().getDecorView(),"resolution has not been confirmed");if(!found)Thread.sleep(50);}assertTrue("Both cached quality notices must render without media or server access",found);assertTrue(contains(activity.getWindow().getDecorView(),"1080p video"));}
  finally{if(activity!=null){Activity current=activity;getInstrumentation().runOnMainSync(current::finish);}session.clear();LocalLibraryStorage.wipe(context,origin,user,device);}
 }
 private boolean contains(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),text))return true;}return false;}
}
