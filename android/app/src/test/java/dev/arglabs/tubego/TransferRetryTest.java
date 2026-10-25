package dev.arglabs.tubego;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.io.*;
import java.net.SocketTimeoutException;
import java.util.UUID;

public class TransferRetryTest {
    private TransferRecord record()throws Exception{return new TransferRecord(Files.createTempDirectory("retry").toFile(),UUID.randomUUID().toString(),"a".repeat(64),10,"Lesson","");}
    @Test public void boundedDelaysPersistAndManualRetryRestoresBudget()throws Exception{
        TransferRecord r=record();long now=1000;
        for(int n=0;n<3;n++){TransferRetry.failed(r,new SocketTimeoutException("secret"),now);assertEquals(now+TransferRetry.DELAYS[n],r.nextRetryAt);assertEquals("retry_wait",r.state);r=TransferRecord.read(r.manifest());assertEquals(n+1,r.failures);assertFalse(r.message.contains("secret"));}
        TransferRetry.failed(r,new SocketTimeoutException(),now);assertEquals("failed",r.state);assertEquals(0,r.nextRetryAt);assertEquals(4,r.failures);
        TransferRetry.reset(r);assertEquals(0,r.failures);assertEquals("pending",r.state);
        TransferRetry.failed(r,new SocketTimeoutException(),now);assertEquals(now+30000,r.nextRetryAt);
    }
    @Test public void permanentErrorsDoNotScheduleRetry()throws Exception{
        TransferRecord r=record();TransferRetry.failed(r,new TransferRetry.Failure("http_404",false),1);assertEquals("failed",r.state);assertEquals(0,r.nextRetryAt);
        assertFalse(TransferRetry.transientError(new IOException("unknown")));
        assertTrue(TransferRetry.transientError(new TransferRetry.Failure("http_503",true)));
    }
    @Test public void pauseAndMetadataReconciliationPreserveCorrectBudget()throws Exception{
        TransferRecord r=record();TransferRetry.failed(r,new SocketTimeoutException(),1000);r.state="paused";r.save();
        r=TransferRecord.read(r.manifest());assertEquals(1,r.failures);assertEquals(31000,r.nextRetryAt);
        r.reconcile("b".repeat(64),20);assertEquals(0,r.failures);assertEquals(0,r.nextRetryAt);
    }

    @Test public void newTerminalFailureGetsDurableDistinctIncidentAfterManualRetry()throws Exception{
        TransferRecord r=record();TransferRetry.failed(r,new TransferRetry.Failure("http_404",false),1);String first=r.failureIncident;
        assertFalse(first.isEmpty());assertEquals(first,TransferRecord.read(r.manifest()).failureIncident);
        TransferRetry.reset(r);TransferRetry.failed(r,new TransferRetry.Failure("http_404",false),2);assertNotEquals(first,r.failureIncident);
    }
}
