package dev.arglabs.tubego;

/** Purpose and scope of every user-visible screen, shared by headers and navigation. */
final class ScreenInfo {
    record Info(String title, String description, String scope, int icon, boolean admin) {}
    static Info forScreen(Class<?> screen) {
        return switch (screen.getSimpleName()) {
            case "MainActivity" -> new Info("Servidor", "Conecta esta app a la dirección HTTPS de tu servidor.", "Este teléfono", R.drawable.ic_ds_link, false);
            case "LoginActivity" -> new Info("Mi cuenta y seguridad", "Inicia sesión, cambia tu contraseña o cierra el acceso de este teléfono.", "Tu cuenta", R.drawable.ic_ds_info, false);
            case "RegistrationActivity" -> new Info("Crear cuenta", "Registra tu correo, verifícalo y consulta la aprobación de tu cuenta.", "Tu cuenta", R.drawable.ic_ds_plus, false);
            case "DevicesActivity" -> new Info("Mis dispositivos", "Consulta tus teléfonos vinculados y cierra el acceso de un dispositivo.", "Tu cuenta", R.drawable.ic_ds_link, false);
            case "MediaPreferencesActivity" -> new Info("Calidad y reproducción", "Elige la calidad habitual y las preferencias de reproducción de tu cuenta.", "Tu cuenta", R.drawable.ic_ds_video, false);
            case "NetworkPolicyActivity" -> new Info("Wi-Fi y datos móviles", "Las descargas usan Wi-Fi. Puedes permitir datos para un archivo de este teléfono.", "Este teléfono", R.drawable.ic_ds_wifi, false);
            case "DeviceStorageActivity" -> new Info("Espacio en este teléfono", "Reserva espacio libre para que las descargas se pausen antes de llenar tu teléfono.", "Este teléfono", R.drawable.ic_ds_storage, false);
            case "LanguageActivity" -> new Info("Idioma", "El idioma se guarda en tu cuenta y se sincroniza con tus dispositivos.", "Tu cuenta", R.drawable.ic_ds_info, false);
            case "AlertsActivity" -> new Info("Avisos y notificaciones", "Consulta tus avisos y decide si este teléfono debe mostrar notificaciones.", "Tu cuenta", R.drawable.ic_ds_alert, false);
            case "DownloadFailuresActivity" -> new Info("Problemas de mis descargas", "Consulta qué falló y las opciones para recuperar tus descargas.", "Tu cuenta", R.drawable.ic_ds_retry, false);
            case "CommandsActivity" -> new Info("Acciones pendientes", "Revisa los cambios guardados que esperan enviarse al servidor.", "Tu cuenta", R.drawable.ic_ds_clock, false);
            case "DiagnosticsActivity" -> new Info("Conexión y diagnóstico", "Comprueba la red, la versión de la app y la respuesta del servidor.", "Este teléfono", R.drawable.ic_ds_wifi, false);
            case "HelpActivity" -> new Info("Ayuda", "Aprende a descargar, reproducir sin conexión y gestionar tus archivos.", "Para todos", R.drawable.ic_ds_info, false);
            case "ServerCleanupActivity" -> new Info("Limpiar copias del servidor", "Borra tus copias del servidor y conserva el historial y lo guardado en tus teléfonos.", "Tu cuenta", R.drawable.ic_ds_trash, false);
            case "LocalLibraryActivity" -> new Info("Biblioteca e historial", "Consulta tus archivos y lo que has descargado, incluso sin conexión.", "Tu cuenta", R.drawable.ic_ds_video, false);
            case "LinkEntryActivity" -> new Info("Agregar enlace", "Pega un enlace y elige cómo guardarlo en tu biblioteca.", "Tu cuenta", R.drawable.ic_ds_plus, false);
            case "ResubmitActivity" -> new Info("Recuperar archivos", "Vuelve a solicitar archivos de tu historial que ya no están disponibles.", "Tu cuenta", R.drawable.ic_ds_retry, false);
            case "ResourceDeletionActivity" -> new Info("Borrar archivos", "Elige qué archivos quitar de tus dispositivos y, si quieres, del servidor.", "Tu cuenta", R.drawable.ic_ds_trash, false);
            case "ShareReceiverActivity" -> new Info("Enlace compartido", "Agrega a tu biblioteca un enlace recibido desde otra app.", "Tu cuenta", R.drawable.ic_ds_link, false);
            case "AdminCenterActivity" -> new Info("Administración del servidor", "Gestiona usuarios y el funcionamiento del servicio para todas las cuentas.", "Solo administradores", R.drawable.ic_ds_sliders, true);
            case "AdminRegistrationsActivity" -> new Info("Aprobar cuentas", "Aprueba o rechaza solicitudes que ya verificaron su correo.", "Solo administradores", R.drawable.ic_ds_check, true);
            case "AdminUsersActivity" -> new Info("Usuarios y permisos", "Gestiona el estado, el rol y el acceso de las cuentas del servidor.", "Solo administradores", R.drawable.ic_ds_info, true);
            case "AdminPriorityActivity" -> new Info("Prioridades de usuarios", "Define qué usuarios tienen preferencia en la cola del servidor.", "Solo administradores", R.drawable.ic_ds_sliders, true);
            case "AdminRetentionActivity" -> new Info("Conservación y plazos", "Decide qué copias conserva el servidor y cuándo puede limpiarlas.", "Solo administradores", R.drawable.ic_ds_clock, true);
            case "AdminMailActivity" -> new Info("Correo del servidor", "Revisa el correo de verificación y recuperación; comprueba el envío desde el servidor.", "Solo administradores", R.drawable.ic_ds_link, true);
            case "AdminDownloadErrorsActivity" -> new Info("Errores de todas las descargas", "Consulta los fallos del servidor que afectan a las descargas de los usuarios.", "Solo administradores", R.drawable.ic_ds_alert, true);
            case "MaintenanceActivity" -> new Info("Mantenimiento del servidor", "Consulta versiones, mide la conexión del servidor y revisa actualizaciones o reinicios.", "Solo administradores", R.drawable.ic_ds_sliders, true);
            case "LibraryActivity" -> new Info("Mi biblioteca", "Tus videos y audios, siempre contigo", "Tu cuenta", R.drawable.ic_ds_video, false);
            case "SettingsActivity" -> new Info("Ajustes", "Personaliza tus descargas, tu cuenta y este teléfono.", "Tu cuenta", R.drawable.ic_ds_sliders, false);
            case "OnboardingActivity" -> new Info("Conecta tu cuenta", "Tus videos y audios, siempre contigo", "Tu cuenta", R.drawable.ic_ds_video, false);
            default -> throw new IllegalArgumentException("Screen missing visual metadata: " + screen.getName());
        };
    }
}
