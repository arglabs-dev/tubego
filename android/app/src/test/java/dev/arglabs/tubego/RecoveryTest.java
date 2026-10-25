package dev.arglabs.tubego;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecoveryTest {
    @Test public void offlineApprovalRetryPreservesCommandAndDeviceConsent()throws Exception{
        File root=Files.createTempDirectory("recovery-outbox").toFile();String resource=UUID.randomUUID().toString();RecoveryOutbox.Entry entry=new RecoveryOutbox(root).add(resource,"approved");
        RecoveryOutbox restarted=new RecoveryOutbox(root);
        assertFalse(restarted.flush(e->{assertEquals(entry.id,e.id);assertEquals("approved",e.scope);throw new IOException("offline");}));
        assertTrue(restarted.flush(e->{assertEquals(entry.id,e.id);return e.resource;}));assertEquals("submitted",restarted.entries().get(0).state);
    }
    @Test public void oldApprovalCannotEraseNewerDeleteIntent()throws Exception{
        File root=Files.createTempDirectory("recovery-generation").toFile();String id=UUID.randomUUID().toString();
        LocalResourceDeletion.apply(root,id);String before=LocalResourceDeletion.stamp(root,id);
        LocalResourceDeletion.apply(root,id);String after=LocalResourceDeletion.stamp(root,id);
        assertNotEquals(before,after);
        if(before.equals(after))LocalResourceDeletion.approve(root,id);
        assertTrue(LocalResourceDeletion.deleted(root,id));
        LocalResourceDeletion.approve(root,id);assertFalse(LocalResourceDeletion.deleted(root,id));
    }
    @Test public void accountLogoutWhileResponsePendingCannotRecreateQueue()throws Exception{
        File root=Files.createTempDirectory("recovery-owner").toFile(),queue=new File(root,"recovery");AtomicBoolean active=new AtomicBoolean(true);
        RecoveryOutbox box=new RecoveryOutbox(queue);box.add(UUID.randomUUID().toString(),"approved");
        assertFalse(box.flush(e->{active.set(false);LocalLibraryStorage.wipeDirectory(root);return e.resource;},new Object(),active::get));assertFalse(root.exists());
    }
}
