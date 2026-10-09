package dev.arglabs.tubego.spike;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Compile with android.jar; call from a foreground Activity, never a Service. */
public final class VlcProbe {
    public static final int REQUEST = 257;
    public static final String VLC = "org.videolan.vlc";
    private VlcProbe() {}

    public enum Launch { STARTED, NOT_INSTALLED, ACCESS_DENIED }

    public static Launch open(Activity activity, Uri privateFileContentUri, String mime,
                              long lastMillis, int userRewindSeconds) {
        if (!"content".equals(privateFileContentUri.getScheme())) {
            throw new IllegalArgumentException("Use a narrow read-only FileProvider content URI");
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(VLC, "org.videolan.vlc.gui.video.VideoPlayerActivity");
        intent.setDataAndType(privateFileContentUri, mime);
        intent.setClipData(ClipData.newRawUri("Tubego offline resource", privateFileContentUri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra("position", ResumePosition.startMillis(lastMillis, userRewindSeconds));
        intent.putExtra("from_external", true);
        // Do not set from_start=true: VLC ignores position when that flag is set.
        // No NEW_TASK flag. The VLC manifest's singleTask still requires device testing.
        try {
            activity.startActivityForResult(intent, REQUEST);
            return Launch.STARTED;
        } catch (ActivityNotFoundException absent) {
            return Launch.NOT_INSTALLED;
        } catch (SecurityException denied) {
            return Launch.ACCESS_DENIED;
        }
    }

    /** Null means no trustworthy checkpoint; retain the previously saved position. */
    @SuppressWarnings("deprecation") // Type-check raw extras on older Android releases too.
    public static Checkpoint result(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST || resultCode != Activity.RESULT_OK || data == null
                || !(VLC + ".player.result").equals(data.getAction())) return null;
        try {
            Bundle extras = data.getExtras();
            if (extras == null) return null;
            Object rawPosition = extras.get("extra_position");
            Object rawDuration = extras.get("extra_duration");
            if (!(rawPosition instanceof Long) || !(rawDuration instanceof Long)) return null;
            Long position = (Long) rawPosition;
            Long duration = (Long) rawDuration;
            return ResumePosition.validResult(position, duration)
                    ? new Checkpoint(position, duration) : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public static final class Checkpoint {
        public final long positionMillis;
        public final long durationMillis;
        Checkpoint(long positionMillis, long durationMillis) {
            this.positionMillis = positionMillis;
            this.durationMillis = durationMillis;
        }
    }
}
