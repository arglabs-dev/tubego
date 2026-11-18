package dev.arglabs.tubego;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.util.List;
import static org.junit.Assert.*;

public class PlaybackTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private JSONObject snapshot(long position,long stamp) throws Exception {
        return PlaybackState.empty().put("position_ms",position).put("duration_ms",100000).put("position_at_ms",stamp);
    }
    @Test public void gesturePreviewIsBoundedFineAndNonlinear() {
        assertEquals(0,PlayerGestureMath.seekDelta(0,400,0,800));
        long small=PlayerGestureMath.seekDelta(100,400,0,800),large=PlayerGestureMath.seekDelta(200,400,0,800);
        assertTrue(large>small*4);assertEquals(-small,PlayerGestureMath.seekDelta(-100,400,0,800));
        assertTrue(PlayerGestureMath.seekDelta(100,400,300,800)<small);
        assertEquals(0,PlayerGestureMath.target(5000,-10000,45000));assertEquals(44999,PlayerGestureMath.target(5000,90000,45000));
        assertEquals(0,PlayerGestureMath.volume(1,-1000,500,15));assertEquals(15,PlayerGestureMath.volume(14,1000,500,15));
        assertEquals(.01f,PlayerGestureMath.brightness(.5f,-1000,500),.001f);assertEquals(1f,PlayerGestureMath.brightness(.5f,1000,500),.001f);
    }
    @Test public void acceleratedPlaybackCountsCoverageButSeeksStillDoNot() {
        PlaybackTimeline timeline=new PlaybackTimeline(0,100000,List.of(),false);
        timeline.sample(0,100000,true,0,4);timeline.sample(4000,100000,true,1000,4);
        timeline.sample(50000,100000,true,1500,4);timeline.sample(52000,100000,true,2000,4);
        assertEquals(2,timeline.ranges().size());assertArrayEquals(new long[]{0,4000},timeline.ranges().get(0));
        assertArrayEquals(new long[]{50000,52000},timeline.ranges().get(1));
    }
    @Test public void positionUsesLatestObservationNotMaximum() throws Exception {
        JSONObject high=snapshot(90000,1),low=snapshot(20000,2);
        assertEquals(20000,PlaybackState.merge(high,low).getLong("position_ms"));
        assertEquals(20000,PlaybackState.merge(low,high).getLong("position_ms"));
        assertEquals(10000,PlaybackTimeline.resume(20000,10));assertEquals(0,PlaybackTimeline.resume(5000,10));
    }
    @Test public void seekPauseAndSamplingGapsDoNotCreateCoverage() {
        PlaybackTimeline timeline=new PlaybackTimeline(0,100000,List.of(),false);
        timeline.sample(0,100000,true,0);timeline.sample(1000,100000,true,1000);
        timeline.sample(50000,100000,true,1500);timeline.sample(51000,100000,true,2500);
        timeline.sample(52000,100000,false,3500);timeline.sample(53000,100000,true,4500);
        timeline.sample(60000,100000,true,11500);
        assertEquals(2,timeline.ranges().size());assertArrayEquals(new long[]{0,1000},timeline.ranges().get(0));
        assertArrayEquals(new long[]{50000,51000},timeline.ranges().get(1));
    }
    @Test public void explicitlySeekingEvenAShortDistanceBreaksCoverage() {
        PlaybackTimeline timeline=new PlaybackTimeline(0,100000,List.of(),false);
        timeline.sample(10000,100000,true,0);timeline.seeking(10500);timeline.sample(10500,100000,true,500);
        assertTrue(timeline.ranges().isEmpty());timeline.sample(11000,100000,true,1000);
        assertArrayEquals(new long[]{10500,11000},timeline.ranges().get(0));
    }
    @Test public void pendingNativeSeekCannotOverwriteLastCheckpointWithZero() {
        PlaybackTimeline timeline=new PlaybackTimeline(90000,100000,List.of(),false);
        timeline.seeking(80000);timeline.sample(0,100000,true,1000);
        assertEquals(90000,timeline.position);assertTrue(timeline.isSeeking());
        timeline.sample(80000,100000,true,1500);assertEquals(80000,timeline.position);assertFalse(timeline.isSeeking());
    }
    @Test public void quantizedNativeTimeDoesNotCreateArtificialCoverageGaps() {
        PlaybackTimeline timeline=new PlaybackTimeline(0,100000,List.of(),false);
        for(int i=0;i<=20;i++)timeline.sample((i/2)*1000,100000,true,i*500);
        assertEquals(1,timeline.ranges().size());assertArrayEquals(new long[]{0,10000},timeline.ranges().get(0));
    }
    @Test public void endWhileSeekPendingIsRecordedWithoutInventingCoverage() throws Exception {
        PlaybackTimeline timeline=new PlaybackTimeline(10000,100000,List.of(),false);
        timeline.seeking(99999);timeline.endReached();
        assertFalse(timeline.isSeeking());assertTrue(timeline.ended);assertTrue(timeline.ranges().isEmpty());
        assertEquals("in_progress",PlaybackState.status(PlaybackState.checkpoint(timeline,PlaybackState.empty())));
    }
    @Test public void replayingTailDoesNotAccumulateWatchedCoverage() throws Exception {
        PlaybackTimeline timeline=new PlaybackTimeline(99000,100000,List.of(),true);
        for(int run=0;run<150;run++){timeline.discontinuity();timeline.sample(98000,100000,true,run*2000L);timeline.sample(99000,100000,true,run*2000L+1000);}
        JSONObject state=PlaybackState.checkpoint(timeline,PlaybackState.empty());
        assertEquals(1,state.getJSONArray("ranges").length());assertEquals("in_progress",PlaybackState.status(state));
    }
    @Test public void completionRequiresCoverageAndRealEndButManualWins() throws Exception {
        JSONObject state=snapshot(99000,1).put("ranges",new JSONArray().put(new JSONArray().put(0).put(96000)));
        assertEquals("in_progress",PlaybackState.status(state));state.put("ended",true);assertEquals("finished",PlaybackState.status(state));
        JSONObject unseen=PlaybackState.mark(state,false);assertEquals("in_progress",PlaybackState.status(PlaybackState.merge(unseen,state)));
        assertEquals("finished",PlaybackState.status(PlaybackState.mark(PlaybackState.empty(),true)));
    }
    @Test public void atomicStoreSurvivesReopeningAndLostReplyCannotEraseNewPendingState() throws Exception {
        String id="33333333-3333-4333-8333-333333333333";File account=temporary.newFolder();PlaybackStore first=new PlaybackStore(account);
        first.save(id,snapshot(10000,1),true);JSONObject sent=first.read(id);String old=sent.getString("dirty");
        PlaybackStore reopened=new PlaybackStore(account);reopened.save(id,snapshot(30000,3),true);String latest=reopened.read(id).getString("dirty");
        reopened.reconcile(id,snapshot(10000,1),old);assertEquals(30000,reopened.read(id).getLong("position_ms"));assertEquals(latest,reopened.read(id).getString("dirty"));
        reopened.reconcile(id,snapshot(30000,3),latest);assertFalse(reopened.read(id).has("dirty"));
        assertEquals("not_started",PlaybackState.status(new PlaybackStore(temporary.newFolder()).read(id)));
    }
    @Test public void mergingDevicesUnionsCoverageWithoutDoubleCounting() throws Exception {
        JSONObject a=snapshot(60000,2).put("ranges",new JSONArray().put(new JSONArray().put(0).put(50000)));
        JSONObject b=snapshot(55000,1).put("ranges",new JSONArray().put(new JSONArray().put(20000).put(55000)));
        JSONObject merged=PlaybackState.merge(a,b);assertEquals(60000,merged.getLong("position_ms"));
        assertEquals("[[0,55000]]",merged.getJSONArray("ranges").toString());
    }
}
