package dev.arglabs.tubego;

import android.content.Context;
import android.test.InstrumentationTestCase;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
public final class ForegroundTransfersInstrumentedTest extends InstrumentationTestCase {
    public void testVisibleTransferStartsWithoutScheduledJobAndCoalescesWakeups() throws Exception {
        Context c=getInstrumentation().getTargetContext();String origin="https://foreground.example.invalid";
        TransferJobs.stop(c,origin);
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();AtomicReference<TransferRuntime.Control> active=new AtomicReference<>();
        ForegroundTransfers transfers=new ForegroundTransfers(c,(context,url,control)->{
            active.set(control);calls.incrementAndGet();started.countDown();release.await(5,TimeUnit.SECONDS);return TransferDriver.Outcome.DONE;
        });
        try {
            transfers.wake(origin);assertTrue(started.await(2,TimeUnit.SECONDS));
            for(int n=0;n<20;n++)transfers.wake(origin);
            assertEquals(1,calls.get());
            android.app.job.JobScheduler scheduler=c.getSystemService(android.app.job.JobScheduler.class);
            assertNull(scheduler.getPendingJob(TransferJobs.jobId(origin,false)));
            transfers.pause();assertTrue(active.get().stopped);
        } finally {release.countDown();transfers.close();}
    }
    public void testFailedStartRecordsPhaseAndSafeCodeInsteadOfSilentlyDisappearing() throws Exception {
        Context c=getInstrumentation().getTargetContext();String origin="https://foreground-failure.example.invalid";
        SessionStore store=new SessionStore(c,origin);
        JSONObject session=new JSONObject().put("token","foreground-fixture-token").put("status","approved").put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString());store.save(session);
        AtomicInteger calls=new AtomicInteger();
        ForegroundTransfers transfers=new ForegroundTransfers(c,(context,url,control)->{
            calls.incrementAndGet();control.sessionToken=session.getString("token");control.phase="device_sync";throw new java.io.IOException("private token and path must not be displayed");
        });
        try {
            transfers.wake(origin);JSONObject diagnostic=null;long until=System.currentTimeMillis()+3000;
            while(diagnostic==null&&System.currentTimeMillis()<until){diagnostic=TransferDiagnostics.read(c,origin);if(diagnostic==null)Thread.sleep(20);}
            assertNotNull(diagnostic);assertEquals("device_sync",diagnostic.getString("phase"));assertEquals("local_or_network_io",diagnostic.getString("error_code"));assertFalse(diagnostic.toString().contains("private token"));
            transfers.wake(origin);assertEquals(1,calls.get());
        } finally {transfers.close();store.clear();LocalLibraryStorage.wipe(c,origin,session.getString("user_id"),session.getString("device_id"));}
    }
    public void testTransferEpochImmediatelyRejectsLogoutAndAccountReplacement() throws Exception {
        Context c=getInstrumentation().getTargetContext();String origin="https://transfer-epoch.example.invalid";
        SessionStore store=new SessionStore(c,origin);
        JSONObject session=new JSONObject().put("token","epoch-fixture").put("status","approved").put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString());
        try {
            store.save(session);TransferRuntime.Control control=new TransferRuntime.Control();
            control.sessionToken=session.getString("token");control.sessionGeneration=store.generation();
            assertTrue(TransferDriver.sameSession(store,control.sessionToken,control));
            store.clear();assertFalse(TransferDriver.sameSession(store,control.sessionToken,control));
            store.save(session);control.sessionGeneration=store.generation();
            assertTrue(TransferDriver.sameSession(store,control.sessionToken,control));
            JSONObject other=new JSONObject(session.toString()).put("user_id",UUID.randomUUID().toString());
            store.save(other);assertFalse(TransferDriver.sameSession(store,control.sessionToken,control));
            control.sessionGeneration=store.generation();assertTrue(TransferDriver.sameSession(store,control.sessionToken,control));
            other.put("token","replacement-token");store.save(other);
            assertFalse(TransferDriver.sameSession(store,control.sessionToken,control));
            control.sessionGeneration=store.generation();control.sessionToken="replacement-token";
            control.stop();assertFalse(TransferDriver.sameSession(store,control.sessionToken,control));
        } finally {store.clear();}
    }

}
