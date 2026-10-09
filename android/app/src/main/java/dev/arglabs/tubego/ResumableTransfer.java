package dev.arglabs.tubego;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure transfer engine. Android adapter supplies a specific, permitted network. */
public final class ResumableTransfer {
    public interface Response extends AutoCloseable {
        int status(); String header(String name); InputStream body() throws IOException; void close();
    }
    public interface Connector {Response open(long offset,String etag) throws Exception;}
    public enum Result {COMPLETE,PAUSED}
    private static final Pattern RANGE=Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    public static Result run(TransferRecord r,Connector connector,BooleanSupplier permitted) throws Exception {
        return run(r,connector,permitted,new Object(),()->true);
    }
    public static Result run(TransferRecord r,Connector connector,BooleanSupplier permitted,Object storageLock,BooleanSupplier alive) throws Exception {
        if(!permitted.getAsBoolean()) {r.state="paused";save(r,storageLock,alive);return Result.PAUSED;}
        if(r.media().isFile() && verifies(r.media(),r.size,r.sha256)) {synchronized(storageLock){if(!alive.getAsBoolean())return Result.PAUSED;r.state="complete";r.save();return Result.COMPLETE;}}
        if(r.offset()>r.size) truncate(r.part(),storageLock,alive);
        if(r.offset()==r.size && r.part().isFile()) return finish(r,storageLock,alive);
        r.state="downloading";save(r,storageLock,alive);
        long offset=r.offset();String etag="\""+r.sha256+"\"";
        try(Response response=connector.open(offset,etag)) {
            int status=response.status();long start=offset;
            if(status==200) {
                if(offset>0) {truncate(r.part(),storageLock,alive);r.message="El servidor reinició la transferencia; se descartó el parcial.";}
                start=0;
            } else if(status==206) {
                Matcher match=RANGE.matcher(value(response.header("Content-Range")));
                if(!match.matches() || Long.parseLong(match.group(1))!=offset || Long.parseLong(match.group(3))!=r.size
                        || Long.parseLong(match.group(2))!=r.size-1) throw new IOException("Rango incompatible con el archivo esperado");
            } else throw new IOException("El servidor respondió HTTP "+status);
            if(!etag.equals(response.header("ETag")) || !r.sha256.equals(response.header("X-Content-SHA256"))) throw new IOException("La identidad del archivo cambió. Actualiza la cola.");
            if(Long.parseLong(value(response.header("Content-Length")))!=r.size-start) throw new IOException("Tamaño de respuesta incompatible");
            RandomAccessFile opened;
            synchronized(storageLock) {if(!alive.getAsBoolean()) return Result.PAUSED;opened=new RandomAccessFile(r.part(),"rw");}
            try(RandomAccessFile output=opened;InputStream input=response.body()) {
                output.seek(start);byte[] buffer=new byte[65536];long written=start,lastSaved=start;
                while(written<r.size) {
                    if(!permitted.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        output.getFD().sync();r.state="paused";save(r,storageLock,alive);return Result.PAUSED;
                    }
                    int count=input.read(buffer,0,(int)Math.min(buffer.length,r.size-written));
                    if(count<0) throw new EOFException("Transferencia incompleta");
                    // Recheck after a blocking read before writing its bytes.
                    if(!permitted.getAsBoolean()) {output.getFD().sync();r.state="paused";save(r,storageLock,alive);return Result.PAUSED;}
                    synchronized(storageLock) {
                        if(!alive.getAsBoolean()) return Result.PAUSED;
                        output.write(buffer,0,count);output.getFD().sync();written+=count;
                    }
                    if(written-lastSaved>=1024*1024) {output.getFD().sync();save(r,storageLock,alive);lastSaved=written;}
                }
                output.getFD().sync();
            }
        } catch(Exception e) {
            r.state=permitted.getAsBoolean()?"pending":"paused";r.message=e.getMessage()==null?"Transferencia interrumpida":e.getMessage();save(r,storageLock,alive);
            if(!permitted.getAsBoolean() || Thread.currentThread().isInterrupted()) return Result.PAUSED;
            throw e;
        }
        if(!permitted.getAsBoolean()) {r.state="paused";save(r,storageLock,alive);return Result.PAUSED;}
        return finish(r,storageLock,alive);
    }
    private static String value(String text) {return text==null?"":text;}
    private static void save(TransferRecord r,Object lock,BooleanSupplier alive) throws IOException {
        synchronized(lock) {if(alive.getAsBoolean()) r.save();}
    }
    private static void truncate(File file,Object lock,BooleanSupplier alive) throws IOException {
        synchronized(lock) {if(!alive.getAsBoolean())return;try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.setLength(0);out.getFD().sync();}}
    }
    private static Result finish(TransferRecord r,Object storageLock,BooleanSupplier alive) throws IOException {
        if(!verifies(r.part(),r.size,r.sha256)) {truncate(r.part(),storageLock,alive);r.state="failed";r.message="El archivo no coincide con su checksum. Se descartó el parcial.";save(r,storageLock,alive);throw new IOException(r.message);}
        synchronized(storageLock) {
            if(!alive.getAsBoolean()) return Result.PAUSED;
            Files.move(r.part().toPath(),r.media().toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            r.state="complete";r.save();return Result.COMPLETE;
        }
    }
    public static boolean verifies(File file,long size,String expected) throws IOException {
        if(file.length()!=size || !file.isFile()) return false;
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];
            try(InputStream input=new FileInputStream(file)){int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}
            StringBuilder text=new StringBuilder();for(byte value:digest.digest())text.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            return text.toString().equals(expected);
        } catch(java.security.NoSuchAlgorithmException e) {throw new IOException(e);}
    }
}
