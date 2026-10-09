package dev.arglabs.tubego;
import android.app.Activity;import android.content.*;import android.test.InstrumentationTestCase;import android.view.*;import android.widget.*;import org.json.JSONObject;
@SuppressWarnings("deprecation")
public final class AdminCenterInstrumentedTest extends InstrumentationTestCase {
 public void testNormalUserCannotForceAnyAdministrativeScreen() throws Exception {
  Context context=getInstrumentation().getTargetContext();String origin="https://admin-ui.example.invalid";SessionStore sessions=new SessionStore(context,origin);
  sessions.save(new JSONObject().put("token","normal-test-session").put("status","approved").put("role","user").put("user_id","11111111-1111-4111-8111-111111111111").put("device_id","22222222-2222-4222-8222-222222222222"));
  try{for(Class<?> cls:new Class<?>[]{AdminCenterActivity.class,AdminUsersActivity.class,AdminRegistrationsActivity.class,AdminPriorityActivity.class,AdminRetentionActivity.class}){
   Activity activity=getInstrumentation().startActivitySync(new Intent(context,cls).putExtra("server_url",origin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));getInstrumentation().waitForIdleSync();
   assertTrue(cls.getName(),has(activity.getWindow().getDecorView(),"administrador"));assertEquals("No protected buttons",0,buttons(activity.getWindow().getDecorView()));getInstrumentation().runOnMainSync(activity::finish);
  }}finally{sessions.clear();}
 }
 public void testMainHidesAdministrativeNavigationForNormalUser() throws Exception {
  Context context=getInstrumentation().getTargetContext();String origin="https://admin-main.example.invalid";SessionStore sessions=new SessionStore(context,origin);android.content.SharedPreferences prefs=context.getSharedPreferences("server_connection",Context.MODE_PRIVATE);String previous=prefs.getString("server_url",null);
  sessions.save(new JSONObject().put("token","normal-main-session").put("status","approved").put("role","user").put("user_id","11111111-1111-4111-8111-111111111111").put("device_id","22222222-2222-4222-8222-222222222222"));prefs.edit().putString("server_url",origin).commit();
  Activity activity=null;try{activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));getInstrumentation().waitForIdleSync();View button=find(activity.getWindow().getDecorView(),"Administración");assertNotNull(button);assertEquals(View.GONE,button.getVisibility());assertNull(find(activity.getWindow().getDecorView(),"Administrar usuarios"));assertNull(find(activity.getWindow().getDecorView(),"Administrar prioridades"));}
  finally{if(activity!=null){Activity a=activity;getInstrumentation().runOnMainSync(a::finish);}sessions.clear();TransferJobs.stop(context,origin);if(previous==null)prefs.edit().remove("server_url").commit();else prefs.edit().putString("server_url",previous).commit();}
 }
 private boolean has(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(has(group.getChildAt(i),text))return true;}return false;}
 private View find(View view,String text){if(view instanceof TextView&&text.equals(((TextView)view).getText().toString()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View match=find(group.getChildAt(i),text);if(match!=null)return match;}}return null;}
 private int buttons(View view){int n=view instanceof Button?1:0;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)n+=buttons(group.getChildAt(i));}return n;}
}
