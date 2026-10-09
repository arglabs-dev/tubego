package dev.arglabs.tubego;

import java.io.*;
import java.nio.file.Files;
import java.util.UUID;

/** The library never resolves a caller-supplied file path. */
public final class OfflineMediaAccess {
    public static TransferRecord record(File root,String id) throws IOException {
        String canonical;
        try { canonical=UUID.fromString(id).toString(); } catch(Exception e){throw new IOException("Recurso inválido");}
        if(!canonical.equals(id)) throw new IOException("Recurso inválido");
        File manifest=new File(root,canonical+".properties");
        if(Files.isSymbolicLink(manifest.toPath())) throw new IOException("Ficha inválida");
        TransferRecord r;
        try {r=TransferRecord.read(manifest);} catch(Exception e){throw new IOException("Ficha no disponible",e);}
        if(!r.id.equals(canonical))throw new IOException("Ficha incompatible");
        return r;
    }
    public static boolean available(TransferRecord r) throws IOException {
        File file=r.media();
        return "complete".equals(r.state) && !new File(r.root,r.id+".deleted").exists()
            && !Files.isSymbolicLink(file.toPath()) && file.isFile() && file.length()==r.size
            && file.getCanonicalFile().getParentFile().equals(r.root.getCanonicalFile());
    }
    public static void verify(TransferRecord r) throws IOException {
        if(!available(r) || !ResumableTransfer.verifies(r.media(),r.size,r.sha256))
            throw new IOException("El archivo local falta o está dañado. No se descargará durante la reproducción.");
    }
    public static String mime(TransferRecord r){return "audio".equals(r.mediaFormat)?"audio/mpeg":"video/mp4";}
}
