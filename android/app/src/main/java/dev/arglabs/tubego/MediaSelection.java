package dev.arglabs.tubego;

/** Offline quality choices. Transport/URL submission must persist the chosen value. */
public final class MediaSelection {
    public static final String[] VALUES = {"480", "720", "1080", "best", "audio"};
    public static final String[] LABELS = {"Video 480p", "Video 720p", "Video 1080p", "Máxima disponible", "Solo audio (MP3)"};
    private MediaSelection() {}
    public static int index(String value) {
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i].equals(value)) return i;
        throw new IllegalArgumentException("Calidad no válida");
    }
    public static String resolve(boolean askEveryTime, String saved, String override) {
        if (override != null) { index(override); return override; }
        if (askEveryTime) throw new IllegalArgumentException("Elige calidad o audio antes de enviar");
        index(saved); return saved;
    }
}
