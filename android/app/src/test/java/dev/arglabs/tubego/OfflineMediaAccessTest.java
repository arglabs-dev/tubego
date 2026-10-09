package dev.arglabs.tubego;
import org.junit.*;import org.junit.rules.TemporaryFolder;import static org.junit.Assert.*;
import java.io.*;import java.nio.file.*;import java.security.*;
public final class OfflineMediaAccessTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 static final String ID="11111111-1111-4111-8111-111111111111";
 private TransferRecord complete() throws Exception {
  byte[] bytes="offline bytes".getBytes();StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",b&255));
  TransferRecord r=new TransferRecord(temp.getRoot(),ID,sha.toString(),bytes.length,"Local","");r.state="complete";Files.write(r.media().toPath(),bytes);r.save();return r;
 }
 @Test public void completePersistsWithoutAnyServerAndMimeTracksAudio() throws Exception {TransferRecord r=complete();OfflineMediaAccess.verify(OfflineMediaAccess.record(temp.getRoot(),ID));r.mediaFormat="audio";r.save();assertEquals("audio/mpeg",OfflineMediaAccess.mime(OfflineMediaAccess.record(temp.getRoot(),ID)));}
 @Test public void sameSizeTamperingIsRejected() throws Exception {TransferRecord r=complete();Files.write(r.media().toPath(),"tamperedbytes".getBytes());assertThrows(IOException.class,()->OfflineMediaAccess.verify(r));}
 @Test public void missingAndDeliberatelyDeletedCannotPlay() throws Exception {TransferRecord r=complete();Files.write(new File(r.root,ID+".deleted").toPath(),new byte[0]);assertFalse(OfflineMediaAccess.available(r));assertThrows(IOException.class,()->OfflineMediaAccess.verify(r));new File(r.root,ID+".deleted").delete();r.media().delete();assertFalse(OfflineMediaAccess.available(r));}
 @Test public void arbitraryPathsManifestMismatchAndLinksAreRejected() throws Exception {TransferRecord r=complete();assertThrows(IOException.class,()->OfflineMediaAccess.record(temp.getRoot(),"../"+ID));String other="22222222-2222-4222-8222-222222222222";Files.copy(r.manifest().toPath(),new File(r.root,other+".properties").toPath());assertThrows(IOException.class,()->OfflineMediaAccess.record(r.root,other));File external=temp.newFile();r.media().delete();Files.createSymbolicLink(r.media().toPath(),external.toPath());assertFalse(OfflineMediaAccess.available(r));}
 @Test public void partialFileNeverBecomesPlayable() throws Exception {TransferRecord r=complete();r.state="paused";assertFalse(OfflineMediaAccess.available(r));}
}
