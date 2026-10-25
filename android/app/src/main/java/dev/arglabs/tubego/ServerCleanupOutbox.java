package dev.arglabs.tubego;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Durable token-free request outbox. The owning account/device supplies transport. */
public final class ServerCleanupOutbox {
    public static final class Entry {
        public final String id,scope,state,error;
        Entry(String id,String scope,String state,String error) {
            this.id=id;this.scope=scope;this.state=state;this.error=error;
        }
    }
    public static class PermanentFailure extends Exception {public PermanentFailure(String reason){super(reason);}}
    public static class SessionGone extends Exception { }
    public interface Transport {String send(Entry entry) throws Exception;}
    private final File directory;
    public ServerCleanupOutbox(File directory){this.directory=directory;}
    public Entry add(String scope) throws Exception {
        validateScope(scope);
        Entry entry=new Entry(UUID.randomUUID().toString(),scope,"queued","");
        synchronized(ServerCleanupOutbox.class){write(entry);}return entry;
    }
    private static String validateScope(String scope) {if(!scope.equals("own")&&!scope.equals("global"))throw new IllegalArgumentException("Alcance inválido");return scope;}
    private void write(Entry entry) throws IOException {
        if(!directory.exists()&&!directory.mkdirs()) throw new IOException("No se pudo guardar la limpieza");
        Properties record=new Properties();record.setProperty("id",entry.id);record.setProperty("scope",entry.scope);
        record.setProperty("state",entry.state);record.setProperty("error",entry.error);
        if(entry.state.equals("queued"))CommandBridge.add(directory,entry.id,"server_cleanup",record);
        File temporary=new File(directory,entry.id+".tmp"),target=new File(directory,entry.id+".properties");
        try(FileOutputStream output=new FileOutputStream(temporary)) {record.store(output,null);output.getFD().sync();}
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    public List<Entry> entries() throws IOException {
        synchronized(ServerCleanupOutbox.class) {
            List<Entry> rows=new ArrayList<>();File[] files=directory.listFiles((dir,name)->name.endsWith(".properties"));
            if(files==null){if(directory.exists())throw new IOException("No se pudo leer la cola");return rows;}
            Arrays.sort(files,Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
            for(File file:files) {
                if(Files.isSymbolicLink(file.toPath())||file.length()>16384)throw new IOException("Registro de cola inválido");
                Properties values=new Properties();try(FileInputStream input=new FileInputStream(file)){values.load(input);}
                String id=UUID.fromString(values.getProperty("id")).toString();
                if(!file.getName().equals(id+".properties"))throw new IOException("Registro de cola inválido");
                rows.add(new Entry(id,validateScope(values.getProperty("scope")),values.getProperty("state","queued"),values.getProperty("error","")));
            }
            return rows;
        }
    }
    private boolean commit(Entry entry,Object lock,java.util.function.BooleanSupplier active) throws IOException {
        synchronized(lock){if(!active.getAsBoolean())return false;synchronized(ServerCleanupOutbox.class){write(entry);return true;}}
    }
    public boolean flush(Transport transport) throws Exception {return flush(transport,new Object(),()->true);}
    public boolean flush(Transport transport,Object lock,java.util.function.BooleanSupplier active) throws Exception {
        boolean done=true;
        for(Entry entry:entries()) {
            if(!entry.state.equals("queued"))continue;
            if(!active.getAsBoolean())return false;
            try {
                String receipt=transport.send(entry);
                if(receipt==null||receipt.isEmpty())throw new IOException("Respuesta incompleta del servidor");
                if(!commit(new Entry(entry.id,entry.scope,"submitted",""),lock,active))return false;
            }catch(SessionGone e){return false;}
            catch(PermanentFailure e){if(!commit(new Entry(entry.id,entry.scope,"error",e.getMessage()),lock,active))return false;}
            catch(Exception e){if(!commit(new Entry(entry.id,entry.scope,"queued","Pendiente de conexión o reintento"),lock,active))return false;done=false;break;}
        }
        return done;
    }
    public void retry(String id) throws Exception {
        synchronized(ServerCleanupOutbox.class){for(Entry entry:entries())if(entry.id.equals(id)){
            if(CommandBridge.production(directory)){if(!entry.state.equals("error"))throw new IOException("La acción no requiere reintento");add(entry.scope);return;}
            write(new Entry(entry.id,entry.scope,"queued",""));
        }}
    }
}
