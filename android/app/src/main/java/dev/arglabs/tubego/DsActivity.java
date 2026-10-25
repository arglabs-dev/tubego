package dev.arglabs.tubego;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Base de las pantallas rediseñadas: idioma de la cuenta, textos traducidos y márgenes del sistema. */
abstract class DsActivity extends Activity {
    private String screenLanguage;
    private Context textContext;
    private final Map<String, String> translations = new HashMap<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ds.window(this);
        screenLanguage = LanguagePreferences.language(this, LanguagePreferences.origin(this));
        textContext = LanguagePreferences.localized(this, screenLanguage);
    }

    @Override protected void onResume() {
        super.onResume();
        String current = LanguagePreferences.language(this, LanguagePreferences.origin(this));
        if (screenLanguage != null && !screenLanguage.equals(current) && !isFinishing()) recreate();
    }

    /** Busca el texto en los recursos (ES/EN); si no existe, devuelve el original en español. */
    final String t(String source) {
        String hit = translations.get(source);
        if (hit != null) return hit;
        Context c = textContext == null ? this : textContext;
        int id = c.getResources().getIdentifier(TextKey.of(source), "string", getPackageName());
        String value = id == 0 ? source : c.getString(id);
        translations.put(source, value);
        return value;
    }

    final String tf(String source, Object... args) {
        return String.format(Locale.ROOT, t(source), args);
    }

    /** Muestra la vista como pantalla completa respetando barras del sistema. */
    final void setScreen(View content) {
        content.setBackgroundColor(Ds.PAPER);
        content.setTag("ds_screen");
        Ds.insets(content);
        setContentView(content);
        content.requestApplyInsets();
    }

    /** Dirección del servidor guardada, o cadena vacía. */
    final String savedOrigin() {
        String extra = getIntent() == null ? null : getIntent().getStringExtra("server_url");
        if (extra != null && !extra.isEmpty()) return extra;
        return getSharedPreferences("server_connection", MODE_PRIVATE).getString("server_url", "");
    }
}
