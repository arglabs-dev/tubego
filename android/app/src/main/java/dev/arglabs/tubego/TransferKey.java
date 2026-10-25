package dev.arglabs.tubego;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** File-specific permission identity; no token or credential is included. */
public final class TransferKey {
    public final String origin, userId, deviceId, resourceId, sha256;
    public TransferKey(String origin,String userId,String deviceId,String resourceId,String sha256) {
        if (origin==null || origin.isEmpty() || userId==null || userId.isEmpty() || deviceId==null || deviceId.isEmpty()
                || resourceId==null || resourceId.isEmpty() || sha256==null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Identidad de transferencia incompleta");
        }
        this.origin=origin;this.userId=userId;this.deviceId=deviceId;this.resourceId=resourceId;this.sha256=sha256;
    }
    private static String digest(String... fields) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String field:fields) digest.update((field.length()+":"+field).getBytes(StandardCharsets.UTF_8));
            StringBuilder text=new StringBuilder();for(byte value:digest.digest()) text.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            return text.toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public static String accountPrefix(String origin,String userId,String deviceId) { return originPrefix(origin)+digest(userId,deviceId)+"."; }
    public static String originPrefix(String origin) {return "grant."+digest(origin)+".";}
    public String storageKey() { return accountPrefix(origin,userId,deviceId)+digest(resourceId,sha256); }
}
