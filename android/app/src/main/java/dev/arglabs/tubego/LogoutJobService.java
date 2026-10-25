package dev.arglabs.tubego;
import android.app.job.JobService;
import android.app.job.JobParameters;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
public final class LogoutJobService extends JobService {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    @Override public boolean onStartJob(JobParameters params) {
        network.execute(()->jobFinished(params,!SessionLifecycle.flush(this)));return true;
    }
    @Override public boolean onStopJob(JobParameters params) {return true;}
    @Override public void onDestroy(){network.shutdownNow();super.onDestroy();}
}
