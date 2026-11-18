package dev.arglabs.tubego;

/** Independent gesture mapping. Preview never changes playback until release. */
public final class PlayerGestureMath {
    private PlayerGestureMath(){}
    public static long seekDelta(float dx,float width,float verticalOffset,float height){
        if(width<=0||height<=0||Math.abs(dx)<1)return 0;
        if(Math.abs(dx)<width/8)return 0; // Ignore motion shorter than one physical centimetre.
        double distance=Math.min(1,Math.abs(dx)/width);
        double precision=Math.abs(verticalOffset)>height*.25?.2:1;
        long delta=Math.max(1000,Math.round((3000+600000*Math.pow(distance,4))*precision));
        return dx<0?-delta:delta;
    }
    public static long target(long start,long delta,long duration){return Math.max(0,Math.min(Math.max(0,duration-1),start+delta));}
    public static float brightness(float start,float dy,float height){return Math.max(.01f,Math.min(1f,start+dy/Math.max(1,height)));}
    public static int volume(int start,float dy,float height,int maximum){return Math.max(0,Math.min(maximum,start+Math.round(dy/Math.max(1,height)*maximum)));}
}
