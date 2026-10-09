package dev.arglabs.tubego;
import android.test.AndroidTestCase;
import org.json.JSONObject;
import java.io.File;
import java.util.UUID;
/** Real Android JSON + durable tombstones; old responses never revive deletion. */
public final class CommandConflictsInstrumentedTest extends AndroidTestCase {
 public void testNewDeleteAndCleanupFenceAnOlderApproval()throws Exception{
  File root=new File(getContext().getCacheDir(),"commands-"+UUID.randomUUID());CommandQueue queue=new CommandQueue(root);String resource=UUID.randomUUID().toString();
  CommandQueue.Entry recover=queue.add("recover",new JSONObject().put("resource_id",resource).put("approve_redownload",true).toString());
  assertFalse(CommandDispatch.newerBarrier(queue,recover,resource));
  queue.add("delete",new JSONObject().put("resource_id",resource).put("scope","devices").toString());LocalResourceDeletion.apply(root,resource);
  assertTrue(CommandDispatch.newerBarrier(queue,recover,resource));queue.finish(recover,"complete","","{}");assertTrue(LocalResourceDeletion.deleted(root,resource));
  CommandQueue.Entry latest=queue.add("recover",new JSONObject().put("resource_id",resource).put("approve_redownload",true).toString());assertFalse(CommandDispatch.newerBarrier(queue,latest,resource));
  queue.add("server_cleanup",new JSONObject().put("scope","own").put("confirmed",true).toString());assertTrue(CommandDispatch.newerBarrier(queue,latest,resource));
 }
}
