package dev.arglabs.tubego;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Read-only regression: rendering must not wait for background session operations. */
@SuppressWarnings("deprecation")
public final class LibraryResponsivenessInstrumentedTest extends InstrumentationTestCase {
 public void testQualityLabelsDoNotWaitForSessionLock() throws Exception {
  android.content.Context context=getInstrumentation().getTargetContext();
  String origin=LanguagePreferences.origin(context);
  Activity activity=getInstrumentation().startActivitySync(new Intent(context,LibraryActivity.class)
    .putExtra("server_url",origin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try {
   assertTrue("Requires an existing approved account",activity instanceof LibraryActivity && !activity.isFinishing());
   CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
   Thread holder=new Thread(()->{synchronized(SessionStore.class){locked.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}});
   holder.start(); assertTrue(locked.await(5,TimeUnit.SECONDS));
   long[] elapsed={0};String[] label={null};
   try {getInstrumentation().runOnMainSync(()->{long start=SystemClock.elapsedRealtime();for(int i=0;i<20;i++)label[0]=Texts.quality(activity,"480");elapsed[0]=SystemClock.elapsedRealtime()-start;});}
   finally {release.countDown();holder.join(5000);}
   assertEquals(((DsActivity)activity).t("Video 480p"),label[0]);
   assertTrue("Rendering waited for session lock: "+elapsed[0]+"ms",elapsed[0]<500);
  } finally {getInstrumentation().runOnMainSync(activity::finish);}
 }
}
