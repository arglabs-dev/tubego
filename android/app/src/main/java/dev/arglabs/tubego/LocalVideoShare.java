package dev.arglabs.tubego;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.IOException;

/** Sends verified private bytes through the same revocable provider used for playback. */
final class LocalVideoShare {
    private LocalVideoShare() {}

    static Intent prepare(Context context, String origin, String id) throws Exception {
        Uri uri = PrivateMediaContentProvider.create(context, origin, id);
        String mime = context.getContentResolver().getType(uri);
        if (mime == null || !mime.startsWith("video/")) throw new IOException("Video required");
        Intent send = new Intent(Intent.ACTION_SEND).setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("TubeGo", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return send;
    }
}
