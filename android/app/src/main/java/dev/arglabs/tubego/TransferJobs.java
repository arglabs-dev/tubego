package dev.arglabs.tubego;
import android.app.job.*;
import android.content.*;
import android.net.*;
import android.os.PersistableBundle;

/** Ordinary persistent jobs: system may defer execution or stop long transfers. */
public final class TransferJobs {
    public static int jobId(String origin,boolean periodic){return 500000+(origin.hashCode()&0x0fffffff)*4+(periodic?1:0);}
    public static void register(Context context,String origin) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);PersistableBundle extras=new PersistableBundle();extras.putString("origin",origin);
        int id=jobId(origin,true);
        if(scheduler.getPendingJob(id)==null) scheduler.schedule(new JobInfo.Builder(id,new ComponentName(context,TransferJobService.class))
            .setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15*60*1000L).build());
        wake(context,origin,false);
    }
    public static void wake(Context context,String origin,boolean wifiOnly) {
        wakeAt(context,origin,wifiOnly,0);
    }
    public static void wakeAt(Context context,String origin,boolean wifiOnly,long retryAt) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);int id=wifiOnly?wifiJobId(origin):jobId(origin,false);
        // Scheduling the same running job cancels it. Preserve existing work.
        if(scheduler.getPendingJob(id)!=null)return;
        PersistableBundle extras=new PersistableBundle();extras.putString("origin",origin);
        JobInfo.Builder job=new JobInfo.Builder(id,new ComponentName(context,TransferJobService.class)).setExtras(extras).setPersisted(true)
            .setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if(retryAt>System.currentTimeMillis())job.setMinimumLatency(retryAt-System.currentTimeMillis());
        if(wifiOnly) job.setRequiredNetwork(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build());
        else job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        scheduler.schedule(job.build());
    }
    public static int wifiJobId(String origin) {return jobId(origin,false)+2;}
    public static void stop(Context context,String origin) {
        TransferRuntime.stopOrigin(origin);
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);scheduler.cancel(jobId(origin,false));scheduler.cancel(jobId(origin,true));scheduler.cancel(wifiJobId(origin));
    }
}
