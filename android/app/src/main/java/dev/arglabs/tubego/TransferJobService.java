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
        notices.createNotificationChannel(new NotificationChannel("transfers",Texts.forOrigin(this,origin,"Descargas en curso"),NotificationManager.IMPORTANCE_LOW));
        if(android.os.Build.VERSION.SDK_INT<33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED) notices.notify(params.getJobId(),new Notification.Builder(this,"transfers").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(Texts.forOrigin(this,origin,"Tubego · Descargas en curso")).setContentText(Texts.forOrigin(this,origin,"Las transferencias respetan tus permisos de red.")).setOngoing(true).build());
        futures.put(params.getJobId(),executor.submit(()->{
            TransferDriver.Outcome outcome=TransferDriver.Outcome.RETRY;Exception failure=null;
            try {try{AlertSync.poll(this,origin);}catch(Exception ignoredAlerts){}outcome=TransferDriver.run(this,origin,control);}catch(Exception error){failure=error;}
            finally {
                if(!control.stopped)TransferDiagnostics.finish(this,origin,control,outcome,failure);
                try{org.json.JSONObject current=new SessionStore(this,origin).read();if(current!=null)AlertSync.local(this,origin,current);}catch(Exception ignoredAlerts){}
                notices.cancel(params.getJobId());controls.remove(params.getJobId());futures.remove(params.getJobId());
                if(!control.stopped) {
                    boolean wasWifi=params.getJobId()==TransferJobs.wifiJobId(origin);
                    boolean retry=outcome==TransferDriver.Outcome.RETRY || outcome==TransferDriver.Outcome.WAIT_SPACE
                        || outcome==TransferDriver.Outcome.WAIT_NETWORK && !wasWifi;
                    // Persist retries in the scheduler: a Handler callback disappears if Android kills this process.
                    jobFinished(params,retry);
                    if(outcome==TransferDriver.Outcome.WAIT_NETWORK && wasWifi)TransferJobs.wake(this,origin,false);
                    if(outcome==TransferDriver.Outcome.WAIT_WIFI)TransferJobs.wake(this,origin,true);
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
