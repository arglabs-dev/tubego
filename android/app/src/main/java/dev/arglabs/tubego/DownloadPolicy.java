package dev.arglabs.tubego;

/** Pure rules shared by video/audio transfers; control requests are independent. */
public final class DownloadPolicy {
    private DownloadPolicy() {}
    public static final class NetworkState {
        public final boolean internet, validated, wifi, cellular, metered;
        public NetworkState(boolean internet, boolean validated, boolean wifi, boolean cellular, boolean metered) {
            this.internet=internet; this.validated=validated; this.wifi=wifi; this.cellular=cellular; this.metered=metered;
        }
        public static NetworkState offline() { return new NetworkState(false,false,false,false,true); }
    }
    public enum Decision { ALLOW_WIFI, ALLOW_AUTHORIZED_MOBILE, WAIT_NETWORK, WAIT_WIFI }
    public static boolean allowsControl(NetworkState network) {
        return network!=null && network.internet && network.validated;
    }
    public static Decision mediaDecision(NetworkState network, boolean authorized) {
        if (!allowsControl(network)) return Decision.WAIT_NETWORK;
        // A metered Wi-Fi hotspot still matches the user's explicit Wi-Fi rule.
        // Ambiguous simultaneous Wi-Fi+cellular must not silently consume mobile data.
        if (network.wifi && !network.cellular) return Decision.ALLOW_WIFI;
        if (network.cellular && authorized) return Decision.ALLOW_AUTHORIZED_MOBILE;
        return Decision.WAIT_WIFI;
    }
    public static boolean allowsMedia(NetworkState network, boolean authorized) {
        Decision decision=mediaDecision(network,authorized);
        return decision==Decision.ALLOW_WIFI || decision==Decision.ALLOW_AUTHORIZED_MOBILE;
    }
}
