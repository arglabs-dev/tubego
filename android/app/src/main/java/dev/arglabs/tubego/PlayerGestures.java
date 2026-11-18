package dev.arglabs.tubego;

import android.content.Context;
import android.view.*;

/** Axis-locked gestures, accumulated double taps and pinch. Own implementation. */
final class PlayerGestures implements View.OnTouchListener {
    interface Actions {
        long position();long duration();boolean locked();boolean enabled(String key);
        void tap();void playPause();void unlock();void seek(long position);void preview(long delta,long target);void finishPreview();
        void beginLevels();void brightness(float dy,float height);void volume(float dy,float height);void zoom(boolean fill);void fast(boolean enabled);
    }
    private final Actions actions;
    private final GestureDetector taps;
    private final ScaleGestureDetector pinch;
    private final float slop,referenceWidth;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean holding,down;
    private final Runnable hold;
    private float x,y,width,height,pinchScale;
    private long anchor,burstTarget,lastBurst;
    private int axis,burstSide,burstCount;
    private boolean moved,multiple,doubleHandled;
    PlayerGestures(Context context,Actions actions){
        this.actions=actions;hold=()->{if(down&&!moved&&!multiple&&!this.actions.locked()&&this.actions.enabled("hold_speed")){holding=true;burstSide=0;lastBurst=0;this.actions.fast(true);}};slop=ViewConfiguration.get(context).getScaledTouchSlop()*1.5f;referenceWidth=context.getResources().getDisplayMetrics().xdpi/2.54f*8;
        taps=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent e){if(android.os.SystemClock.uptimeMillis()-lastBurst<650)return true;if(actions.locked())actions.unlock();else actions.tap();return true;}
            @Override public boolean onDoubleTap(MotionEvent e){
                if(actions.locked()){actions.unlock();return true;}
                if(!actions.enabled("double_tap"))return true;
                int side=side(e.getX());if(side==0){actions.playPause();return true;}
                doubleHandled=true;long now=android.os.SystemClock.uptimeMillis();boolean continuing=side==burstSide&&now-lastBurst<650;long base=continuing?burstTarget:actions.position();burstCount=continuing?burstCount+1:1;burstSide=side;burstTarget=PlayerGestureMath.target(base,side*10000,actions.duration());lastBurst=now;actions.seek(burstTarget);actions.preview(side*burstCount*10000L,burstTarget);actions.finishPreview();return true;
            }
        });
        taps.setIsLongpressEnabled(false);
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector detector){if(actions.locked()||!actions.enabled("pinch_scale"))return false;multiple=true;pinchScale=1;return true;}
            @Override public boolean onScale(ScaleGestureDetector detector){pinchScale*=detector.getScaleFactor();return true;}
            @Override public void onScaleEnd(ScaleGestureDetector detector){if(pinchScale>1.03f)actions.zoom(true);else if(pinchScale<.97f)actions.zoom(false);}
        });pinch.setQuickScaleEnabled(false);
    }
    void cancel(){down=false;handler.removeCallbacks(hold);if(holding){holding=false;actions.fast(false);}long now=android.os.SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);taps.onTouchEvent(event);event.recycle();}
    private int side(float value){return value<width*.25f?-1:value>width*.75f?1:0;}
    @Override public boolean onTouch(View view,MotionEvent event){
        width=view.getWidth();height=view.getHeight();int type=event.getActionMasked();if(type==MotionEvent.ACTION_UP||type==MotionEvent.ACTION_CANCEL)down=false;
        if(type==MotionEvent.ACTION_DOWN){down=true;x=event.getX();y=event.getY();anchor=actions.position();axis=0;moved=false;multiple=false;doubleHandled=false;actions.beginLevels();handler.removeCallbacks(hold);handler.postDelayed(hold,250);}
        if(actions.locked()){if(type==MotionEvent.ACTION_UP){actions.unlock();view.performClick();}return true;}
        if(holding){if(type==MotionEvent.ACTION_UP||type==MotionEvent.ACTION_CANCEL){cancel();actions.finishPreview();}return true;}
        pinch.onTouchEvent(event);
        if(event.getPointerCount()>1){multiple=true;lastBurst=0;handler.removeCallbacks(hold);if(type==MotionEvent.ACTION_POINTER_DOWN){MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);taps.onTouchEvent(cancel);cancel.recycle();}}
        if(!multiple)taps.onTouchEvent(event);
        if(multiple){if(type==MotionEvent.ACTION_UP||type==MotionEvent.ACTION_CANCEL)actions.finishPreview();return true;}
        float dx=event.getX()-x,dy=y-event.getY();
        if(type==MotionEvent.ACTION_MOVE){
            if(axis==0&&Math.max(Math.abs(dx),Math.abs(dy))>slop){axis=Math.abs(dx)>=Math.abs(dy)?1:x<width*.5f?2:3;moved=true;lastBurst=0;handler.removeCallbacks(hold);}
            if(axis==1){long delta=PlayerGestureMath.seekDelta(dx,referenceWidth,dy,height);if(delta!=0)actions.preview(delta,PlayerGestureMath.target(anchor,delta,actions.duration()));}
            else if(axis==2&&actions.enabled("brightness"))actions.brightness(dy,height);
            else if(axis==3&&actions.enabled("volume"))actions.volume(dy,height);
        }else if(type==MotionEvent.ACTION_UP){
            handler.removeCallbacks(hold);
            if(axis==1&&Math.abs(dx)>slop){long delta=PlayerGestureMath.seekDelta(dx,referenceWidth,dy,height);if(delta!=0)actions.seek(PlayerGestureMath.target(anchor,delta,actions.duration()));}
            else if(!moved&&!doubleHandled&&burstSide!=0&&android.os.SystemClock.uptimeMillis()-lastBurst<650&&side(event.getX())==burstSide){burstTarget=PlayerGestureMath.target(burstTarget,burstSide*10000,actions.duration());lastBurst=android.os.SystemClock.uptimeMillis();actions.seek(burstTarget);burstCount++;actions.preview(burstSide*burstCount*10000L,burstTarget);}
            actions.finishPreview();view.performClick();
        }else if(type==MotionEvent.ACTION_CANCEL){handler.removeCallbacks(hold);actions.finishPreview();}
        return true;
    }
}
