package dev.arglabs.tubego;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

/** Default-network observation; never equates unmetered cellular with Wi-Fi. */
public final class NetworkMonitor implements AutoCloseable {
    public interface Listener { void changed(DownloadPolicy.NetworkState state); }
    private final ConnectivityManager manager;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final ConnectivityManager.NetworkCallback callback;
    private volatile boolean closed;
    public NetworkMonitor(Context context,Listener listener) {
        this.manager=context.getApplicationContext().getSystemService(ConnectivityManager.class);
        this.listener=listener;
        callback=new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { notifyCurrent(); }
            @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities) { notifyCurrent(); }
            @Override public void onLost(Network network) { notifyCurrent(); }
            @Override public void onUnavailable() { notifyCurrent(); }
        };
        manager.registerDefaultNetworkCallback(callback);
        notifyCurrent();
    }
    public DownloadPolicy.NetworkState current() {
        Network active=manager.getActiveNetwork();
        NetworkCapabilities caps=active==null?null:manager.getNetworkCapabilities(active);
        if(caps==null) return DownloadPolicy.NetworkState.offline();
        return new DownloadPolicy.NetworkState(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
    }
    private void notifyCurrent() { main.post(()->{if(!closed) listener.changed(current());}); }
    @Override public void close() { if(!closed) {closed=true;manager.unregisterNetworkCallback(callback);} }
}
