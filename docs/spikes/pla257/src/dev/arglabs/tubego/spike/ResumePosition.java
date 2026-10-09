package dev.arglabs.tubego.spike;

/** Isolated experiment; production adoption depends on the VLC device matrix. */
public final class ResumePosition {
    public static final int DEFAULT_REWIND_SECONDS = 10;
    private ResumePosition() {}

    public static long startMillis(long savedMillis, int userRewindSeconds) {
        if (userRewindSeconds < 0) throw new IllegalArgumentException("Negative rewind");
        return Math.max(0L, Math.max(0L, savedMillis) - (long) userRewindSeconds * 1000L);
    }

    /** Missing, negative or inconsistent position must never overwrite known history. */
    public static boolean validResult(Long positionMillis, Long durationMillis) {
        return positionMillis != null && positionMillis >= 0 && durationMillis != null
                && durationMillis > 0 && positionMillis <= durationMillis;
    }
}
