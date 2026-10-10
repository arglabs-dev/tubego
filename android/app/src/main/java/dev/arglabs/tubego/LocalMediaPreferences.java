package dev.arglabs.tubego;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import org.json.JSONObject;

/** Device cache scoped to private account/device root, deleted with its media. */
public final class LocalMediaPreferences {
    private final File file;
    public LocalMediaPreferences(File root){file=new File(root,"media-preferences.properties");}
    public synchronized JSONObject read() throws Exception {
        Properties prefs=new Properties();if(file.exists())try(FileInputStream input=new FileInputStream(file)){prefs.load(input);}
        return new JSONObject().put("ask_every_time",Boolean.parseBoolean(prefs.getProperty("ask_every_time","true")))
            .put("selection",prefs.getProperty("selection","720")).put("rewind_seconds",Integer.parseInt(prefs.getProperty("rewind_seconds","10")));
    }
    /** Caller holds SessionStore.class so an account switch cannot recreate files. */
    public boolean saveServerSnapshotIfCurrent(CommandQueue queue,long expectedSequence,JSONObject values)throws Exception {
        if(queue.pendingKind("preferences")||queue.latestSequence("preferences")!=expectedSequence)return false;
        save(values);return true;
    }
    public synchronized void save(JSONObject values) throws Exception {
        MediaSelection.index(values.getString("selection"));
        if(!file.getParentFile().exists()&&!file.getParentFile().mkdirs())throw new IOException("No se pudo guardar preferencias");
        Properties prefs=new Properties();prefs.setProperty("ask_every_time",String.valueOf(values.getBoolean("ask_every_time")));prefs.setProperty("selection",values.getString("selection"));prefs.setProperty("rewind_seconds",String.valueOf(values.getInt("rewind_seconds")));
        File temporary=new File(file.getPath()+".tmp");try(FileOutputStream output=new FileOutputStream(temporary)){prefs.store(output,null);output.getFD().sync();}
        Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
