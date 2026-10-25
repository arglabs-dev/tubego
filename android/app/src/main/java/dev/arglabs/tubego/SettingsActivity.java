package dev.arglabs.tubego;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Personal settings are separate from server administration and show their scope. */
public final class SettingsActivity extends DsActivity {
    private String origin;
    private LinearLayout body;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        origin = savedOrigin();
        LinearLayout page = Ds.column(this);
        LinearLayout top = Ds.row(this);
        top.setPadding(Ds.dp(this, 8), Ds.dp(this, 8), Ds.dp(this, 20), Ds.dp(this, 8));
        ImageButton back = Ds.iconButton(this, R.drawable.ic_ds_back, t("Volver"));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(Ds.dp(this, 48), Ds.dp(this, 48)));
        Ds.addWeighted(top, Ds.text(this, t("Ajustes"), 26, Ds.INK, 800), 1, 8, 0);
        page.addView(top);
        body = Ds.column(this);
        body.setPadding(Ds.dp(this, 20), 0, Ds.dp(this, 20), Ds.dp(this, 32));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setScreen(page);
    }

    @Override protected void onResume() {
        super.onResume();
        // Role can change after returning from an account or administration screen.
        String configured = getSharedPreferences("server_connection", MODE_PRIVATE).getString("server_url", origin);
        if (!origin.equals(configured)) {
            getIntent().putExtra("server_url", configured);
            recreate();
            return;
        }
        if (body != null) render();
    }

    private void render() {
        body.removeAllViews();
        Ds.add(body, Ds.text(this, t("Cada opción indica si cambia este teléfono, tu cuenta o el servidor."), 15, Ds.MUTED, 500), 4);
        group("Descargas y reproducción", "Preferencias de tu cuenta");
        row(MediaPreferencesActivity.class);
        row(ResubmitActivity.class);

        group("Este teléfono", "Conexión y almacenamiento local");
        row(NetworkPolicyActivity.class);
        row(DeviceStorageActivity.class);
        row(MainActivity.class);

        group("Mi cuenta", "Tus preferencias y acceso");
        row(LoginActivity.class);
        row(DevicesActivity.class);
        row(LanguageActivity.class);
        row(AlertsActivity.class);

        group("Mis archivos en el servidor", "Gestiona solo tus copias");
        row(ServerCleanupActivity.class);

        group("Ayuda y actividad", "Entiende qué está pasando");
        row(DownloadFailuresActivity.class);
        row(CommandsActivity.class);
        row(DiagnosticsActivity.class);
        row(HelpActivity.class);

        if (AdminAccess.allowed(this, origin)) {
            group("Administración", "Solo administradores · afecta al servidor y sus usuarios");
            LinearLayout notice = Ds.card(this);
            notice.setBackground(Ds.rounded(this, Ds.SOFT_SELECTED, Ds.BRAND, 20));
            notice.addView(Ds.badge(this, t("Solo administradores"), Ds.Tone.PROGRESS));
            Ds.add(notice, Ds.text(this, t("Usuarios, permisos, correo, conservación, prioridades y mantenimiento. Se comprobarán tus permisos al abrir cada herramienta."), 14, Ds.MUTED, 500), 8);
            Ds.add(body, notice, 10);
            row(AdminCenterActivity.class);
        }
    }

    private void group(String title, String subtitle) {
        Ds.add(body, Ds.text(this, t(title), 18, Ds.INK, 800), 26);
        Ds.add(body, Ds.text(this, t(subtitle), 13, Ds.MUTED, 500), 4);
    }

    private void row(Class<?> target) {
        ScreenInfo.Info info = ScreenInfo.forScreen(target);
        Ds.add(body, Ds.option(this, info.icon(), t(info.title()),
                t(info.scope()) + " · " + t(info.description()), () -> {
                    if (info.admin() && !AdminAccess.allowed(this, origin)) { render(); return; }
                    startActivity(new Intent(this, target).putExtra("server_url", origin));
                }), 10);
    }
}
