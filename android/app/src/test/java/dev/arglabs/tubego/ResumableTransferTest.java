package dev.arglabs.tubego;
import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ResumableTransferTest {
    File root;byte[] bytes;String sha;TransferRecord record;
    @Before public void setup() throws Exception {
        root=Files.createTempDirectory("tubego-transfer-").toFile();bytes=new byte[200000];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;
        StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))digest.append(String.format("%02x",b&255));sha=digest.toString();
        record=new TransferRecord(root,"22222222-2222-4222-8222-222222222222",sha,bytes.length,"Lesson","now");record.save();
    }
    @After public void cleanup() throws Exception {try(var walk=Files.walk(root.toPath())){walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}}
    ResumableTransfer.Response response(int status,int start,byte[] content,String digest) {
        return new ResumableTransfer.Response() {
            public int status(){return status;}
            public String header(String key){switch(key){case "ETag":return "\""+digest+"\"";case "X-Content-SHA256":return digest;case "Content-Length":return Integer.toString(content.length);case "Content-Range":return "bytes "+start+"-"+(bytes.length-1)+"/"+bytes.length;default:return null;}}
            public InputStream body(){return new ByteArrayInputStream(content);}
            public void close(){}
        };
    }
    @Test public void partialSurvivesRestartAndResumesExactOffset() throws Exception {
        AtomicBoolean permitted=new AtomicBoolean(true);
        ResumableTransfer.Response source=response(200,0,bytes,sha);
        ResumableTransfer.Result first=ResumableTransfer.run(record,(offset,etag)->new ResumableTransfer.Response(){
            public int status(){return source.status();}public String header(String n){return source.header(n);}public void close(){}
            public InputStream body(){return new ByteArrayInputStream(bytes){int calls;@Override public synchronized int read(byte[] b,int off,int len){if(++calls==3)permitted.set(false);return super.read(b,off,len);}};}
        },permitted::get);
        assertEquals(ResumableTransfer.Result.PAUSED,first);assertTrue(record.offset()>0);assertFalse(record.media().exists());
        TransferRecord restarted=TransferRecord.read(record.manifest());long saved=restarted.offset();assertEquals("paused",restarted.state);
        assertEquals(ResumableTransfer.Result.COMPLETE,ResumableTransfer.run(restarted,(offset,etag)->{assertEquals(saved,offset);assertEquals("\""+sha+"\"",etag);return response(206,(int)offset,Arrays.copyOfRange(bytes,(int)offset,bytes.length),sha);},()->true));
        assertArrayEquals(bytes,Files.readAllBytes(restarted.media().toPath()));assertFalse(restarted.part().exists());
    }
    @Test public void fullResponseToResumeDiscardsOldPartial() throws Exception {
        Files.write(record.part().toPath(),new byte[]{5,6,7});
        assertEquals(ResumableTransfer.Result.COMPLETE,ResumableTransfer.run(record,(offset,etag)->{assertEquals(3,offset);return response(200,0,bytes,sha);},()->true));
        assertArrayEquals(bytes,Files.readAllBytes(record.media().toPath()));assertTrue(record.message.contains("descartó"));
    }
    @Test public void rejectsInvalidRangeTotalIdentityOrChecksumWithoutPublishing() throws Exception {
        Files.write(record.part().toPath(),new byte[]{0,1});
        try{ResumableTransfer.run(record,(o,e)->response(206,3,Arrays.copyOfRange(bytes,2,bytes.length),sha),()->true);fail();}catch(IOException expected){}
        assertFalse(record.media().exists());
        try{ResumableTransfer.run(record,(o,e)->response(200,0,bytes,"a".repeat(64)),()->true);fail();}catch(IOException expected){}
        byte[] corrupt=bytes.clone();corrupt[4]=99;
        try{ResumableTransfer.run(record,(o,e)->response(200,0,corrupt,sha),()->true);fail();}catch(IOException expected){}
        assertFalse(record.media().exists());assertEquals(0,record.offset());
    }
    @Test public void changedMetadataResetsPartAndExplainsRestart() throws Exception {
        Files.write(record.part().toPath(),new byte[]{0,1});record.reconcile("b".repeat(64),100);
        assertEquals(0,record.offset());assertEquals("pending",record.state);assertTrue(record.message.contains("cambió"));
    }
    @Test public void alreadyCompletedVerifiedFileIsNeverFetchedAgain() throws Exception {
        Files.write(record.media().toPath(),bytes);
        assertEquals(ResumableTransfer.Result.COMPLETE,ResumableTransfer.run(record,(o,e)->{throw new AssertionError("No second download");},()->true));
    }
    @Test public void diskWipeDuringNetworkReadNeverRecreatesFiles() throws Exception {
        AtomicBoolean alive=new AtomicBoolean(true);Object lock=new Object();
        ResumableTransfer.run(record,(o,e)->new ResumableTransfer.Response(){
            public int status(){return 200;}public String header(String name){return response(200,0,bytes,sha).header(name);}public void close(){}
            public InputStream body(){return new ByteArrayInputStream(bytes){@Override public synchronized int read(byte[] b,int off,int len){alive.set(false);synchronized(lock){try{LocalLibraryStorage.wipeDirectory(root);}catch(IOException error){throw new RuntimeException(error);}}return super.read(b,off,len);}};}
        },alive::get,lock,alive::get);
        assertFalse(root.exists());root.mkdirs();
    }
    @Test public void fileLengthRatherThanStaleManifestOffsetDefinesResume() throws Exception {
        Files.write(record.part().toPath(),Arrays.copyOf(bytes,500));record.save();Files.write(record.part().toPath(),Arrays.copyOf(bytes,700));
        TransferRecord restored=TransferRecord.read(record.manifest());assertEquals(700,restored.offset());
    }
}
