package dev.arglabs.tubego;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;
public final class LocalLibraryStorageTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final String USER="00000000-0000-0000-0000-000000000001";
    private static final String FIRST="00000000-0000-0000-0000-000000000002";
    private static final String SECOND="00000000-0000-0000-0000-000000000003";
    @Test public void cleanupTouchesOnlySpecifiedNamespace() throws Exception {
        File first=LocalLibraryStorage.root(temp.getRoot(),"https://example.com",USER,FIRST);
        File second=LocalLibraryStorage.root(temp.getRoot(),"https://example.com",USER,SECOND);
        assertTrue(first.mkdirs());assertTrue(second.mkdirs());
        Files.write(new File(first,"video.mp4").toPath(),"first".getBytes());
        Files.write(new File(second,"video.mp4").toPath(),"second".getBytes());
        LocalLibraryStorage.wipeDirectory(first);
        assertFalse(first.exists());assertTrue(new File(second,"video.mp4").exists());
    }
    @Test public void symlinkIsRemovedWithoutFollowingTarget() throws Exception {
        File outside=temp.newFolder("outside");File target=new File(outside,"keep.mp4");Files.write(target.toPath(),"keep".getBytes());
        File root=temp.newFolder("managed");Files.createSymbolicLink(new File(root,"escape").toPath(),outside.toPath());
        LocalLibraryStorage.wipeDirectory(root);assertTrue(target.exists());assertFalse(root.exists());
    }
    @Test public void differentOriginsAreIsolatedAndInvalidIdsRejected() throws Exception {
        assertNotEquals(LocalLibraryStorage.root(temp.getRoot(),"https://a.example.com",USER,FIRST),LocalLibraryStorage.root(temp.getRoot(),"https://b.example.com",USER,FIRST));
        assertThrows(IllegalArgumentException.class,()->LocalLibraryStorage.root(temp.getRoot(),"https://example.com","../../escape",FIRST));
    }
}
