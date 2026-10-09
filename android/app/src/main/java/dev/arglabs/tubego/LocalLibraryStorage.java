package dev.arglabs.tubego;
import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** All managed downloads must use this root. Logout never touches another device. */
public final class LocalLibraryStorage {
    public static File root(Context context,String origin,String userId,String deviceId) throws Exception {
        return root(context.getFilesDir(),origin,userId,deviceId);
    }
    static File root(File files,String origin,String userId,String deviceId) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(new ApiClient(origin).getBaseUrl().getBytes(StandardCharsets.UTF_8));
        StringBuilder hash=new StringBuilder();for(byte value:digest) hash.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));String originHash=hash.toString();
        String user=UUID.fromString(userId).toString(),device=UUID.fromString(deviceId).toString();
        return new File(files.getCanonicalFile(),"tubego-media/"+originHash+"/"+user+"/"+device);
    }
    public static void wipe(Context context,String origin,String userId,String deviceId) throws Exception {
        File directory=root(context,origin,userId,deviceId);
        wipeDirectory(directory);
    }
    static void wipeDirectory(File directory) throws IOException { remove(directory,directory.getCanonicalPath()); }
    private static void remove(File file,String boundary) throws IOException {
        if(java.nio.file.Files.isSymbolicLink(file.toPath())) {
            if(!file.delete()) throw new IOException("No se pudo borrar un enlace local");
            return;
        }
        if(!file.exists()) return;
        String resolved=file.getCanonicalPath();
        if(!resolved.equals(boundary)&&!resolved.startsWith(boundary+File.separator)) {
            // Refuse following a symlink outside our managed directory.
            if(!file.delete()) throw new IOException("No se pudo borrar el enlace local");
            return;
        }
        if(file.isDirectory()) {
            File[] children=file.listFiles();if(children==null) throw new IOException("No se pudo leer la biblioteca local");
            for(File child:children) remove(child,boundary);
        }
        if(!file.delete()) throw new IOException("No se pudo borrar un archivo local");
    }
}
