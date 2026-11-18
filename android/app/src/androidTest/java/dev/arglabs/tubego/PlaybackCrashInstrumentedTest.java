package dev.arglabs.tubego;
import android.app.Activity;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Run methods separately: kill ONLY the guarded harness process after crash-ready.json. */
@SuppressWarnings("deprecation")
public final class PlaybackCrashInstrumentedTest extends PlaybackFixture {
    public void testPrepareAbruptExit() throws Exception {
        File marker=new File(app.getFilesDir(),"crash-ready.json");
        Files.write(marker.toPath(),"{\"ready\":false}".getBytes(StandardCharsets.UTF_8));
        reset();open();JSONObject checkpoint=waitForPosition(15000,26000);
        Files.write(marker.toPath(),new JSONObject().put("ready",true).put("checkpoint",checkpoint).put("libvlc_version",org.videolan.libvlc.LibVLC.version()).put("libvlc_changeset",org.videolan.libvlc.LibVLC.changeset()).toString().getBytes(StandardCharsets.UTF_8));
        Thread.sleep(60000);
        fail("The external driver must force-stop only dev.arglabs.tubego.playbacktest after ready=true");
    }
    public void testResumeAfterAbruptExit() throws Exception {
        JSONObject before=saved();assertTrue(before.getLong("position_ms")>=15000);
        Activity activity=open();
        try {
            long until=System.currentTimeMillis()+10000;JSONObject after=saved();
            while(after.optLong("position_at_ms")<=before.optLong("position_at_ms")&&System.currentTimeMillis()<until){Thread.sleep(200);after=saved();}
            assertTrue(after.optLong("position_at_ms")>before.optLong("position_at_ms"));
            long requested=PlaybackTimeline.resume(before.getLong("position_ms"),10);
            assertTrue("Restart must rewind from durable checkpoint",after.getLong("position_ms")>=requested && after.getLong("position_ms")<=requested+2500);
            Files.write(new File(app.getFilesDir(),"crash-resume.json").toPath(),new JSONObject().put("before",before).put("after",after).put("requested_ms",requested).toString().getBytes(StandardCharsets.UTF_8));
        } finally {getInstrumentation().runOnMainSync(activity::finish);}
    }
}
