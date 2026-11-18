package dev.arglabs.tubego;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.json.JSONObject;

/** Atomic account-root snapshots. Dirty revision survives a crash and a lost HTTP reply. */
public final class PlaybackStore {
    private final File directory;
    public PlaybackStore(File accountRoot) {directory=new File(accountRoot,"playback");}
    private File file(String id) {return new File(directory,UUID.fromString(id).toString()+".json");}
    public JSONObject read(String id) throws Exception {synchronized(PlaybackStore.class){
        File file=file(id);if(!file.exists())return PlaybackState.empty();
        if(Files.isSymbolicLink(file.toPath())||file.length()>512*1024)throw new IOException("Invalid playback state");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    }}
    public void save(String id,JSONObject state,boolean dirty) throws Exception {synchronized(PlaybackStore.class){
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot save playback");
        JSONObject copy=new JSONObject(state.toString());if(dirty)copy.put("dirty",UUID.randomUUID().toString());
        File target=file(id),temporary=new File(target.getPath()+".tmp");
        try(FileOutputStream output=new FileOutputStream(temporary)){output.write(copy.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }}
    public String[] ids() {
        String[] files=directory.list((dir,name)->name.matches("[0-9a-f-]{36}\\.json"));if(files==null)return new String[0];
        for(int i=0;i<files.length;i++)files[i]=files[i].substring(0,36);return files;
    }
    public void reconcile(String id,JSONObject remote,String sentRevision) throws Exception {synchronized(PlaybackStore.class){
        JSONObject current=read(id),merged=PlaybackState.merge(current,remote);
        if(sentRevision!=null&&sentRevision.equals(current.optString("dirty")))merged.remove("dirty");
        save(id,merged,false);
    }}
}
