package dev.arglabs.tubego;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.util.Properties;

public final class AudioTrackTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void durableCommandKeepsChoiceAcrossOfflineRetryAndLegacyMigration() throws Exception {
  File root=temp.newFolder();File dir=new File(root,"outbox");
  LinkOutbox box=new LinkOutbox(dir);
  LinkOutbox.Entry added=box.add("https://example.com/v","audio","es-419~auto");
  LinkOutbox restarted=new LinkOutbox(dir);
  assertEquals("es-419~auto",restarted.entries().get(0).audioTrack);
  CommandQueue.Entry command=new CommandQueue(root).find(added.id);
  assertTrue(command.payload.contains("\"audio_track\":\"es-419~auto\""));
  assertTrue(command.payload.contains("\"selection\":\"audio\""));
  assertFalse(restarted.flush(value->{assertEquals("es-419~auto",value.audioTrack);throw new IOException("offline");}));
  assertEquals("es-419~auto",restarted.entries().get(0).audioTrack);
  assertTrue(restarted.flush(value->"resource"));
  assertEquals("es-419~auto",restarted.entries().get(0).audioTrack);
 }
 @Test public void oldOriginalPayloadStaysByteIdenticalAndInvalidChoicesAreRejected() throws Exception {
  Properties p=new Properties();p.setProperty("url","https://example.com/v");p.setProperty("selection","720");
  assertEquals("{\"url\":\"https://example.com/v\",\"selection\":\"720\"}",CommandBridge.payload("submit",p));
  p.setProperty("audio_track","original");assertFalse(CommandBridge.payload("submit",p).contains("audio_track"));
  assertThrows(IllegalArgumentException.class,()->new LinkOutbox(temp.newFolder()).add("https://example.com/v","720","es]/best"));
 }
}
