package dev.arglabs.tubego;
import org.junit.Test;import static org.junit.Assert.*;import java.nio.file.Files;import java.io.File;import java.util.UUID;
public class LocalResourceRevisionTest {
 @Test public void oldSnapshotAfterNewRecoveryCannotApplyADeletion()throws Exception{File root=Files.createTempDirectory("revision").toFile();String id=UUID.randomUUID().toString();assertTrue(LocalResourceRevision.accept(root,id,2));assertFalse(LocalResourceRevision.accept(root,id,1));assertEquals(2,LocalResourceRevision.current(root,id));assertTrue(LocalResourceRevision.accept(root,id,3));assertEquals(3,LocalResourceRevision.current(root,id));}
}
