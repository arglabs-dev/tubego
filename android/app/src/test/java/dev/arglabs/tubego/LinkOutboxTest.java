package dev.arglabs.tubego;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
public final class LinkOutboxTest{
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void restartAndRetryKeepSameRequestIdentity() throws Exception{
  File root=temp.newFolder();LinkOutbox box=new LinkOutbox(root);LinkOutbox.Entry entry=box.add("https://example.com/v","audio");
  List<String> ids=new ArrayList<>();assertFalse(box.flush(value->{ids.add(value.id);throw new IOException("offline");}));
  LinkOutbox restarted=new LinkOutbox(root);assertEquals("audio",restarted.entries().get(0).selection);
  assertTrue(restarted.flush(value->{ids.add(value.id);return "resource";}));assertEquals(ids.get(0),ids.get(1));assertEquals(entry.id,ids.get(0));assertEquals("submitted",restarted.entries().get(0).state);
  assertTrue(restarted.flush(value->{fail("already submitted must not resend");return "";}));
 }
 @Test public void terminalErrorsStayVisibleAndManualRetryIsExplicit() throws Exception{
  LinkOutbox box=new LinkOutbox(temp.newFolder());LinkOutbox.Entry entry=box.add("https://example.com/v","720");
  assertTrue(box.flush(value->{throw new LinkOutbox.PermanentFailure("Unsupported source");}));assertEquals("error",box.entries().get(0).state);
  box.retry(entry.id);assertEquals("queued",box.entries().get(0).state);assertEquals(entry.id,box.entries().get(0).id);
 }
 @Test public void revokedOwnerCannotRecreateDeletedOutbox() throws Exception{
  File root=temp.newFolder();LinkOutbox box=new LinkOutbox(root);box.add("https://example.com/v","720");boolean[] active={true};
  assertFalse(box.flush(value->{LocalLibraryStorage.wipeDirectory(root);active[0]=false;return "resource";},new Object(),()->active[0]));
  assertFalse(root.exists());
 }
}
