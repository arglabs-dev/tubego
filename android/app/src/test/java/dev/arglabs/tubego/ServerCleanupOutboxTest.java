package dev.arglabs.tubego;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerCleanupOutboxTest {
    @Test public void offlineRestartRetryPreservesConfirmedGlobalScopeAndUuid()throws Exception{
        File queue=Files.createTempDirectory("server-cleanup").toFile();ServerCleanupOutbox.Entry saved=new ServerCleanupOutbox(queue).add("global");ServerCleanupOutbox restarted=new ServerCleanupOutbox(queue);
        assertFalse(restarted.flush(e->{assertEquals(saved.id,e.id);assertEquals("global",e.scope);throw new IOException("offline");}));
        assertTrue(restarted.flush(e->{assertEquals(saved.id,e.id);assertEquals("global",e.scope);return e.id;}));assertEquals("submitted",restarted.entries().get(0).state);
    }
    @Test public void roleRejectionPreservedUntilManualRetryAndDoesNotEraseLocalMedia()throws Exception{
        File root=Files.createTempDirectory("server-cleanup-role").toFile(),media=new File(root,"saved.media");Files.write(media.toPath(),new byte[]{1,2,3});ServerCleanupOutbox box=new ServerCleanupOutbox(new File(root,"commands"));ServerCleanupOutbox.Entry saved=box.add("global");
        assertTrue(box.flush(e->{throw new ServerCleanupOutbox.PermanentFailure("Administrator required");}));
        assertEquals("error",box.entries().get(0).state);assertTrue(media.exists());box.retry(saved.id);
        assertTrue(box.flush(e->{assertEquals(saved.id,e.id);return e.id;}));assertTrue(media.exists());
    }
    @Test public void logoutDuringRemoteResponseCannotRecreateCleanedAccountStorage()throws Exception{
        File root=Files.createTempDirectory("server-cleanup-session").toFile(),queue=new File(root,"commands");AtomicBoolean active=new AtomicBoolean(true);ServerCleanupOutbox box=new ServerCleanupOutbox(queue);box.add("own");
        assertFalse(box.flush(e->{active.set(false);LocalLibraryStorage.wipeDirectory(root);return e.id;},new Object(),active::get));assertFalse(root.exists());
    }
}
