package dev.arglabs.tubego;
import android.app.*;
import android.app.job.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TransferJobService extends JobService {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<Integer,TransferRuntime.Control> controls=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer,Future<?>> futures=new ConcurrentHashMap<>();
    @Override public boolean onStartJob(JobParameters params) {
        String origin=params.getExtras().getString("origin");if(origin==null)return false;
        TransferRuntime.Control control=new TransferRuntime.Control();controls.put(params.getJobId(),control);
        NotificationManager notices=getSystemService(NotificationManager.class);
        notices.createNotificationChannel(new NotificationChannel("transfers","Descargas en curso",NotificationManager.IMPORTANCE_LOW));
        if(android.os.Build.VERSION.SDK_INT<33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED) notices.notify(params.getJobId(),new Notification.Builder(this,"transfers").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Tubego · Descargas en curso").setContentText("Las transferencias respetan tus permisos de red.").setOngoing(true).build());
        futures.put(params.getJobId(),executor.submit(()->{
            TransferDriver.Outcome outcome=TransferDriver.Outcome.RETRY;
            try {outcome=TransferDriver.run(this,origin,control);}catch(Exception ignored){}
            finally {
                notices.cancel(params.getJobId());controls.remove(params.getJobId());futures.remove(params.getJobId());
                if(!control.stopped) {
                                        boolean needsAny=outcome==TransferDriver.Outcome.RETRY || outcome==TransferDriver.Outcome.WAIT_NETWORK;
                    boolean wasWifi=params.getJobId()==TransferJobs.wifiJobId(origin);
                    jobFinished(params,needsAny && !wasWifi && outcome!=TransferDriver.Outcome.RETRY);
                    if(outcome==TransferDriver.Outcome.RETRY)new android.os.Handler(getMainLooper()).postDelayed(()->TransferJobs.wakeAt(this,origin,false,control.retryAt),250);
                    if(needsAny && wasWifi && outcome!=TransferDriver.Outcome.RETRY)TransferJobs.wake(this,origin,false);
                    if(outcome==TransferDriver.Outcome.WAIT_WIFI) TransferJobs.wake(this,origin,true);
                }
            }
        }));return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        TransferRuntime.Control control=controls.get(params.getJobId());if(control!=null)control.stop();
        Future<?> future=futures.get(params.getJobId());if(future!=null)future.cancel(true);
        getSystemService(NotificationManager.class).cancel(params.getJobId());return true;
    }
    @Override public void onDestroy(){for(TransferRuntime.Control c:controls.values())c.stop();executor.shutdownNow();super.onDestroy();}
}
