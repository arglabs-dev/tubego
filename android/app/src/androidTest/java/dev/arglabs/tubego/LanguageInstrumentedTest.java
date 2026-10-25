package dev.arglabs.tubego;
import android.app.Activity;import android.content.*;import android.test.InstrumentationTestCase;import android.view.*;import android.widget.*;import org.json.JSONObject;
@SuppressWarnings("deprecation")
public final class LanguageInstrumentedTest extends InstrumentationTestCase {
 private final String origin="https://language-test.example.invalid";
 private JSONObject account(String user)throws Exception{return new JSONObject().put("token","language-"+user).put("status","approved").put("role","user").put("user_id",user).put("device_id","33333333-3333-4333-8333-333333333333");}
 public void testOfflinePreferenceIsolationAndStaleAcknowledgement()throws Exception{
  Context context=getInstrumentation().getTargetContext();SessionStore sessions=new SessionStore(context,origin);context.getSharedPreferences("tubego_language",Context.MODE_PRIVATE).edit().clear().commit();
  try{sessions.save(account("language-a"));JSONObject first=LanguagePreferences.saveLocal(context,origin,"es"),second=LanguagePreferences.saveLocal(context,origin,"en");
   LanguagePreferences.acknowledge(context,origin,"language-a",first.getLong("revision"));assertNotNull(LanguagePreferences.pending(context,origin));assertEquals("en",LanguagePreferences.language(context,origin));
   sessions.clear();sessions.save(account("language-b"));assertEquals(LanguageDefaults.system(android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage()),LanguagePreferences.language(context,origin));assertNull(LanguagePreferences.pending(context,origin));
   LanguagePreferences.saveLocal(context,origin,"es");sessions.clear();sessions.save(account("language-a"));assertEquals("en",LanguagePreferences.language(context,origin));assertEquals(second.getLong("revision"),LanguagePreferences.pending(context,origin).getLong("revision"));
   LanguagePreferences.acknowledge(context,origin,"language-a",second.getLong("revision"));assertNull(LanguagePreferences.pending(context,origin));
  }finally{sessions.clear();context.getSharedPreferences("tubego_language",Context.MODE_PRIVATE).edit().clear().commit();}
 }
 public void testOldRemoteReadCannotOverwriteAcknowledgedNewLocalLanguage()throws Exception{
  Context context=getInstrumentation().getTargetContext();SessionStore sessions=new SessionStore(context,origin);sessions.save(account("language-race"));
  try{JSONObject old=LanguagePreferences.saveLocal(context,origin,"es");LanguagePreferences.acknowledge(context,origin,"language-race",old.getLong("revision"));
   JSONObject next=LanguagePreferences.saveLocal(context,origin,"en");LanguagePreferences.acknowledge(context,origin,"language-race",next.getLong("revision"));
   assertNull(LanguagePreferences.pending(context,origin));assertFalse(LanguagePreferences.applyRemote(context,origin,"language-language-race","language-race",old.getLong("revision"),"es"));assertEquals("en",LanguagePreferences.language(context,origin));
   assertTrue(LanguagePreferences.applyRemote(context,origin,"language-language-race","language-race",next.getLong("revision"),"es"));assertEquals("es",LanguagePreferences.language(context,origin));
  }finally{sessions.clear();context.getSharedPreferences("tubego_language",Context.MODE_PRIVATE).edit().clear().commit();}
 }
 public void testHelpRendersSavedEnglishAndSpanishWithoutNetwork()throws Exception{
  Context context=getInstrumentation().getTargetContext();SessionStore sessions=new SessionStore(context,origin);sessions.save(account("language-render"));
  try{for(String language:new String[]{"en","es"}){
   LanguagePreferences.saveLocal(context,origin,language);Activity activity=getInstrumentation().startActivitySync(new Intent(context,HelpActivity.class).putExtra("server_url",origin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));getInstrumentation().waitForIdleSync();
   assertTrue(find(activity.getWindow().getDecorView(),language.equals("es")?"Ayuda de Tubego":"Tubego help"));assertTrue(find(activity.getWindow().getDecorView(),language.equals("es")?"Idioma":"Language"));
   assertEquals(language.equals("es")?"Aprobado":"Approved",Texts.state(activity,"approved"));getInstrumentation().runOnMainSync(activity::finish);
  }}finally{sessions.clear();context.getSharedPreferences("tubego_language",Context.MODE_PRIVATE).edit().clear().commit();}
 }
 private boolean find(View view,String text){if(view instanceof TextView&&text.equals(((TextView)view).getText().toString()))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(find(group.getChildAt(i),text))return true;}return false;}
}
