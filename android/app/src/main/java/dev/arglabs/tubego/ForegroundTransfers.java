package dev.arglabs.tubego;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Visible-library work starts immediately; background delivery still belongs to JobScheduler. */
final class ForegroundTransfers {
    interface Runner {TransferDriver.Outcome run(Context context,String origin,TransferRuntime.Control control)throws Exception;}
    private final Context context;
    private final Runner runner;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private TransferRuntime.Control control;
    private Future<?> future;
    private long nextAttempt;
    ForegroundTransfers(Context context){this(context,TransferDriver::run);}
    ForegroundTransfers(Context context,Runner runner){this.context=context.getApplicationContext();this.runner=runner;}
    synchronized void wake(String origin){
        if(future!=null&&!future.isDone()||System.currentTimeMillis()<nextAttempt)return;
        TransferRuntime.Control attempt=new TransferRuntime.Control();control=attempt;
        future=executor.submit(()->{
            TransferDriver.Outcome outcome=TransferDriver.Outcome.RETRY;Exception failure=null;
            try{outcome=runner.run(context,origin,attempt);}catch(Exception error){failure=error;}
            if(!attempt.stopped)TransferDiagnostics.finish(context,origin,attempt,outcome,failure);
            synchronized(this){nextAttempt=System.currentTimeMillis()+(outcome==TransferDriver.Outcome.RETRY?30000:4000);}
        });
    }
    synchronized void pause(){if(control!=null)control.stop();if(future!=null)future.cancel(true);nextAttempt=0;}
    synchronized void close(){pause();executor.shutdownNow();}
}
