package dev.arglabs.tubego;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

public class MediaFileNameTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 private final String id="11111111-1111-4111-8111-111111111111";
 @Test public void normalizesPortableUnicodeNames(){
  assertEquals("Mi_video_2026_resumen.mp4",MediaFileName.display(" ../Mi vídeo: 2026/ resumen?* ",false));
  assertEquals("Video_CON.mp3",MediaFileName.display("CON",true));
  assertEquals("Video.mp4",MediaFileName.display(".../\\",false));
  assertEquals("ABC.mp4",MediaFileName.display("ＡＢＣ",false));
  assertTrue(MediaFileName.display("🎥".repeat(200),false).getBytes(StandardCharsets.UTF_8).length<=164);
 }
 @Test public void persistsStableDescriptivePathAndDeletesIt()throws Exception{
  TransferRecord r=new TransferRecord(temp.getRoot(),id,"a".repeat(64),3,"Título / prueba","");
  Files.write(r.media().toPath(),new byte[]{1,2,3});r.state="complete";r.save();
  String name=r.media().getName();assertTrue(name.endsWith("Titulo_prueba.mp4"));assertTrue(name.startsWith(id));
  TransferRecord restored=TransferRecord.read(r.manifest());restored.title="Changed metadata";restored.save();
  assertEquals(name,TransferRecord.read(r.manifest()).media().getName());
  LocalResourceDeletion.apply(temp.getRoot(),id);assertFalse(new File(temp.getRoot(),name).exists());
 }
 @Test public void audioExtensionAndCollisionsAndLegacy()throws Exception{
  TransferRecord a=new TransferRecord(temp.getRoot(),id,"a".repeat(64),1,"Same title","");a.mediaFormat="audio";
  assertTrue(a.media().getName().endsWith(".mp3"));
  TransferRecord b=new TransferRecord(temp.getRoot(),"22222222-2222-4222-8222-222222222222","a".repeat(64),1,"Same title","");b.mediaFormat="audio";
  assertNotEquals(a.media(),b.media());
  Files.write(new File(temp.getRoot(),id+".media").toPath(),new byte[]{1});
  TransferRecord legacy=new TransferRecord(temp.getRoot(),id,"a".repeat(64),1,"Legacy","");legacy.save();
  assertEquals(id+".media",TransferRecord.read(legacy.manifest()).media().getName());
 }
 @Test public void rejectsManifestPathTraversal()throws Exception{
  TransferRecord r=new TransferRecord(temp.getRoot(),id,"a".repeat(64),1,"Video","");r.save();
  java.util.Properties p=new java.util.Properties();try(FileInputStream in=new FileInputStream(r.manifest())){p.load(in);}
  p.setProperty("file_name",id+"--../../escape.mp4");try(FileOutputStream out=new FileOutputStream(r.manifest())){p.store(out,null);}
  assertThrows(IOException.class,()->TransferRecord.read(r.manifest()));
 }
 @Test public void onlyAsciiLettersNumbersAndUnderscores(){
  assertEquals("Cancion_del_nino_2026.mp4",MediaFileName.display(" ¡Canción del niño! 🎵 (2026) ",false));
  assertEquals("uno_dos_tres_cuatro.mp3",MediaFileName.display("uno  dos\t tres—cuatro",true));
  assertEquals("Video.mp4",MediaFileName.display("🎥 日本語 !!!",false));
  assertTrue(MediaFileName.normalized(MediaFileName.display("áéíóú Ñ ß / \n",false)));
  assertEquals("Same_title_2.mp4",MediaFileName.numbered("Same title",false,2));
  assertFalse(MediaFileName.normalized("Title (1).mp4"));
 }

}
