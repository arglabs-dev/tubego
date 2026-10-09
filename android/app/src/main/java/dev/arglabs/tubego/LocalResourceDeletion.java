package dev.arglabs.tubego;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** A durable deliberate-deletion mark fences old snapshots and partial writers. */
public final class LocalResourceDeletion {
    private LocalResourceDeletion() {}
    public static File marker(File root,String resource) {return new File(root,UUID.fromString(resource).toString()+".deleted");}
    public static boolean deleted(File root,String resource) {return marker(root,resource).exists();}
    public static void apply(File root,String resource) throws IOException {
        String id=UUID.fromString(resource).toString();
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("No se pudo guardar el borrado");
        File temp=new File(root,id+".deleted.tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(("deleted:"+UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
        Files.move(temp.toPath(),marker(root,id).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        for(String suffix:new String[]{".part",".media"})Files.deleteIfExists(new File(root,id+suffix).toPath());
        File manifest=new File(root,id+".properties");
        if(manifest.isFile()&&!Files.isSymbolicLink(manifest.toPath())) {
            Properties values=new Properties();try(FileInputStream in=new FileInputStream(manifest)){values.load(in);}
            values.setProperty("state","deleted");values.setProperty("message","Borrado por el usuario. Requiere aprobación para descargar de nuevo.");values.setProperty("offset","0");
            File pending=new File(root,id+".properties.tmp");
            try(FileOutputStream out=new FileOutputStream(pending)){values.store(out,null);out.getFD().sync();}
            Files.move(pending.toPath(),manifest.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
    }
    public static String stamp(File root,String resource) throws IOException {
        File marker=marker(root,resource);if(!marker.exists())return "";
        if(Files.isSymbolicLink(marker.toPath())||marker.length()>256)throw new IOException("Marca de borrado inválida");
        return new String(Files.readAllBytes(marker.toPath()),java.nio.charset.StandardCharsets.UTF_8);
    }
    /** Called only after an explicit approved request; never from a normal snapshot. */
    public static void approve(File root,String resource) throws IOException {Files.deleteIfExists(marker(root,resource).toPath());}
}
