package dev.arglabs.tubego;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Primer uso: servidor y cuenta. El inicio de sesión y el registro siguen siendo las pantallas existentes. */
public final class OnboardingActivity extends DsActivity {
    private EditText server;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = Ds.column(this);
        body.setPadding(Ds.dp(this, 24), Ds.dp(this, 40), Ds.dp(this, 24), Ds.dp(this, 32));

        body.addView(Ds.thumb(this, false, Ds.SOFT, Ds.BRAND_DARK, 72), new LinearLayout.LayoutParams(Ds.dp(this, 72), Ds.dp(this, 72)));
        Ds.add(body, Ds.text(this, t("Tus videos y audios, siempre contigo"), 30, Ds.INK, 800), 22);
        Ds.add(body, Ds.text(this, t("Comparte un enlace, elige la calidad y míralo después sin conexión. Primero conecta tu servidor y tu cuenta."), 16, Ds.MUTED, 500), 10);

        Ds.add(body, Ds.text(this, t("Dirección del servidor"), 14, Ds.MUTED, 800), 28);
        server = new EditText(this);
        server.setSingleLine(true);
        server.setHint("https://");
        server.setTextSize(16);
        server.setTextColor(Ds.INK);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setBackground(Ds.rounded(this, 0xFFFFFFFF, Ds.OUTLINE, 16));
        server.setPadding(Ds.dp(this, 16), Ds.dp(this, 14), Ds.dp(this, 16), Ds.dp(this, 14));
        server.setText(savedOrigin());
        Ds.add(body, server, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0);

        status = Ds.text(this, "", 14, Ds.ERR, 600);
        Ds.add(body, status, 10);

        Button login = Ds.button(this, t("Iniciar sesión"), Ds.Kind.PRIMARY);
        login.setOnClickListener(v -> go(LoginActivity.class));
        Ds.add(body, login, LinearLayout.LayoutParams.MATCH_PARENT, Ds.bigButtonHeight(this), 0, 16, 0, 0);
        Button register = Ds.button(this, t("Crear cuenta"), Ds.Kind.SECONDARY);
        register.setOnClickListener(v -> go(RegistrationActivity.class));
        Ds.add(body, register, LinearLayout.LayoutParams.MATCH_PARENT, Ds.bigButtonHeight(this), 0, 10, 0, 0);

        TextView note = Ds.text(this, t("Una cuenta nueva puede necesitar que verifiques tu correo y que un administrador la apruebe."), 13, Ds.MUTED, 500);
        note.setGravity(Gravity.CENTER);
        Ds.add(body, note, 16);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        setScreen(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        String origin = getSharedPreferences("server_connection", MODE_PRIVATE).getString("server_url", "");
        if (!origin.isEmpty() && "approved".equals(AccountSync.state(this, origin))) {
            startActivity(new Intent(this, LibraryActivity.class).putExtra("server_url", origin)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        }
    }

    private void go(Class<?> target) {
        try {
            String origin = new ApiClient(server.getText().toString().trim()).getBaseUrl();
            getSharedPreferences("server_connection", MODE_PRIVATE).edit().putString("server_url", origin).apply();
            startActivity(new Intent(this, target).putExtra("server_url", origin));
        } catch (Exception e) {
            status.setText(t("Revisa la dirección del servidor: debe empezar con https://"));
        }
    }
}
