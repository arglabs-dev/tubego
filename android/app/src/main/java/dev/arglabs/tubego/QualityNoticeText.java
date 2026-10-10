package dev.arglabs.tubego;

/** A notice never invents an actual resolution from the requested preference. */
public final class QualityNoticeText {
    private QualityNoticeText() {}
    public static String source(String code) {
        if (code == null) return "";
        return switch (code) {
            case "lower_quality_available" -> "La fuente ofreció una calidad inferior a la solicitada. El archivo usa la calidad disponible.";
            case "quality_unknown" -> "La fuente no informó la resolución. La calidad mostrada es la solicitada; no se ha confirmado la resolución del archivo.";
            default -> "";
        };
    }
}
