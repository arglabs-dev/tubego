package dev.arglabs.tubego;

/** Stable API failure codes to user-facing resource literals, with no extractor logs. */
public final class MediaFailureText {
    private MediaFailureText() {}
    public static String source(String code) {
        if (code == null) code = "";
        return switch (code) {
            case "invalid_url" -> "Usa un único enlace HTTP o HTTPS público válido.";
            case "unsupported", "unsupported_url" -> "La plataforma o colección de este enlace no es compatible.";
            case "private", "authentication_required" -> "El contenido es privado o requiere iniciar sesión en la plataforma. Tubego no admite credenciales ni cookies de esa fuente.";
            case "unavailable" -> "El contenido fue eliminado o ya no está disponible en la plataforma.";
            case "source_restricted" -> "La plataforma restringe el acceso a este contenido. Puede depender del país, edad o permisos de la fuente.";
            case "format_unavailable" -> "No está disponible el formato o la calidad elegida. Solicita otra calidad o solo audio.";
            case "temporary_failure", "temporary_network_failure", "network_error", "download_timeout", "download_failed_transient" -> "La conexión o la fuente falló temporalmente. Reintenta más tarde.";
            case "integrity_mismatch", "checksum_mismatch", "size_mismatch" -> "Falló la verificación del archivo. Reintenta la descarga.";
            case "resource_unavailable", "file_missing" -> "El archivo del servidor ya no está disponible.";
            case "conversion_failed" -> "No se pudo convertir el archivo. Solicita otra calidad o formato.";
            default -> "No se pudo descargar. Revisa el enlace y reintenta.";
        };
    }
    public static boolean known(String code) {
        if (code == null) return false;
        return switch (code) {
            case "invalid_url", "unsupported", "unsupported_url", "private", "authentication_required", "unavailable", "source_restricted", "format_unavailable", "temporary_failure", "temporary_network_failure", "network_error", "download_timeout", "download_failed_transient", "integrity_mismatch", "checksum_mismatch", "size_mismatch", "resource_unavailable", "file_missing", "conversion_failed" -> true;
            default -> false;
        };
    }
}
