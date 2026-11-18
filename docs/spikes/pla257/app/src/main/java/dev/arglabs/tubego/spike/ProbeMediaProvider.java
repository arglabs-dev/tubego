package dev.arglabs.tubego.spike;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;

/** Only two exact synthetic fixture paths can be granted; no TubeGo files or sessions. */
public final class ProbeMediaProvider extends ContentProvider {
    static final Uri CLIP = Uri.parse("content://dev.arglabs.tubego.vlcprobe.clip/sample.mp4");
    static final Uri LONG_CLIP = Uri.parse("content://dev.arglabs.tubego.vlcprobe.clip/long.mp4");
    @Override public boolean onCreate() { return true; }
    private synchronized File clip(Uri uri) throws FileNotFoundException {
        if (LONG_CLIP.equals(uri)) {
            File fixture = new File(getContext().getFilesDir(), "long.mp4");
            if (!fixture.isFile()) throw new FileNotFoundException("Provision the synthetic long fixture first");
            return fixture;
        }
        File file = new File(getContext().getCacheDir(), "sample.mp4");
        if (!file.exists()) {
            try (InputStream input = getContext().getResources().openRawResource(R.raw.vlc_sample)) {
                Files.copy(input, file.toPath());
            } catch (Exception error) {
                file.delete();
                throw new FileNotFoundException("Cannot prepare synthetic fixture");
            }
        }
        return file;
    }
    private void check(Uri uri) { if (!CLIP.equals(uri) && !LONG_CLIP.equals(uri)) throw new SecurityException("Unknown clip"); }
    @Override public String getType(Uri uri) { check(uri); return "video/mp4"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        check(uri);
        if (!"r".equals(mode)) throw new SecurityException("Read only");
        return ParcelFileDescriptor.open(clip(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        check(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = LONG_CLIP.equals(uri) ? "long.mp4" : "sample.mp4";
            if (OpenableColumns.SIZE.equals(columns[i])) try { values[i] = clip(uri).length(); } catch (FileNotFoundException ignored) { }
        }
        result.addRow(values);
        return result;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new SecurityException("Read only"); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new SecurityException("Read only"); }
}
