package dev.arglabs.tubego;

import java.util.Locale;

/** Un video o audio de la biblioteca, o un enlace guardado que aún no se envía. */
final class Item {
    String id = "", title = "", mediaFormat = "video", selection = "best", sourceUrl = "", host = "";
    String createdAt = "", lastOpenedAt = "", thumbnailKey = "";
    String taskId = "", taskState = "", localState = "", pauseReason = "", failureCode = "", qualityNoticeCode = "";
    double durationSeconds = -1, serverProgress = -1;
    long sizeBytes = -1, offsetBytes = 0;
    boolean downloaded, serverAvailable, mobileAuthorized, hasThumbnail;
    ItemState state = ItemState.REMOTE_ONLY;

    /** Enlace guardado en el teléfono pendiente de enviar (cola de envío). */
    boolean outbox;
    String outboxId = "", outboxState = "";

    boolean isAudio() { return "audio".equals(mediaFormat); }

    /** Avance del tramo del teléfono (0-100), o -1 si no se conoce. */
    int phonePercent() {
        if (sizeBytes <= 0) return -1;
        return (int) Math.min(100, offsetBytes * 100 / sizeBytes);
    }

    /** Avance del tramo del servidor (0-100), o -1 si no se conoce. */
    int serverPercent() {
        if (serverProgress < 0) return -1;
        return (int) Math.max(0, Math.min(100, Math.round(serverProgress * 100)));
    }

    boolean canCancelOnServer() {
        return !taskId.isEmpty() && ("queued".equals(taskState) || "running".equals(taskState) || "paused".equals(taskState));
    }

    boolean canRetryOnServer() {
        return !taskId.isEmpty() && ("failed".equals(taskState) || "cancelled".equals(taskState));
    }

    boolean canRetryOnPhone() { return "failed".equals(localState); }

    static String formatDuration(double seconds) {
        if (seconds < 0) return "";
        long s = Math.round(seconds);
        if (s < 60) return s + " s";
        long minutes = s / 60;
        if (minutes < 60) return minutes + " min";
        return String.format(Locale.ROOT, "%d h %02d min", minutes / 60, minutes % 60);
    }

    static String formatSize(long bytes) {
        if (bytes < 0) return "";
        if (bytes >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
        if (bytes >= 1_000_000L) return String.format(Locale.ROOT, "%d MB", Math.round(bytes / 1e6));
        return String.format(Locale.ROOT, "%d KB", Math.max(1, Math.round(bytes / 1e3)));
    }
}
