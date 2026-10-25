package dev.arglabs.tubego;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.Set;

public final class DownloadPolicyTest {
    private static final String DIGEST="a".repeat(64);
    private static final DownloadPolicy.NetworkState WIFI=new DownloadPolicy.NetworkState(true,true,true,false,false);
    private static final DownloadPolicy.NetworkState CELL=new DownloadPolicy.NetworkState(true,true,false,true,true);
    static final class MemoryStore implements DownloadPermissions.Store {
        final Set<String> keys=new HashSet<>();
        public boolean contains(String key) {return keys.contains(key);}
        public void add(String key) {keys.add(key);}
        public void remove(String key) {keys.remove(key);}
        public Set<String> keys() {return new HashSet<>(keys);}
    }
    private TransferKey key(String resource) {return new TransferKey("https://tubego.example","alice","phone",resource,DIGEST);}
    @Test public void wifiDefaultIncludesMeteredHotspotButNotFreeCellular() {
        assertTrue(DownloadPolicy.allowsMedia(WIFI,false));
        assertTrue(DownloadPolicy.allowsMedia(new DownloadPolicy.NetworkState(true,true,true,false,true),false));
        assertFalse(DownloadPolicy.allowsMedia(CELL,false));
        assertFalse(DownloadPolicy.allowsMedia(new DownloadPolicy.NetworkState(true,true,false,true,false),false));
        assertFalse(DownloadPolicy.allowsMedia(new DownloadPolicy.NetworkState(true,true,false,false,false),false));
    }
    @Test public void offlineCaptivePortalAndAmbiguousNetworkFailClosed() {
        assertFalse(DownloadPolicy.allowsMedia(null,true));
        assertFalse(DownloadPolicy.allowsMedia(DownloadPolicy.NetworkState.offline(),true));
        assertFalse(DownloadPolicy.allowsMedia(new DownloadPolicy.NetworkState(true,false,true,false,false),true));
        assertFalse(DownloadPolicy.allowsMedia(new DownloadPolicy.NetworkState(false,true,true,false,false),true));
        assertFalse(DownloadPolicy.allowsMedia(new DownloadPolicy.NetworkState(true,true,true,true,false),false));
        assertTrue(DownloadPolicy.allowsMedia(new DownloadPolicy.NetworkState(true,true,true,true,false),true));
    }
    @Test public void controlTrafficIsIndependentOfMediaPermission() {
        assertTrue(DownloadPolicy.allowsControl(CELL));
        assertTrue(DownloadPolicy.allowsControl(new DownloadPolicy.NetworkState(true,true,false,false,true)));
        assertFalse(DownloadPolicy.allowsMedia(CELL,false));
        assertFalse(DownloadPolicy.allowsControl(DownloadPolicy.NetworkState.offline()));
    }
    @Test public void wifiLossPausesOnlyUnauthorizedFilesAndResumesWhenWifiReturns() {
        assertTrue(DownloadPolicy.allowsMedia(WIFI,false));
        assertEquals(DownloadPolicy.Decision.WAIT_WIFI,DownloadPolicy.mediaDecision(CELL,false));
        assertEquals(DownloadPolicy.Decision.ALLOW_AUTHORIZED_MOBILE,DownloadPolicy.mediaDecision(CELL,true));
        assertEquals(DownloadPolicy.Decision.WAIT_NETWORK,DownloadPolicy.mediaDecision(DownloadPolicy.NetworkState.offline(),true));
        assertTrue(DownloadPolicy.allowsMedia(WIFI,false));
    }
    @Test public void authorizationNeverSpreadsAcrossAccountsServersDevicesFilesOrVersions() {
        DownloadPermissions permissions=new DownloadPermissions(new MemoryStore());TransferKey file=key("video");permissions.authorize(file);
        assertTrue(permissions.authorized(file));assertFalse(permissions.authorized(key("audio")));
        assertFalse(permissions.authorized(new TransferKey(file.origin,"bob",file.deviceId,file.resourceId,DIGEST)));
        assertFalse(permissions.authorized(new TransferKey(file.origin,file.userId,"other-phone",file.resourceId,DIGEST)));
        assertFalse(permissions.authorized(new TransferKey("https://other.example",file.userId,file.deviceId,file.resourceId,DIGEST)));
        assertFalse(permissions.authorized(new TransferKey(file.origin,file.userId,file.deviceId,file.resourceId,"b".repeat(64))));
    }
    @Test public void authorizationSurvivesRestartRetryPauseButEndsOnCompletionOrCancellation() {
        MemoryStore durable=new MemoryStore();TransferKey file=key("video");new DownloadPermissions(durable).authorize(file);
        DownloadPermissions restarted=new DownloadPermissions(durable);assertTrue(restarted.authorized(file));
        assertFalse(DownloadPolicy.allowsMedia(DownloadPolicy.NetworkState.offline(),restarted.authorized(file)));
        assertTrue(restarted.authorized(file));assertTrue(DownloadPolicy.allowsMedia(CELL,restarted.authorized(file)));
        restarted.revoke(file);assertFalse(new DownloadPermissions(durable).authorized(file));
    }
    @Test public void revocationClearsOnlyAffectedAccountDeviceNamespace() {
        DownloadPermissions permissions=new DownloadPermissions(new MemoryStore());TransferKey first=key("video"),second=key("audio");
        TransferKey other=new TransferKey(first.origin,"bob",first.deviceId,first.resourceId,DIGEST);
        permissions.authorize(first);permissions.authorize(second);permissions.authorize(other);
        permissions.revokeAccount(first.origin,first.userId,first.deviceId);
        assertFalse(permissions.authorized(first));assertFalse(permissions.authorized(second));assertTrue(permissions.authorized(other));
    }
    @Test public void clearingServerSessionRevokesOnlyItsOrigin() {
        DownloadPermissions permissions=new DownloadPermissions(new MemoryStore());TransferKey file=key("video");
        TransferKey other=new TransferKey("https://other.example",file.userId,file.deviceId,file.resourceId,DIGEST);
        permissions.authorize(file);permissions.authorize(other);
        permissions.revokeOrigin(file.origin);
        assertFalse(permissions.authorized(file));assertTrue(permissions.authorized(other));
    }
    @Test(expected=IllegalArgumentException.class) public void unknownChecksumCannotReceivePermission() {
        new TransferKey("https://example.com","alice","phone","video","");
    }
}
