package dev.arglabs.tubego;
import android.app.Activity;
import java.io.File;
import android.widget.ImageButton;
import android.widget.SeekBar;
import org.json.JSONObject;
@SuppressWarnings("deprecation")
public final class PlaybackInstrumentedTest extends PlaybackFixture {
    public void testNativeCheckpointSeekPauseAndRewind() throws Exception {
        reset();Activity activity=open();
        try {
            JSONObject before=waitForPosition(12000,24000);assertTrue(before.getJSONArray("ranges").length()>0);
            getInstrumentation().runOnMainSync(()->((ImageButton)activity.getWindow().getDecorView().findViewWithTag("playback_toggle")).performClick());
            Thread.sleep(800);long paused=saved().getLong("position_ms");assertTrue(paused>=12000);
            getInstrumentation().runOnMainSync(activity::finish);Thread.sleep(700);
            Activity resumed=open();
            try {
                long oldStamp=saved().optLong("position_at_ms"),until=System.currentTimeMillis()+10000;
                JSONObject rewind=saved();while(rewind.optLong("position_at_ms")<=oldStamp&&System.currentTimeMillis()<until){Thread.sleep(250);rewind=saved();}
                assertTrue("No new checkpoint after reopening",rewind.optLong("position_at_ms")>oldStamp);
                assertTrue("Rewind must lower position instead of keeping maximum",rewind.getLong("position_ms")<paused-3000);
                getInstrumentation().runOnMainSync(()->{
                    SeekBar bar=resumed.getWindow().getDecorView().findViewWithTag("playback_seek");
                    long now=android.os.SystemClock.uptimeMillis();float x=bar.getWidth()*.98f,y=bar.getHeight()/2f;
                    android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,0,x,y,0),up=android.view.MotionEvent.obtain(now,now+100,1,x,y,0);
                    bar.dispatchTouchEvent(down);bar.dispatchTouchEvent(up);down.recycle();up.recycle();
                });
                Thread.sleep(4000);JSONObject end=saved();assertTrue(end.optBoolean("ended"));assertEquals("in_progress",PlaybackState.status(end));
            }finally{getInstrumentation().runOnMainSync(resumed::finish);}
        } finally {getInstrumentation().runOnMainSync(activity::finish);}
    }
    private void swipe(android.view.View view,float x1,float y1,float x2,float y2) {
        long now=android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,0,x1,y1,0),move=android.view.MotionEvent.obtain(now,now+200,2,x2,y2,0),up=android.view.MotionEvent.obtain(now,now+400,1,x2,y2,0);
        view.dispatchTouchEvent(down);view.dispatchTouchEvent(move);view.dispatchTouchEvent(up);down.recycle();move.recycle();up.recycle();
    }
    public void testNativeGesturesBrightnessVolumeAndSeek() throws Exception {
        reset();Activity activity=open();android.media.AudioManager audio=app.getSystemService(android.media.AudioManager.class);int original=audio.getStreamVolume(3);
        try {
            waitForPosition(4000,15000);
            getInstrumentation().runOnMainSync(()->{
                android.view.View gestures=activity.getWindow().getDecorView().findViewWithTag("playback_gestures");float w=gestures.getWidth(),h=gestures.getHeight();
                swipe(gestures,w*.2f,h*.6f,w*.2f,h*.2f);assertTrue(activity.getWindow().getAttributes().screenBrightness>.5f);
                boolean lower=original==audio.getStreamMaxVolume(3);swipe(gestures,w*.8f,lower?h*.2f:h*.6f,w*.8f,lower?h*.6f:h*.2f);
                assertTrue(audio.getStreamVolume(3)!=original);
                swipe(gestures,w*.2f,h*.5f,w*.8f,h*.5f);
            });
            Thread.sleep(6000);assertTrue(saved().optBoolean("ended")||saved().optLong("position_ms")>7000);assertEquals("in_progress",PlaybackState.status(saved()));
        }finally{audio.setStreamVolume(3,original,0);getInstrumentation().runOnMainSync(activity::finish);}
    }
    public void testNativeNaturalEndNeedsCoverage() throws Exception {
        reset();Activity activity=open();
        try {long until=System.currentTimeMillis()+55000;while(!saved().optBoolean("ended")&&System.currentTimeMillis()<until)Thread.sleep(500);
            JSONObject end=saved();assertTrue("Native EndReached not persisted",end.optBoolean("ended"));assertEquals("finished",PlaybackState.status(end));
        } finally {getInstrumentation().runOnMainSync(activity::finish);}
    }
    public void testAccountSwitchStopsAndDoesNotWriteIntoOtherAccount() throws Exception {
        reset();Activity activity=open();
        try {waitForPosition(4000,15000);
            new SessionStore(app,origin).save(new JSONObject().put("token","other-session").put("status","approved").put("user_id","44444444-4444-4444-8444-444444444444").put("device_id",device));
            Thread.sleep(1500);assertTrue(activity.isFinishing()||activity.isDestroyed());
            File other=LocalLibraryStorage.root(app,origin,"44444444-4444-4444-8444-444444444444",device);assertEquals(0,new PlaybackStore(other).read(id).getLong("position_ms"));
        } finally {getInstrumentation().runOnMainSync(activity::finish);}
    }
}
