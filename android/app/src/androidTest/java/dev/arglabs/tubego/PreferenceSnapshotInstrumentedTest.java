package dev.arglabs.tubego;
import android.test.AndroidTestCase;import java.io.File;import java.util.UUID;import org.json.JSONObject;
/** A GET issued before a local edit must never overwrite that edit, even after its ACK. */
public final class PreferenceSnapshotInstrumentedTest extends AndroidTestCase {
 public void testPendingAndAlreadyAcknowledgedEditRejectOldServerSnapshot()throws Exception{
  File root=new File(getContext().getCacheDir(),"prefs-"+UUID.randomUUID());LocalMediaPreferences prefs=new LocalMediaPreferences(root);CommandQueue queue=new CommandQueue(root);
  JSONObject old=new JSONObject().put("ask_every_time",true).put("selection","720").put("rewind_seconds",10);JSONObject edit=new JSONObject().put("ask_every_time",false).put("selection","audio").put("rewind_seconds",20);
  long requestSequence=queue.latestSequence("preferences");CommandQueue.Entry command=queue.add("preferences",edit.toString());prefs.save(edit);
  assertFalse(prefs.saveServerSnapshotIfCurrent(queue,requestSequence,old));assertEquals("audio",prefs.read().getString("selection"));
  queue.finish(command,"complete","",edit.toString());assertFalse(prefs.saveServerSnapshotIfCurrent(queue,requestSequence,old));assertEquals(20,prefs.read().getInt("rewind_seconds"));
  assertTrue(prefs.saveServerSnapshotIfCurrent(queue,queue.latestSequence("preferences"),edit));
 }
}
