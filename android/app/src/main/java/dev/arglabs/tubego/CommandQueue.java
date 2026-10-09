package dev.arglabs.tubego;

import java.io.*;
import java.util.*;

/** Account/device-owned durable intentions. Acknowledgement never changes order. */
public final class CommandQueue {
    public static final class Entry {
        public final String id,kind,payload,state,error,result;
        public final long sequence;
        Entry(String id,long sequence,String kind,String payload,String state,String error,String result){
            this.id=id;this.sequence=sequence;this.kind=kind;this.payload=payload;
            this.state=state;this.error=error;this.result=result;
        }
        Entry state(String state,String error,String result){return new Entry(id,sequence,kind,payload,state,error,result);}
    }
    private static final Object LOCK=new Object();
    private final File directory;
    public CommandQueue(File root){directory=new File(root,"command-outbox");}
    public Entry add(String kind,String payload)throws IOException{return importIntent(UUID.randomUUID().toString(),kind,payload);}
    /** Original UUID preserves a legacy effect receipt whose reply was lost. */
    public Entry importIntent(String id,String kind,String payload)throws IOException{
        UUID.fromString(id);
        if(!Arrays.asList("submit","recover","delete","server_cleanup","preferences","language","resource_priority","task_cancel","task_retry","task_priority","noop").contains(kind)||payload==null||payload.length()>16384)throw new IOException("Invalid command intention");
        synchronized(LOCK){
            Entry existing=find(id);if(existing!=null)return existing;
            long sequence=0;for(Entry entry:entries())sequence=Math.max(sequence,entry.sequence);
            if(sequence==Long.MAX_VALUE)throw new IOException("Command sequence exhausted");
            Entry value=new Entry(id,sequence+1,kind,payload,"queued","","");write(value);return value;
        }
    }
    public List<Entry> entries()throws IOException{
        synchronized(LOCK){
            List<Entry> result=new ArrayList<>();File[] files=directory.listFiles((dir,name)->name.endsWith(".properties"));
            if(files!=null)for(File file:files){if(java.nio.file.Files.isSymbolicLink(file.toPath())||file.length()>65536)throw new IOException("Invalid command record");Properties p=new Properties();try(InputStream in=new FileInputStream(file)){p.load(in);}
                try{String id=UUID.fromString(p.getProperty("id")).toString();if(!file.getName().equals(id+".properties"))throw new IOException("Invalid command identity");
                result.add(new Entry(p.getProperty("id"),Long.parseLong(p.getProperty("sequence")),p.getProperty("kind"),p.getProperty("payload"),p.getProperty("state"),p.getProperty("error",""),p.getProperty("result","")));}
                catch(RuntimeException invalid){throw new IOException("Invalid command record",invalid);}}
            result.sort(Comparator.comparingLong(entry->entry.sequence));return result;
        }
    }
    public Entry find(String id)throws IOException{for(Entry entry:entries())if(entry.id.equals(id))return entry;return null;}
    public boolean pendingKind(String kind)throws IOException{for(Entry entry:entries())if(entry.kind.equals(kind)&&entry.state.equals("queued"))return true;return false;}
    public void finish(Entry entry,String state,String error,String result)throws IOException{
        synchronized(LOCK){Entry current=find(entry.id);if(current!=null&&current.state.equals("queued"))write(current.state(state,error,result));}
    }
    /** Retry is a NEW intention, after intervening deletes/cleanup/preferences. */
    public Entry retry(String id)throws IOException{
        synchronized(LOCK){Entry old=find(id);if(old==null||!old.state.equals("error"))throw new IOException("Command cannot be retried");return add(old.kind,old.payload);}
    }
    private void write(Entry value)throws IOException{
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot persist command");
        Properties p=new Properties();p.setProperty("id",value.id);p.setProperty("sequence",Long.toString(value.sequence));
        p.setProperty("kind",value.kind);p.setProperty("payload",value.payload);p.setProperty("state",value.state);
        p.setProperty("error",value.error);p.setProperty("result",value.result);
        File destination=new File(directory,value.id+".properties"),temporary=new File(directory,value.id+".tmp");
        try(FileOutputStream out=new FileOutputStream(temporary)){p.store(out,null);out.getFD().sync();}
        java.nio.file.Files.move(temporary.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
