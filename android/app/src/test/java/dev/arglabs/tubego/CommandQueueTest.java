package dev.arglabs.tubego;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.io.File;
import java.util.UUID;
public class CommandQueueTest {
 @Test public void immutableOrderingSurvivesAckAndRestart()throws Exception {
  File root=Files.createTempDirectory("commands").toFile();CommandQueue queue=new CommandQueue(root);
  CommandQueue.Entry recover=queue.add("recover","{}");CommandQueue.Entry delete=queue.add("delete","{}");
  queue.finish(recover,"complete","","{}");
  File file=new File(root,"command-outbox/"+recover.id+".properties");assertTrue(file.setLastModified(System.currentTimeMillis()+100000));
  CommandQueue restarted=new CommandQueue(root);assertEquals(recover.id,restarted.entries().get(0).id);assertEquals(delete.id,restarted.entries().get(1).id);
  assertEquals(3,restarted.add("server_cleanup","{}").sequence);
 }
 @Test public void replayUsesSameIdentityButRetryIsNewLaterIntention()throws Exception {
  CommandQueue queue=new CommandQueue(Files.createTempDirectory("commands").toFile());String id=UUID.randomUUID().toString();
  CommandQueue.Entry failed=queue.importIntent(id,"recover","{}");assertEquals(1,queue.importIntent(id,"recover","{}").sequence);
  queue.finish(failed,"error","rejected","");CommandQueue.Entry deletion=queue.add("delete","{}");CommandQueue.Entry retry=queue.retry(id);
  assertNotEquals(failed.id,retry.id);assertTrue(retry.sequence>deletion.sequence);assertEquals("recover",retry.kind);
 }
}
