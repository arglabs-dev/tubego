package dev.arglabs.tubego;

/**
 * Estado de un archivo tal como lo ve la persona. Reduce los estados internos del servidor
 * y del teléfono a unos pocos. Lógica pura, sin Android, para poder probarla.
 */
enum ItemState {
    WAITING_WIFI, WAITING_OFFLINE, PREPARING, SAVING, READY, PAUSED_SPACE, FAILED, CANCELLED, UNAVAILABLE, REMOTE_ONLY;

    /** Bloque de la biblioteca donde se muestra. */
    enum Group { ATTENTION, NOW, SAVED, PAST }

    Group group() {
        switch (this) {
            case FAILED: return Group.ATTENTION;
            case WAITING_WIFI:
            case WAITING_OFFLINE:
            case PREPARING:
            case SAVING:
            case PAUSED_SPACE: return Group.NOW;
            case READY: return Group.SAVED;
            default: return Group.PAST;
        }
    }

    /** Verdadero si el archivo está en curso o en espera (puede cancelarse, priorizarse, etc.). */
    boolean inProgress() { return group() == Group.NOW; }

    private static boolean in(String value, String... options) {
        if (value == null) return false;
        for (String o : options) if (o.equals(value)) return true;
        return false;
    }

    /**
     * @param task        estado de la tarea en el servidor (queued, running, paused, failed, cancelled...) o ""
     * @param local       estado de la transferencia en el teléfono (pending, downloading, paused, retry_wait, failed...) o ""
     * @param pauseReason motivo de pausa local ("device_storage" si falta espacio) o ""
     * @param decision    resultado de la política de red para este archivo
     */
    static ItemState of(boolean downloaded, boolean unavailable, String task, String local, String pauseReason,
                        DownloadPolicy.Decision decision) {
        boolean phoneStage = in(local, "pending", "downloading", "paused", "retry_wait");
        boolean pending = in(task, "queued", "running", "paused") || phoneStage;
        boolean error = "failed".equals(task) || "failed".equals(local);
        if (downloaded) return READY;
        if (unavailable) return UNAVAILABLE;
        if (error) return FAILED;
        if ("cancelled".equals(task) && !pending) return CANCELLED;
        if (pending) {
            if ("device_storage".equals(pauseReason)) return PAUSED_SPACE;
            if (phoneStage) {
                if (decision == DownloadPolicy.Decision.WAIT_NETWORK) return WAITING_OFFLINE;
                if (decision == DownloadPolicy.Decision.WAIT_WIFI) return WAITING_WIFI;
                return SAVING;
            }
            return PREPARING;
        }
        return REMOTE_ONLY;
    }
}
