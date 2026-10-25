package dev.arglabs.tubego;

/** Completion/process updates are intentionally outside the actionable channels. */
public final class AlertPolicy {
    private AlertPolicy(){}
    public static boolean accepts(String kind,boolean administrator){
        if("download_failed".equals(kind)||"device_storage".equals(kind))return true;
        return administrator && ("server_storage_paused".equals(kind)||"registration_pending".equals(kind)||"admin_maintenance".equals(kind));
    }
    public static boolean administrative(String kind){return "server_storage_paused".equals(kind)||"registration_pending".equals(kind)||"admin_maintenance".equals(kind);}
    public interface Store {boolean seen(String identity);void mark(String identity);}
    public static boolean remember(Store store,String identity,String kind,boolean administrator){
        if(!accepts(kind,administrator)||store.seen(identity))return false;
        store.mark(identity);return true;
    }
}
