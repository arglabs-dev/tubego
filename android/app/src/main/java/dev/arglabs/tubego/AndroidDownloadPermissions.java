package dev.arglabs.tubego;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

/** App-private durable grants; no passwords or bearer tokens are stored here. */
public final class AndroidDownloadPermissions {
    private AndroidDownloadPermissions() {}
    public static DownloadPermissions create(Context context) {
        SharedPreferences prefs=context.getSharedPreferences("tubego_transfer_grants",Context.MODE_PRIVATE);
        return new DownloadPermissions(new DownloadPermissions.Store() {
            @Override public boolean contains(String key) { return prefs.getBoolean(key,false); }
            @Override public void add(String key) {
                if(!prefs.edit().putBoolean(key,true).commit()) throw new IllegalStateException("No se pudo guardar la autorización");
            }
            @Override public void remove(String key) {
                if(!prefs.edit().remove(key).commit()) throw new IllegalStateException("No se pudo retirar la autorización");
            }
            @Override public Set<String> keys() { return new HashSet<>(prefs.getAll().keySet()); }
        });
    }
}
