package dev.arglabs.tubego;

import android.content.Context;
import org.json.JSONObject;

/** Actualiza rol y estado de la cuenta y el idioma desde el servidor (igual que la pantalla anterior). */
final class AccountSync {
    private AccountSync() {}

    /** Devuelve true si el idioma cambió y la pantalla debe recrearse. Debe llamarse fuera del hilo principal. */
    static boolean refresh(Context ctx, String origin) {
        try {
            String token = new SessionStore(ctx, origin).token();
            if (token == null) return false;
            JSONObject account = new ApiClient(origin).request("GET", "/account/status", null, token);
            synchronized (SessionStore.class) {
                SessionStore store = new SessionStore(ctx, origin);
                JSONObject current = store.read();
                if (current != null && token.equals(current.optString("token"))) {
                    current.put("role", account.getString("role")).put("status", account.getString("status"));
                    store.save(current);
                }
            }
            String before = LanguagePreferences.language(ctx, origin);
            try { LanguagePreferences.refresh(ctx, origin); } catch (Exception ignored) { /* sin conexión: se conserva el idioma */ }
            return !before.equals(LanguagePreferences.language(ctx, origin));
        } catch (Exception ignored) {
            return false; // Sin conexión: el acceso sin conexión sigue hasta que se sepa una revocación.
        }
    }

    /** Estado de la sesión guardada: "none", "pending_verification", "pending_approval", "approved" u otro. */
    static String state(Context ctx, String origin) {
        try {
            JSONObject session = new SessionStore(ctx, origin).read();
            return session == null ? "none" : session.optString("status", "none");
        } catch (Exception e) {
            return "none";
        }
    }
}
