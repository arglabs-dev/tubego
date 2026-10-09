package dev.arglabs.tubego;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Durable token-free request outbox. The owning account/device supplies transport. */
public final class DeletionOutbox {
    public static final class Entry {
        public final String id,resource,scope,state,error,resourceId;
        Entry(String id,String resource,String scope,String state,String error,String resourceId) {
            this.id=id;this.resource=resource;this.scope=scope;this.state=state;this.error=error;this.resourceId=resourceId;
        }
    }
    public static class PermanentFailure extends Exception {public PermanentFailure(String reason){super(reason);}}
    public static class SessionGone extends Exception { }
    public interface Transport {String send(Entry entry) throws Exception;}
    private final File directory;
    public DeletionOutbox(File directory){this.directory=directory;}
    public Entry add(String text,String scope) throws Exception {
        String resource=UUID.fromString(text).toString();validateScope(scope);
        Entry entry=new Entry(UUID.randomUUID().toString(),resource,scope,"queued","","");
        synchronized(DeletionOutbox.class){write(entry);}return entry;
    }
    private static String validateScope(String scope) {if(!scope.equals("devices")&&!scope.equals("devices_and_server"))throw new IllegalArgumentException("Alcance inválido");return scope;}
    private void write(Entry entry) throws IOException {
        if(!directory.exists()&&!directory.mkdirs()) throw new IOException("No se pudo guardar el borrado");
        Properties record=new Properties();record.setProperty("id",entry.id);record.setProperty("resource",entry.resource);record.setProperty("scope",entry.scope);
        record.setProperty("state",entry.state);record.setProperty("error",entry.error);record.setProperty("resource_id",entry.resourceId);
        File temporary=new File(directory,entry.id+".tmp"),target=new File(directory,entry.id+".properties");
        try(FileOutputStream output=new FileOutputStream(temporary)) {record.store(output,null);output.getFD().sync();}
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    public List<Entry> entries() throws IOException {
        synchronized(DeletionOutbox.class) {
            List<Entry> rows=new ArrayList<>();File[] files=directory.listFiles((dir,name)->name.endsWith(".properties"));
            if(files==null){if(directory.exists())throw new IOException("No se pudo leer la cola");return rows;}
            Arrays.sort(files,Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
            for(File file:files) {
                if(Files.isSymbolicLink(file.toPath())||file.length()>16384)throw new IOException("Registro de cola inválido");
                Properties values=new Properties();try(FileInputStream input=new FileInputStream(file)){values.load(input);}
                String id=UUID.fromString(values.getProperty("id")).toString();
                if(!file.getName().equals(id+".properties"))throw new IOException("Registro de cola inválido");
                rows.add(new Entry(id,UUID.fromString(values.getProperty("resource")).toString(),validateScope(values.getProperty("scope")),values.getProperty("state","queued"),values.getProperty("error",""),values.getProperty("resource_id","")));
            }
            return rows;
        }
    }
    private boolean commit(Entry entry,Object lock,java.util.function.BooleanSupplier active) throws IOException {
        synchronized(lock){if(!active.getAsBoolean())return false;synchronized(DeletionOutbox.class){write(entry);return true;}}
    }
    public boolean flush(Transport transport) throws Exception {return flush(transport,new Object(),()->true);}
    public boolean flush(Transport transport,Object lock,java.util.function.BooleanSupplier active) throws Exception {
        boolean done=true;
        for(Entry entry:entries()) {
            if(!entry.state.equals("queued"))continue;
            if(!active.getAsBoolean())return false;
            try {
                String resource=transport.send(entry);
                if(resource==null||resource.isEmpty())throw new IOException("Respuesta incompleta del servidor");
                if(!commit(new Entry(entry.id,entry.resource,entry.scope,"submitted","",resource),lock,active))return false;
            }catch(SessionGone e){return false;}
            catch(PermanentFailure e){if(!commit(new Entry(entry.id,entry.resource,entry.scope,"error",e.getMessage(),""),lock,active))return false;}
            catch(Exception e){if(!commit(new Entry(entry.id,entry.resource,entry.scope,"queued","Pendiente de conexión o reintento",""),lock,active))return false;done=false;break;}
        }
        return done;
    }
    public void retry(String id) throws Exception {
        synchronized(DeletionOutbox.class){for(Entry entry:entries())if(entry.id.equals(id))write(new Entry(entry.id,entry.resource,entry.scope,"queued","",""));}
    }
}
