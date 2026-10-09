package dev.arglabs.tubego;
import android.test.InstrumentationTestCase;
import android.content.Context;
import org.json.JSONObject;
import javax.net.ssl.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real TLS/network/job fixture. Trust override exists only in instrumentation APK. */
public final class TransferInstrumentedTest extends InstrumentationTestCase {
    private static final String ORIGIN="https://10.0.2.2:9443";
    private static final String RESOURCE="22222222-2222-4222-8222-222222222222";
    public void testTlsPauseResumeThenLockedBackgroundJobConfirms() throws Exception {
        Context context=getInstrumentation().getTargetContext();SSLSocketFactory original=HttpsURLConnection.getDefaultSSLSocketFactory();
        SSLContext tls=SSLContext.getInstance("TLS");KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null);
        Context test=getInstrumentation().getContext();int certId=test.getResources().getIdentifier("fixture_cert","raw",test.getPackageName());assertTrue(certId!=0);
        try(InputStream cert=test.getResources().openRawResource(certId)){trust.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509").generateCertificate(cert));}
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);tls.init(null,tm.getTrustManagers(),null);
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
        // Hostname verification is the normal platform verifier; SAN must match 10.0.2.2.
        try {
            TransferJobs.stop(context,ORIGIN);
            ApiClient api=new ApiClient(ORIGIN);
            JSONObject session=api.request("POST","/auth/login",new JSONObject().put("email","tubego-test@example.invalid").put("password","MVP test password 2026").put("device_name","Transfer instrumentation"),null);
            new SessionStore(context,ORIGIN).save(session);
            api.request("POST","/resources/"+RESOURCE+"/deliveries/request",new JSONObject(),session.getString("token"));
            File root=LocalLibraryStorage.root(context,ORIGIN,session.getString("user_id"),session.getString("device_id"));
            TransferRuntime.Control first=new TransferRuntime.Control();AtomicReference<Throwable> error=new AtomicReference<>();
            Thread thread=new Thread(()->{try{TransferDriver.run(context,ORIGIN,first);}catch(Throwable e){error.set(e);}});thread.start();
            File part=new File(root,RESOURCE+".part");long until=System.currentTimeMillis()+30000;
            while(part.length()==0 && thread.isAlive() && System.currentTimeMillis()<until)Thread.sleep(5);
            assertTrue("Expected actual partial bytes",part.length()>0);first.stop();thread.join(30000);assertFalse(thread.isAlive());
            if(error.get()!=null)throw new AssertionError(error.get());
            TransferRecord paused=TransferRecord.read(new File(root,RESOURCE+".properties"));
            assertTrue(paused.offset()>0 && paused.offset()<paused.size);assertFalse(paused.media().exists());long offset=paused.offset();
            TransferJobs.wake(context,ORIGIN,false);
            shell("cmd jobscheduler run -f dev.arglabs.tubego "+TransferJobs.jobId(ORIGIN,false));
            shell("input keyevent 223"); // SLEEP: the ordinary job owns its system wakelock.
            until=System.currentTimeMillis()+90000;
            boolean complete=false;
            while(System.currentTimeMillis()<until) {
                if(paused.media().isFile()) {
                    JSONObject snapshot=api.request("GET","/device/sync",null,session.getString("token"));
                    var items=snapshot.getJSONArray("deliveries");
                    for(int i=0;i<items.length();i++)if(RESOURCE.equals(items.getJSONObject(i).getString("id")) && "complete".equals(items.getJSONObject(i).getString("delivery_status")))complete=true;
                    if(complete)break;
                }
                Thread.sleep(100);
            }
            assertTrue("Background job did not confirm completed transfer",complete);
            TransferRecord finalRecord=TransferRecord.read(paused.manifest());assertEquals("complete",finalRecord.state);
            assertEquals(16777216L,finalRecord.media().length());assertTrue(ResumableTransfer.verifies(finalRecord.media(),finalRecord.size,finalRecord.sha256));
            assertFalse(finalRecord.part().exists());assertTrue(offset>0);
            TransferJobs.stop(context,ORIGIN);
            // Remote revocation must wipe the managed media before future transfer writes.
            SessionLifecycle.remotelyRevoked(context,ORIGIN,session.getString("token"),"session_revoked");assertFalse(root.exists());
        } finally {shell("input keyevent 224");TransferJobs.stop(context,ORIGIN);HttpsURLConnection.setDefaultSSLSocketFactory(original);}
    }
    private void shell(String command) throws Exception {try(var fd=getInstrumentation().getUiAutomation().executeShellCommand(command);var input=new FileInputStream(fd.getFileDescriptor())){while(input.read()!=-1){}}}
}
