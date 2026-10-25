package dev.arglabs.tubego;
import android.content.Context;
import android.test.InstrumentationTestCase;
import java.util.UUID;
import org.json.JSONObject;
@SuppressWarnings("deprecation")
public final class SessionResponseFenceInstrumentedTest extends InstrumentationTestCase {
 private JSONObject session(String token)throws Exception{return new JSONObject().put("token",token).put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString()).put("status","approved").put("role","user");}
 public void testStaleStatusCannotModifyNewAccountAndSameTokenRefreshPreservesEpoch()throws Exception{
  Context context=getInstrumentation().getTargetContext();String origin="https://session-fence.example.invalid";SessionStore store=new SessionStore(context,origin);store.clear();
  try{
   JSONObject first=session("first-token"),second=session("second-token");store.save(first);long epoch=store.generation();
   JSONObject admin=new JSONObject().put("status","approved").put("role","admin");
   assertTrue(store.updateAccountIfCurrent("first-token",epoch,admin));assertEquals(epoch,store.generation());
   store.save(second);assertFalse(store.updateAccountIfCurrent("first-token",epoch,admin));assertEquals("second-token",store.token());assertEquals("user",store.read().getString("role"));
   store.clear();assertFalse(store.updateAccountIfCurrent("second-token",store.generation()-1,admin));assertNull(store.read());
  }finally{store.clear();}
 }
 public void testLogoutWithEmptyCacheInvalidatesLateLoginAcrossStoreInstances()throws Exception{
  Context context=getInstrumentation().getTargetContext();String origin="https://login-fence.example.invalid";SessionStore store=new SessionStore(context,origin);store.clear();
  try{
   long pending=store.generation();SessionLifecycle.logout(context,origin);SessionStore reopened=new SessionStore(context,origin);
   assertTrue(reopened.generation()>pending);assertFalse(reopened.saveIfGeneration(session("late-empty-login"),pending));assertNull(reopened.read());
   long fresh=reopened.generation();JSONObject account=session("fresh-login");assertTrue(reopened.saveIfGeneration(account,fresh));assertEquals(fresh+1,reopened.generation());
   java.io.File directory=LocalLibraryStorage.root(context,origin,account.getString("user_id"),account.getString("device_id"));assertTrue(directory.mkdirs());java.io.File media=new java.io.File(directory,"download.part");assertTrue(media.createNewFile());
   long stale=reopened.generation();SessionLifecycle.logout(context,origin);assertFalse(store.saveIfGeneration(account,stale));assertNull(store.read());
   assertFalse("The late response must not recreate the wiped download directory",directory.exists());
  }finally{store.clear();store.clearPendingLogout();}
 }
}
