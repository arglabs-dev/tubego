package dev.arglabs.tubego;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import java.io.*;
import java.nio.file.Files;

/** Samples the physical app-private volume, never cache/Telegram storage. */
public final class AndroidStorageGuard implements ResumableTransfer.StorageGuard {
    private final File path;private final SharedPreferences preferences;private final String prefix;
    public final DeviceStoragePreferences settings;
    public AndroidStorageGuard(Context context,String origin,String user,String device){
        path=context.getFilesDir();preferences=context.getSharedPreferences("tubego_device_storage",Context.MODE_PRIVATE);
        prefix=TransferKey.accountPrefix(origin,user,device);
        settings=new DeviceStoragePreferences(new DeviceStoragePreferences.Store(){
            public int get(String key,int fallback){return preferences.getInt(key,fallback);}
            public void put(String key,int value){if(!preferences.edit().putInt(key,value).commit())throw new IllegalStateException("No se pudo guardar el umbral");}
        },origin,user,device);
    }
    public DeviceStoragePolicy.Decision sample(long remaining){
        try{StatFs fs=new StatFs(path.getAbsolutePath());return DeviceStoragePolicy.evaluate(fs.getTotalBytes(),fs.getAvailableBytes(),remaining,settings.threshold());}
        catch(Exception e){return DeviceStoragePolicy.evaluate(0,0,remaining,DeviceStoragePolicy.DEFAULT_PERCENT);}
    }
    @Override public boolean allows(long remaining){DeviceStoragePolicy.Decision state=sample(remaining);if(state.paused)notice(state);return !state.paused;}
    @Override public void noSpace(){DeviceStoragePolicy.Decision state=sample(0);notice(new DeviceStoragePolicy.Decision(state.total,state.available,state.reserve,0,true,"no_space"));}
    private void notice(DeviceStoragePolicy.Decision state){
        var editor=preferences.edit().putBoolean(prefix+"low_space_active",true).putString(prefix+"low_space_reason",state.reason)
            .putLong(prefix+"available_bytes",state.available).putLong(prefix+"total_bytes",state.total);
        if(!preferences.getBoolean(prefix+"low_space_active",false))editor.putLong(prefix+"low_space_sequence",preferences.getLong(prefix+"low_space_sequence",0)+1);
        editor.commit();
    }
    public void recovered(){if(!sample(0).paused)preferences.edit().putBoolean(prefix+"low_space_active",false).remove(prefix+"low_space_reason").commit();}
    public static long used(File root)throws IOException{
        if(!root.exists()||Files.isSymbolicLink(root.toPath()))return 0;
        if(root.isFile())return root.length();
        File[] files=root.listFiles();if(files==null)throw new IOException("No se pudo medir el almacenamiento");
        long sum=0;for(File file:files){long bytes=used(file);sum=bytes>Long.MAX_VALUE-sum?Long.MAX_VALUE:sum+bytes;}return sum;
    }
}
