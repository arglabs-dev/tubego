package dev.arglabs.tubego;
import android.app.job.*;
import android.content.*;
import android.os.PersistableBundle;
public final class MaintenanceJobService extends JobService {
    public static void schedule(Context c,String origin){PersistableBundle extras=new PersistableBundle();extras.putString("server_url",origin);JobScheduler scheduler=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);scheduler.schedule(new JobInfo.Builder(700000+(origin.hashCode()&0xffff),new ComponentName(c,MaintenanceJobService.class)).setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());}
    @Override public boolean onStartJob(JobParameters p){new Thread(()->{boolean retry=false;try{MaintenanceCommands.flush(this,p.getExtras().getString("server_url"));}catch(Exception e){retry=true;}jobFinished(p,retry);},"maintenance-outbox").start();return true;}
    @Override public boolean onStopJob(JobParameters p){return true;}
}
