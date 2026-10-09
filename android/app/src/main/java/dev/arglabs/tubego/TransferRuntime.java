package dev.arglabs.tubego;
import java.net.HttpURLConnection;
import java.util.concurrent.ConcurrentHashMap;

/** Synchronizes cleanup with active transfers so logout cannot recreate wiped files. */
public final class TransferRuntime {
    private static final ConcurrentHashMap<String,Object> locks=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Control> running=new ConcurrentHashMap<>();
    public static Object lock(String origin) {return locks.computeIfAbsent(origin,key->new Object());}
    public static final class Control {
        public volatile boolean stopped;
        public volatile HttpURLConnection connection;
        public volatile TransferKey transfer;
        public void stop() {stopped=true;HttpURLConnection current=connection;if(current!=null)current.disconnect();}
    }
    public static void register(String origin,Control control) {running.put(origin,control);}
    public static void unregister(String origin,Control control) {running.remove(origin,control);}
    public static void permissionRemoved(String origin,TransferKey key) {
        Control control=running.get(origin);
        if(control!=null && control.transfer!=null && control.transfer.storageKey().equals(key.storageKey())) {
            HttpURLConnection connection=control.connection;if(connection!=null)connection.disconnect();
        }
    }
    public static void stopOrigin(String origin) {Control control=running.get(origin);if(control!=null)control.stop();}
}
