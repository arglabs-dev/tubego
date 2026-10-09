package dev.arglabs.tubego;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.UUID;

/** Durable queue item. The actual partial-file length is the resume offset. */
public final class TransferRecord {
    public final File root;
    public final String id;
    public String sha256,title,createdAt,mediaFormat="video",state="pending",message="";
    public long size,nextRetryAt;
    public int failures;
    public String failureCode="",pauseReason="",failureIncident="";
    public TransferRecord(File root,String id,String sha256,long size,String title,String createdAt) {
        this.root=root;this.id=UUID.fromString(id).toString();
        if(!sha256.matches("[0-9a-f]{64}") || size<0) throw new IllegalArgumentException("Metadatos inválidos");
        this.sha256=sha256;this.size=size;this.title=title;this.createdAt=createdAt;
    }
    public File part() {return new File(root,id+".part");}
    public File media() {return new File(root,id+".media");}
    public File manifest() {return new File(root,id+".properties");}
    public long offset() {return part().length();}
    public void save() throws IOException {
        if(!root.isDirectory() && !root.mkdirs()) throw new IOException("No se pudo crear la biblioteca");
        Properties p=new Properties();p.setProperty("id",id);p.setProperty("sha256",sha256);p.setProperty("size",Long.toString(size));
        p.setProperty("retry_failures",Integer.toString(failures));p.setProperty("next_retry_at",Long.toString(nextRetryAt));p.setProperty("failure_code",failureCode);p.setProperty("pause_reason",pauseReason);p.setProperty("failure_incident",failureIncident);
        p.setProperty("media_format",mediaFormat);p.setProperty("title",title);p.setProperty("created_at",createdAt);p.setProperty("state",state);p.setProperty("message",message);p.setProperty("offset",Long.toString(offset()));
        File tmp=new File(root,id+".properties.tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)) {p.store(out,"Tubego transfer");out.getFD().sync();}
        Files.move(tmp.toPath(),manifest().toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    public static TransferRecord read(File manifest) throws IOException {
        Properties p=new Properties();try(FileInputStream in=new FileInputStream(manifest)){p.load(in);}
        TransferRecord r=new TransferRecord(manifest.getParentFile(),p.getProperty("id"),p.getProperty("sha256"),Long.parseLong(p.getProperty("size")),p.getProperty("title","Archivo"),p.getProperty("created_at",""));
        r.failures=Integer.parseInt(p.getProperty("retry_failures","0"));r.nextRetryAt=Long.parseLong(p.getProperty("next_retry_at","0"));r.failureCode=p.getProperty("failure_code","");r.pauseReason=p.getProperty("pause_reason","");r.failureIncident=p.getProperty("failure_incident","");
        r.mediaFormat=p.getProperty("media_format","video");r.state=p.getProperty("state","pending");r.message=p.getProperty("message","");return r;
    }
    public void reconcile(String digest,long expected) throws IOException {
        if(!digest.matches("[0-9a-f]{64}") || expected<0) throw new IOException("Metadatos inválidos");
        if(!sha256.equals(digest) || size!=expected) {
            if(part().exists() && !part().delete()) throw new IOException("No se pudo descartar parcial incompatible");
            sha256=digest;size=expected;pauseReason="";failureIncident="";failures=0;nextRetryAt=0;failureCode="";state="pending";message="El archivo cambió en el servidor. La descarga comienza de nuevo.";
        }
    }
}
