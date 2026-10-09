package dev.arglabs.tubego;
import android.app.Application;
public final class TubegoApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ApiClient.setSessionObserver((origin,token,code)->SessionLifecycle.remotelyRevoked(this,origin,token,code));
        SessionLifecycle.schedule(this);
        LinkOutboxDispatch.schedule(this);
        DeletionOutboxDispatch.schedule(this);
        RecoveryOutboxDispatch.schedule(this);
    }
}
