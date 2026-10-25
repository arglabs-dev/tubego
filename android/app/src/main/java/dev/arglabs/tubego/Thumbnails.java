package dev.arglabs.tubego;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.widget.ImageView;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

/** Bounded previews, retrieved only from the account's API; private cache follows logout cleanup. */
final class Thumbnails {
    private Thumbnails() {}
    private static final java.util.concurrent.ExecutorService IO=new java.util.concurrent.ThreadPoolExecutor(
        2,2,30,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(64),
        new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    static Bitmap decode(String encoded) {
        try {
            if(encoded==null||encoded.length()>44000)return null;
            return decode(Base64.decode(encoded,Base64.DEFAULT));
        } catch(Exception ignored){return null;}
    }
    private static Bitmap decode(byte[] data) {
        if(data.length>32768)return null;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,options);
        if(options.outWidth<=0||options.outHeight<=0||(long)options.outWidth*options.outHeight>4_000_000)return null;
        options.inJustDecodeBounds=false;options.inSampleSize=1;
        while(options.outWidth/options.inSampleSize>640||options.outHeight/options.inSampleSize>640)options.inSampleSize*=2;
        return BitmapFactory.decodeByteArray(data,0,data.length,options);
    }
    static ImageView view(android.content.Context context) {
        ImageView image=new ImageView(context);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(Ds.rounded(context,Ds.SOFT,0,12));image.setClipToOutline(true);
        image.setImageResource(R.drawable.ic_ds_play);image.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }
    private static final android.util.LruCache<String,Bitmap> MEMORY=new android.util.LruCache<String,Bitmap>(8*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private static final Object[] LOCKS=new Object[32];
    static {for(int i=0;i<LOCKS.length;i++)LOCKS[i]=new Object();}
    static void clearMemory(){MEMORY.evictAll();}
    private static void save(Host host,File root,String id,String token,byte[] bytes)throws Exception {
        synchronized(SessionStore.class){
            if(!token.equals(new SessionStore(host.act(),host.origin()).token())||LocalResourceDeletion.deleted(root,id))return;
            if(!root.isDirectory()&&!root.mkdirs())return;
            File target=new File(root,id+".thumbnail"),temp=new File(root,id+".thumbnail.tmp");
            Files.write(temp.toPath(),bytes);
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static void load(Host host,ImageView image,Item item) {
        if(item.id.isEmpty()||item.outbox)return;
        Bitmap immediate=MEMORY.get(item.thumbnailKey);
        if(immediate!=null){image.setImageBitmap(immediate);if(BuildConfig.DEBUG)android.util.Log.d("TubeGoThumbnails","memory cache hit");return;}
        IO.execute(()->{
            try {
                if(host.act().isDestroyed())return;
                final JSONObject session;final File root;final String token;
                synchronized(SessionStore.class){
                    session=new SessionStore(host.act(),host.origin()).read();if(session==null)return;
                    token=session.getString("token");root=LinkOutboxDispatch.root(host.act(),host.origin(),session);
                }
                String id=java.util.UUID.fromString(item.id).toString();File cache=new File(root,id+".thumbnail");
                if(LocalResourceDeletion.deleted(root,id))return;
                String key=root.getAbsolutePath()+"/"+id;
                Bitmap bitmap;
                synchronized(LOCKS[(key.hashCode()&0x7fffffff)%LOCKS.length]) {
                    bitmap=MEMORY.get(key);
                    if(bitmap==null&&!Files.isSymbolicLink(cache.toPath())&&cache.isFile()&&cache.length()<=32768) {
                        bitmap=decode(Files.readAllBytes(cache.toPath()));
                        if(BuildConfig.DEBUG)android.util.Log.d("TubeGoThumbnails","disk cache hit");
                    }
                    // Existing downloads never wait for the network to produce a cover.
                    if(bitmap==null&&item.downloaded&&!item.isAudio()) {
                        android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();
                        try {
                            TransferRecord record=OfflineMediaAccess.record(root,id);
                            if(SharedMedia.available(host.act(),record)) {
                                if(record.sharedUri.isEmpty())retriever.setDataSource(record.media().getAbsolutePath());
                                else retriever.setDataSource(host.act(),android.net.Uri.parse(record.sharedUri));
                                bitmap=retriever.getScaledFrameAtTime(1_000_000,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,180);
                            }
                        } finally {retriever.release();}
                        if(bitmap!=null){
                            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
                            bitmap.compress(Bitmap.CompressFormat.JPEG,75,out);
                            if(out.size()>32768){out.reset();bitmap.compress(Bitmap.CompressFormat.JPEG,35,out);}
                            if(out.size()<=32768)save(host,root,id,token,out.toByteArray());
                            if(BuildConfig.DEBUG)android.util.Log.d("TubeGoThumbnails","local video cover cached");
                        }
                    }
                    if(bitmap==null&&item.hasThumbnail&&DownloadPolicy.allowsControl(host.network())) {
                        if(BuildConfig.DEBUG)android.util.Log.d("TubeGoThumbnails","fetching missing source preview");
                        JSONObject response=new ApiClient(host.origin()).request("GET","/resources/"+id+"/thumbnail",null,token);
                        String encoded=response.optString("thumbnail_base64","");bitmap=decode(encoded);
                        if(bitmap!=null)save(host,root,id,token,Base64.decode(encoded,Base64.DEFAULT));
                    }
                    if(bitmap!=null)synchronized(SessionStore.class){if(!token.equals(new SessionStore(host.act(),host.origin()).token()))return;MEMORY.put(key,bitmap);}
                }
                final Bitmap result=bitmap;
                if(result!=null)host.act().runOnUiThread(()->{
                    try { synchronized(SessionStore.class){
                        if(!host.act().isDestroyed()&&token.equals(new SessionStore(host.act(),host.origin()).token())&&!LocalResourceDeletion.deleted(root,id))image.setImageBitmap(result);
                    }} catch(Exception ignored) { }
                });
            }catch(Exception ignored){if(BuildConfig.DEBUG)android.util.Log.d("TubeGoThumbnails","preview unavailable; keeping placeholder");}
        });
    }
}
