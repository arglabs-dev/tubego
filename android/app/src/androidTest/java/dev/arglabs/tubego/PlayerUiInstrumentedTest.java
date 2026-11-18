package dev.arglabs.tubego;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import org.json.JSONObject;

/** Real touch sequences against the isolated app; never against a user's production session. */
@SuppressWarnings("deprecation")
public final class PlayerUiInstrumentedTest extends PlaybackFixture {
    private PlayerControls controls(Activity a){return a.getWindow().getDecorView().findViewWithTag("playback_controls");}
    private View gestures(Activity a){return a.getWindow().getDecorView().findViewWithTag("playback_gestures");}
    private void touch(View target,int action,float x,float y,long down){
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);target.dispatchTouchEvent(event);event.recycle();
    }
    private void tap(Activity a,float fraction){getInstrumentation().runOnMainSync(()->{View target=gestures(a);long now=SystemClock.uptimeMillis();touch(target,0,target.getWidth()*fraction,target.getHeight()*.5f,now);touch(target,1,target.getWidth()*fraction,target.getHeight()*.5f,now);});}
    private void reveal(Activity a) throws Exception {if(!controls(a).toggle.isShown()){tap(a,.5f);Thread.sleep(400);}assertTrue(controls(a).toggle.isShown());}
    private void clickText(String source) throws Exception {
        String label=Texts.text(app,source);AccessibilityNodeInfo target=null;long until=SystemClock.elapsedRealtime()+3000;
        while(target==null&&SystemClock.elapsedRealtime()<until){AccessibilityNodeInfo root=getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null&&app.getPackageName().contentEquals(root.getPackageName()))for(AccessibilityNodeInfo candidate:root.findAccessibilityNodeInfosByText(label))if(candidate.getText()!=null&&label.equalsIgnoreCase(candidate.getText().toString())&&candidate.isVisibleToUser()){target=candidate;break;}if(target==null){if(root!=null)scrollForward(root);Thread.sleep(150);}}
        assertNotNull("Missing menu item: "+label,target);for(int i=0;i<5&&!target.isClickable();i++){target=target.getParent();assertNotNull(target);}
        assertTrue("Menu item not clickable: "+label,target.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(250);
    }
    private boolean scrollForward(AccessibilityNodeInfo node){
        if(node.isScrollable()&&node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null&&scrollForward(child))return true;}return false;
    }
    private void menu(Activity a) throws Exception {reveal(a);getInstrumentation().runOnMainSync(()->a.getWindow().getDecorView().findViewWithTag("playback_more").performClick());Thread.sleep(1000);}
    private void screenshot(String name) throws Exception {Bitmap bitmap=getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);try(FileOutputStream out=new FileOutputStream(new File(app.getFilesDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
    public void testOverlayAutoHideAndBothOrientations() throws Exception {
        reset();Activity a=open();try {
            waitForPosition(4000,15000);assertFalse("Playing overlay must auto-hide",controls(a).toggle.isShown());reveal(a);
            getInstrumentation().runOnMainSync(()->controls(a).toggle.performClick());Thread.sleep(500);assertTrue(controls(a).toggle.isShown());
            getInstrumentation().runOnMainSync(()->a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));Thread.sleep(1000);assertFalse(a.isDestroyed());screenshot("player-vlc-landscape.png");menu(a);screenshot("player-vlc-options.png");getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);Thread.sleep(250);
            long before=saved().getLong("position_ms");getInstrumentation().runOnMainSync(()->a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));Thread.sleep(1000);assertFalse(a.isDestroyed());assertEquals(before,saved().getLong("position_ms"));screenshot("player-vlc-portrait.png");
        }finally{getInstrumentation().runOnMainSync(a::finish);}
    }
    public void testRotationWhilePlayingKeepsPositionAndActivity() throws Exception {
        reset();Activity a=open();try{JSONObject before=waitForPosition(12000,25000);
            int orientation=a.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            getInstrumentation().runOnMainSync(()->a.setRequestedOrientation(orientation));Thread.sleep(6000);
            assertFalse(a.isDestroyed());assertTrue("Rotation must continue without rewind",saved().getLong("position_ms")>before.getLong("position_ms"));
        }finally{getInstrumentation().runOnMainSync(a::finish);}
    }
    public void testDoubleTripleTapAndHoldRestoreSpeed() throws Exception {
        reset();Activity a=open();try {
            waitForPosition(4000,15000);tap(a,.9f);Thread.sleep(120);tap(a,.9f);Thread.sleep(80);
            TextView hud=a.getWindow().getDecorView().findViewWithTag("playback_hud_value");assertEquals("+0:10",hud.getText().toString());
            Thread.sleep(80);tap(a,.9f);Thread.sleep(80);assertEquals("+0:20",hud.getText().toString());
            Thread.sleep(900);getInstrumentation().runOnMainSync(()->{View target=gestures(a);long now=SystemClock.uptimeMillis();touch(target,0,target.getWidth()*.5f,target.getHeight()*.5f,now);});Thread.sleep(700);assertEquals("2.00×",hud.getText().toString());
            assertEquals(2f,playbackRate(),.01f);
            getInstrumentation().runOnMainSync(()->{View target=gestures(a);touch(target,1,target.getWidth()*.5f,target.getHeight()*.5f,SystemClock.uptimeMillis()-700);});Thread.sleep(700);assertEquals(1f,playbackRate(),.01f);
            assertEquals("in_progress",PlaybackState.status(saved()));
        }finally{getInstrumentation().runOnMainSync(a::finish);}
    }
    private float playbackRate(){
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
        try{for(android.media.session.MediaController c:app.getSystemService(android.media.session.MediaSessionManager.class).getActiveSessions(null))if(app.getPackageName().equals(c.getPackageName()))return c.getPlaybackState().getPlaybackSpeed();throw new AssertionError("No own media session");}
        finally{getInstrumentation().getUiAutomation().dropShellPermissionIdentity();}
    }
    public void testControlLockBlocksGesturesUntilSliding() throws Exception {
        reset();Activity a=open();android.media.AudioManager audio=app.getSystemService(android.media.AudioManager.class);int original=audio.getStreamVolume(3);
        try{waitForPosition(4000,15000);menu(a);clickText("Bloquear");assertTrue(controls(a).locked());assertFalse(controls(a).toggle.isShown());
            getInstrumentation().runOnMainSync(()->{View target=gestures(a);long now=SystemClock.uptimeMillis();float x=target.getWidth()*.9f;touch(target,0,x,target.getHeight()*.7f,now);touch(target,2,x,target.getHeight()*.2f,now);touch(target,1,x,target.getHeight()*.2f,now);});assertEquals(original,audio.getStreamVolume(3));assertTrue(controls(a).locked());
            tap(a,.5f);Thread.sleep(200);assertTrue(controls(a).unlock.isShown());
            getInstrumentation().runOnMainSync(()->{SeekBar slider=controls(a).unlock;long now=SystemClock.uptimeMillis();touch(slider,0,slider.getWidth()*.1f,slider.getHeight()/2f,now);touch(slider,2,slider.getWidth()*.99f,slider.getHeight()/2f,now);touch(slider,1,slider.getWidth()*.99f,slider.getHeight()/2f,now);});
            assertFalse(controls(a).locked());assertTrue(controls(a).toggle.isShown());
        }finally{audio.setStreamVolume(3,original,0);getInstrumentation().runOnMainSync(a::finish);}
    }
    public void testPictureInPictureKeepsCheckpoints() throws Exception {
        reset();Activity a=open();try{JSONObject before=waitForPosition(4000,15000);menu(a);clickText("Reproductor miniatura");
            long until=SystemClock.elapsedRealtime()+4000;while(!a.isInPictureInPictureMode()&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);assertTrue("PiP must be granted on test device",a.isInPictureInPictureMode());
            Thread.sleep(6000);assertTrue(saved().getLong("position_ms")>before.getLong("position_ms")+2000);assertFalse(controls(a).toggle.isShown());
        }finally{getInstrumentation().runOnMainSync(a::finish);}
    }
    public void testPinchChangesVideoFitAndSettingsAreFunctional() throws Exception {
        reset();Activity a=open();try{waitForPosition(4000,15000);
            getInstrumentation().runOnMainSync(()->pinch(gestures(a)));Thread.sleep(200);
            TextView hud=a.getWindow().getDecorView().findViewWithTag("playback_hud_value");assertEquals(Texts.text(app,"Ajustar a pantalla"),hud.getText().toString());
            menu(a);clickText("Configuración de controles");clickText("Gesto de volumen");assertFalse(app.getSharedPreferences("player_controls",0).getBoolean("volume",true));clickText("Aceptar");
        }finally{app.getSharedPreferences("player_controls",0).edit().putBoolean("volume",true).commit();getInstrumentation().runOnMainSync(a::finish);}
    }
    private void pinch(View view){
        long now=SystemClock.uptimeMillis();MotionEvent.PointerProperties[] props={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};for(int i=0;i<2;i++){props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;}
        MotionEvent.PointerCoords[] points={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};for(MotionEvent.PointerCoords p:points){p.y=view.getHeight()*.5f;p.pressure=1;p.size=1;}
        float center=view.getWidth()*.5f;int[] actions={0,5|(1<<8),2,2,2,2,6|(1<<8),1};float w=view.getWidth();float[] spans={w*.04f,w*.04f,w*.12f,w*.22f,w*.34f,w*.42f,w*.42f,w*.42f};
        for(int step=0;step<actions.length;step++){points[0].x=center-spans[step];points[1].x=center+spans[step];int count=step==0||step==actions.length-1?1:2;MotionEvent event=MotionEvent.obtain(now,now+step*50,actions[step],count,props,points,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);view.dispatchTouchEvent(event);event.recycle();}
    }
}
