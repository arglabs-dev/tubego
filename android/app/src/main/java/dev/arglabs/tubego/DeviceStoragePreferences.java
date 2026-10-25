package dev.arglabs.tubego;

/** Device-scoped preference keys include server, account and device incarnation. */
public final class DeviceStoragePreferences {
    public interface Store{int get(String key,int fallback);void put(String key,int value);}
    private final Store store;private final String key;
    public DeviceStoragePreferences(Store store,String origin,String user,String device){this.store=store;this.key=TransferKey.accountPrefix(origin,user,device)+"minimum_free_percent";}
    public int threshold(){return DeviceStoragePolicy.validPercent(store.get(key,DeviceStoragePolicy.DEFAULT_PERCENT));}
    public void threshold(int value){store.put(key,DeviceStoragePolicy.validPercent(value));}
}
