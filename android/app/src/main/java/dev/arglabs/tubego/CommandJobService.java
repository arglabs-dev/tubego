package dev.arglabs.tubego;
import android.app.job.*;
import java.util.concurrent.*;
public final class CommandJobService extends JobService{
 private final ExecutorService network=Executors.newSingleThreadExecutor();
 @Override public boolean onStartJob(JobParameters params){network.execute(()->jobFinished(params,!CommandDispatch.flush(this)));return true;}
 @Override public boolean onStopJob(JobParameters params){return true;}
 @Override public void onDestroy(){network.shutdownNow();super.onDestroy();}
}
