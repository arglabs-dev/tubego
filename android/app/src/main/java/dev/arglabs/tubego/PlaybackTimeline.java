package dev.arglabs.tubego;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Observed continuous playback only. Seeks, pauses and long sampling gaps break coverage. */
public final class PlaybackTimeline {
    private final List<long[]> intervals = new ArrayList<>();
    private long previous = -1, previousTick, seekTarget = -1;
    private boolean wasPlaying;
    public long position, duration;
    public boolean ended;

    public PlaybackTimeline(long position, long duration, List<long[]> ranges, boolean ended) {
        this.position=position;this.duration=duration;this.ended=ended;
        for(long[] range:ranges) add(range[0],range[1]);
    }
    public void discontinuity() { previous=-1;wasPlaying=false; }
    public void seeking(long target) { discontinuity();seekTarget=target; }
    public void sample(long value,long length,boolean playing,long tick) {sample(value,length,playing,tick,1f);}
    public void sample(long value,long length,boolean playing,long tick,float rate) {
        if(length<=0 || value<0 || value>length) {discontinuity();return;}
        if(seekTarget>=0) {
            if(Math.abs(value-seekTarget)>1500) {discontinuity();return;}
            seekTarget=-1;discontinuity();
        }
        position=value;duration=length;
        long elapsed=tick-previousTick,delta=value-previous;
        if(previous>=0 && playing && wasPlaying && elapsed>0 && elapsed<=2500
                && delta>0 && delta<=elapsed*Math.max(.25f,Math.min(4f,rate))*1.1+1000) add(previous,value);
        // LibVLC time can repeat between native updates; do not reset the clock on duplicates.
        if(value!=previous || playing!=wasPlaying)previousTick=tick;
        previous=value;wasPlaying=playing;
    }
    public void endReached(){ended=true;seekTarget=-1;discontinuity();}
    public boolean isSeeking(){return seekTarget>=0;}
    private void add(long start,long end) {
        if(start<0 || end<=start)return;
        intervals.add(new long[]{start,end});intervals.sort(Comparator.comparingLong(r->r[0]));
        for(int i=1;i<intervals.size();) {
            long[] a=intervals.get(i-1),b=intervals.get(i);
            if(b[0]<=a[1]) {a[1]=Math.max(a[1],b[1]);intervals.remove(i);} else i++;
        }
        if(intervals.size()>4096) {
            int smallest=0;for(int i=1;i<intervals.size();i++)if(intervals.get(i)[1]-intervals.get(i)[0]<intervals.get(smallest)[1]-intervals.get(smallest)[0])smallest=i;
            intervals.remove(smallest);
        }
    }
    public List<long[]> ranges() {List<long[]> result=new ArrayList<>();for(long[] r:intervals)result.add(r.clone());return result;}
    public static long resume(long position,int seconds) {return Math.max(0,position-Math.max(0,Math.min(120,seconds))*1000L);}
}
