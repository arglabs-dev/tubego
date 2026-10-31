package dev.arglabs.tubego;

/** Stable source choice, independent of transient yt-dlp format IDs. */
public final class AudioTrack {
    public final String id, language, kind;
    public AudioTrack(String id, String language, String kind) {
        this.id = validate(id); this.language = language; this.kind = kind;
    }
    public static String validate(String value) {
        if (value == null || value.length() > 64 || !value.matches("original|[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3}~(?:original|dubbed|auto|unknown)"))
            throw new IllegalArgumentException("Invalid audio choice");
        return value;
    }
}
