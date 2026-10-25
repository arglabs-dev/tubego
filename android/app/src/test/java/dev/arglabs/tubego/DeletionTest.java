package dev.arglabs.tubego;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DeletionTest {
    @Test public void offlineIntentSurvivesRestartAndNetworkRetryKeepsSameCommand() throws Exception {
        File root=Files.createTempDirectory("deletion-outbox").toFile();String resource=UUID.randomUUID().toString();
        DeletionOutbox.Entry entry=new DeletionOutbox(root).add(resource,"devices_and_server");
        DeletionOutbox restarted=new DeletionOutbox(root);
        assertFalse(restarted.flush(e->{assertEquals(entry.id,e.id);throw new IOException("offline");}));
        assertTrue(restarted.flush(e->{assertEquals(entry.id,e.id);assertEquals("devices_and_server",e.scope);return e.resource;}));
        assertEquals("submitted",restarted.entries().get(0).state);
    }
    @Test public void logoutDuringCallbackNeverRecreatesErasedOutbox() throws Exception {
        File root=Files.createTempDirectory("deletion-owner").toFile(),queue=new File(root,"commands");AtomicBoolean active=new AtomicBoolean(true);
        DeletionOutbox box=new DeletionOutbox(queue);box.add(UUID.randomUUID().toString(),"devices");
        assertFalse(box.flush(e->{active.set(false);LocalLibraryStorage.wipeDirectory(root);return e.resource;},new Object(),active::get));
        assertFalse(root.exists());
    }
    @Test public void partialAndCompleteGoneButManifestAndDurableTombstoneRemain() throws Exception {
        File root=Files.createTempDirectory("deletion-local").toFile();String id=UUID.randomUUID().toString();
        Files.write(new File(root,id+".part").toPath(),new byte[]{1});Files.write(new File(root,id+".media").toPath(),new byte[]{2});
        Properties props=new Properties();props.setProperty("title","Keep history");props.setProperty("state","complete");
        try(FileOutputStream out=new FileOutputStream(new File(root,id+".properties"))){props.store(out,null);}
        LocalResourceDeletion.apply(root,id);
        assertFalse(new File(root,id+".part").exists());assertFalse(new File(root,id+".media").exists());assertTrue(LocalResourceDeletion.deleted(root,id));
        try(FileInputStream in=new FileInputStream(new File(root,id+".properties"))){props.load(in);}
        assertEquals("Keep history",props.getProperty("title"));assertEquals("deleted",props.getProperty("state"));
        LocalResourceDeletion.apply(root,id);assertTrue(LocalResourceDeletion.deleted(root,id));
        LocalResourceDeletion.approve(root,id);assertFalse(LocalResourceDeletion.deleted(root,id));
    }
    @Test public void symlinkCopyUnlinkedWithoutTouchingTarget() throws Exception {
        File root=Files.createTempDirectory("deletion-link").toFile();Path foreign=Files.createTempFile("foreign","video");String id=UUID.randomUUID().toString();
        Files.createSymbolicLink(new File(root,id+".media").toPath(),foreign);LocalResourceDeletion.apply(root,id);
        assertTrue(Files.exists(foreign));assertFalse(Files.exists(new File(root,id+".media").toPath(),LinkOption.NOFOLLOW_LINKS));
    }
}
