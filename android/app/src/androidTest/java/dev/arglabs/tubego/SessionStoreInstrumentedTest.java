package dev.arglabs.tubego;

import android.test.InstrumentationTestCase;
import android.content.Context;
import org.json.JSONObject;

/** Exercises the real Android Keystore and persistent preferences on a device. */
@SuppressWarnings("deprecation")
public final class SessionStoreInstrumentedTest extends InstrumentationTestCase {
    public void testPersistentEncryptedSessionsAreIsolatedByServer() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        SessionStore first = new SessionStore(context, "https://first.example.com");
        SessionStore second = new SessionStore(context, "https://second.example.com");
        first.clear(); second.clear();
        try {
            first.save(new JSONObject().put("token", "first-instrumentation-token").put("user_id", "first-user"));
            second.save(new JSONObject().put("token", "second-instrumentation-token").put("user_id", "second-user"));
            assertEquals("first-instrumentation-token", new SessionStore(context, "https://first.example.com/").token());
            assertEquals("second-instrumentation-token", second.token());
            assertEquals("first-user", first.read().getString("user_id"));
            for (Object value : context.getSharedPreferences("tubego_sessions", Context.MODE_PRIVATE).getAll().values()) {
                assertFalse(String.valueOf(value).contains("instrumentation-token"));
            }
            first.clear();
            assertNull(new SessionStore(context, "https://first.example.com").read());
            assertEquals("second-instrumentation-token", second.token());
        } finally { first.clear(); second.clear(); }
    }
}
