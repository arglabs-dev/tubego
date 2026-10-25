package dev.arglabs.tubego;
import android.app.Activity;import android.content.*;import android.test.InstrumentationTestCase;import android.view.*;import java.io.File;import java.util.UUID;import org.json.JSONObject;
@SuppressWarnings("deprecation")
public final class OfflineControlsInstrumentedTest extends InstrumentationTestCase {
 public void testStorageAndAlertsAvailableBeforeNetworkAndFencedAfterSessionChange()throws Exception{
  Context context=getInstrumentation().getTargetContext();String origin="https://offline-controls.example.invalid",user=UUID.randomUUID().toString(),device=UUID.randomUUID().toString();SessionStore store=new SessionStore(context,origin);File root=LocalLibraryStorage.root(context,origin,user,device);
  store.save(new JSONObject().put("token","offline-controls-token").put("status","approved").put("role","user").put("user_id",user).put("device_id",device));
  new LocalMediaPreferences(root).save(new JSONObject().put("selection","720").put("ask_every_time",true).put("rewind_seconds",10));Activity activity=null;
  try{
   activity=getInstrumentation().startActivitySync(new Intent(context,NetworkPolicyActivity.class).putExtra("server_url",origin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));getInstrumentation().waitForIdleSync();
   Activity screen=activity;View storage=find(screen.getWindow().getDecorView(),"local-storage");assertNotNull("Offline local controls must exist without a server response",storage);assertNotNull(find(screen.getWindow().getDecorView(),"local-alerts"));
   store.save(new JSONObject().put("token","different-account-token").put("status","approved").put("role","user").put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString()));
   getInstrumentation().runOnMainSync(storage::performClick);getInstrumentation().waitForIdleSync();assertNull("A stale account callback must remove its controls",find(screen.getWindow().getDecorView(),"local-storage"));
  }finally{if(activity!=null){Activity screen=activity;getInstrumentation().runOnMainSync(screen::finish);}store.clear();LocalLibraryStorage.wipe(context,origin,user,device);}
 }
 private View find(View view,String tag){if(tag.equals(view.getTag()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),tag);if(result!=null)return result;}}return null;}
}
