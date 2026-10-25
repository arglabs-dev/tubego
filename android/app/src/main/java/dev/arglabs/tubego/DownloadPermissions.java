package dev.arglabs.tubego;

import java.util.Set;

/** Durable explicit exceptions. Retry/pause never consumes a grant. */
public final class DownloadPermissions {
    public interface Store {
        boolean contains(String key);
        void add(String key);
        void remove(String key);
        Set<String> keys();
    }
    private final Store store;
    public DownloadPermissions(Store store) { this.store=store; }
    public boolean authorized(TransferKey transfer) { return store.contains(transfer.storageKey()); }
    /** Call only after an explicit UI confirmation for this specific file. */
    public void authorize(TransferKey transfer) { store.add(transfer.storageKey()); }
    /** Completion/cancellation/revocation ends the exception, not temporary pauses. */
    public void revoke(TransferKey transfer) { store.remove(transfer.storageKey()); }
    public void revokeOrigin(String origin) {
        String prefix=TransferKey.originPrefix(origin);
        for(String key:store.keys()) if(key.startsWith(prefix)) store.remove(key);
    }
    public void revokeAccount(String origin,String userId,String deviceId) {
        String prefix=TransferKey.accountPrefix(origin,userId,deviceId);
        for(String key:store.keys()) if(key.startsWith(prefix)) store.remove(key);
    }
}
