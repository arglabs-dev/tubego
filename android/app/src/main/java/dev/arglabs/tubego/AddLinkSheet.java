package dev.arglabs.tubego;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Hoja "Agregar enlace": enlace, video o solo audio, calidad y un solo botón. */
final class AddLinkSheet {
    private final Host host;
    private final DsActivity a;
    private final Sheet sheet;
    private final String[] videoValues = {"480", "720", "1080", "best"};
    private String selection;
    private boolean audio;
    private EditText input;
    private TextView info;
    private LinearLayout optionsBox;
    private Button tabVideo, tabAudio, add;
    private final String knownTitle;
    private volatile String analyzedUrl = "";

    private AddLinkSheet(Host host, String url, String preselect, String knownTitle) {
        this.host = host;
        this.a = host.act();
        this.sheet = new Sheet(a);
        this.knownTitle = knownTitle;
        this.selection = preselect == null ? "720" : preselect;
        this.audio = "audio".equals(selection);
        if (audio) selection = "audio";
        build(url == null ? "" : url);
    }

    /** Abre la hoja. {@code preselect} puede ser null (se usa la calidad habitual de la cuenta). */
    static void show(Host host, String url, String preselect, String knownTitle) {
        host.io().execute(() -> {
            String chosen = preselect;
            if (chosen == null) {
                try {
                    JSONObject session = new SessionStore(host.act(), host.origin()).read();
                    chosen = new LocalMediaPreferences(LinkOutboxDispatch.root(host.act(), host.origin(), session)).read().optString("selection", "720");
                } catch (Exception ignored) { chosen = "720"; }
            }
            final String value = chosen;
            host.act().runOnUiThread(() -> new AddLinkSheet(host, url, value, knownTitle).sheet.show());
        });
    }

    private void build(String url) {
        sheet.title(a.t("Agregar enlace"), a.t("Cerrar"));
        LinearLayout body = sheet.body();

        LinearLayout field = Ds.row(a);
        field.setBackground(Ds.rounded(a, 0xFFFBFAF7, Ds.OUTLINE, 16));
        field.setPadding(Ds.dp(a, 14), 0, Ds.dp(a, 6), 0);
        field.addView(Ds.icon(a, R.drawable.ic_ds_link, Ds.MUTED), new LinearLayout.LayoutParams(Ds.dp(a, 24), Ds.dp(a, 24)));
        input = new EditText(a);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setHint(a.t("Pega un enlace HTTP o HTTPS"));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setTextSize(16);
        input.setTextColor(Ds.INK);
        input.setText(url);
        Ds.addWeighted(field, input, 1, 10, 0);
        Button paste = Ds.button(a, a.t("Pegar"), Ds.Kind.TEXT);
        paste.setOnClickListener(v -> { input.setText(clipboard()); analyze(); });
        field.addView(paste, new LinearLayout.LayoutParams(-2, Ds.dp(a, 48)));
        Ds.add(body, field, 4);
        field.setMinimumHeight(Ds.dp(a, 56));

        info = Ds.text(a, "", 15, Ds.MUTED, 600);
        Ds.add(body, info, 10);

        LinearLayout tabs = Ds.row(a);
        tabs.setBackground(Ds.rounded(a, Ds.SOFT, 0, 16));
        tabs.setPadding(Ds.dp(a, 4), Ds.dp(a, 4), Ds.dp(a, 4), Ds.dp(a, 4));
        tabVideo = tab(a.t("Video"));
        tabAudio = tab(a.t("Solo audio"));
        Ds.addWeighted(tabs, tabVideo, 1, 0, 0);
        Ds.addWeighted(tabs, tabAudio, 1, 0, 0);
        Ds.add(body, tabs, 14);
        tabVideo.setOnClickListener(v -> { audio = false; if ("audio".equals(selection)) selection = "720"; refreshChoices(); });
        tabAudio.setOnClickListener(v -> { audio = true; selection = "audio"; refreshChoices(); });

        optionsBox = Ds.column(a);
        Ds.add(body, optionsBox, 12);

        TextView note = Ds.text(a, a.t("Si la fuente no ofrece esa calidad, se guarda la más cercana disponible. La descarga empieza con Wi-Fi."), 14, Ds.MUTED, 500);
        Ds.add(body, note, 12);

        add = Ds.button(a, a.t("Agregar"), Ds.Kind.PRIMARY);
        add.setOnClickListener(v -> save());
        Ds.add(body, add, LinearLayout.LayoutParams.MATCH_PARENT, Ds.bigButtonHeight(a), 0, 14, 0, 0);

        refreshChoices();
        if (!knownTitle.isEmpty()) info.setText(knownTitle);
        else if (!url.isEmpty()) analyze();
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            public void onTextChanged(CharSequence s, int st, int b, int c) { }
            public void afterTextChanged(Editable e) { if (!knownTitle.isEmpty()) return; info.setText(""); }
        });
        input.setOnEditorActionListener((v, action, ev) -> { analyze(); return false; });
    }

    private Button tab(String label) {
        Button b = Ds.button(a, label, Ds.Kind.TEXT);
        b.setMinHeight(Ds.dp(a, 44));
        return b;
    }

    private String clipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) return clip.getItemAt(0).getText().toString().trim();
        } catch (Exception ignored) { }
        return "";
    }

    private void styleTab(Button b, boolean on) {
        b.setTextColor(on ? 0xFFFFFFFF : Ds.BRAND_DARK);
        b.setBackground(Ds.ripple(a, Ds.rounded(a, on ? Ds.BRAND : 0x00000000, 0, 12)));
    }

    private void refreshChoices() {
        styleTab(tabVideo, !audio);
        styleTab(tabAudio, audio);
        optionsBox.removeAllViews();
        if (audio) return;
        String[][] labels = {
                {a.t("Ligero"), a.t("480p · ocupa menos")},
                {a.t("Equilibrado"), a.t("720p")},
                {a.t("Alta"), a.t("1080p")},
                {a.t("La mejor"), a.t("Máxima disponible")}};
        LinearLayout rowA = Ds.row(a), rowB = Ds.row(a);
        for (int i = 0; i < 4; i++) {
            final String value = videoValues[i];
            boolean on = value.equals(selection);
            LinearLayout cell = Ds.column(a);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setMinimumHeight(Ds.dp(a, 64));
            cell.setPadding(Ds.dp(a, 14), Ds.dp(a, 8), Ds.dp(a, 14), Ds.dp(a, 8));
            cell.setBackground(Ds.ripple(a, on ? Ds.rounded(a, Ds.SOFT_SELECTED, Ds.BRAND, 18) : Ds.rounded(a, 0xFFFFFFFF, Ds.OUTLINE, 18)));
            cell.addView(Ds.text(a, labels[i][0], 16, Ds.INK, 700));
            cell.addView(Ds.text(a, labels[i][1], 13, on ? Ds.BRAND_DARK : Ds.MUTED, 600));
            cell.setClickable(true);
            cell.setContentDescription(labels[i][0] + ", " + labels[i][1] + (on ? ", " + a.t("elegida") : ""));
            cell.setOnClickListener(v -> { selection = value; refreshChoices(); });
            Ds.addWeighted(i < 2 ? rowA : rowB, cell, 1, i % 2 == 0 ? 0 : 5, i % 2 == 0 ? 5 : 0);
        }
        Ds.add(optionsBox, rowA, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0);
        Ds.add(optionsBox, rowB, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0);
    }

    /** Consulta título y duración si hay conexión; si no, avisa que se guardará igualmente. */
    private void analyze() {
        final String url;
        try { url = SharedUrl.parse(input.getText().toString()); } catch (Exception e) { return; }
        if (url.equals(analyzedUrl)) return;
        analyzedUrl = url;
        info.setText(a.t("Consultando…"));
        host.io().execute(() -> {
            String text;
            try {
                String token = new SessionStore(a, host.origin()).token();
                MediaInfoClient.Info i = new MediaInfoClient(new ApiClient(host.origin())::request).analyze(url, token);
                String title = i.title == null ? a.t("Sin título disponible") : i.title;
                String dur = i.durationSeconds == null ? "" : " · " + Item.formatDuration(i.durationSeconds);
                text = title + dur;
            } catch (Exception e) {
                text = a.t("Sin conexión: se guardará y se validará cuando vuelva la red.");
            }
            final String result = text;
            a.runOnUiThread(() -> { if (!a.isDestroyed() && sheet.isShowing()) info.setText(result); });
        });
    }

    private void save() {
        final String url;
        try {
            url = SharedUrl.parse(input.getText().toString());
        } catch (Exception e) {
            info.setText(Texts.error(a, e));
            return;
        }
        final String chosen = audio ? "audio" : selection;
        add.setEnabled(false);
        host.io().execute(() -> {
            String message;
            try {
                synchronized (SessionStore.class) {
                    JSONObject session = new SessionStore(a, host.origin()).read();
                    if (session == null || !"approved".equals(session.optString("status"))) throw new Exception();
                    LinkOutboxDispatch.box(a, host.origin(), session).add(url, chosen);
                }
                LinkOutboxDispatch.schedule(a, host.origin());
                DownloadPolicy.NetworkState n = host.network();
                message = DownloadPolicy.allowsControl(n) ? a.t("Enlace agregado. Lo verás en “Ahora”.") : a.t("Enlace guardado. Se enviará cuando vuelva la conexión.");
                final String shown = message;
                a.runOnUiThread(() -> { sheet.dismiss(); host.message(shown); host.refresh(); });
            } catch (Exception e) {
                a.runOnUiThread(() -> { add.setEnabled(true); info.setText(a.t("No se pudo guardar. Revisa sesión y almacenamiento.")); });
            }
        });
    }

    /** Agrega sin mostrar la hoja (cuando la cuenta tiene desactivado "elegir la calidad al agregar"). */
    static void addDirect(Host host, String url, String selection) {
        DsActivity a = host.act();
        host.io().execute(() -> {
            try {
                synchronized (SessionStore.class) {
                    JSONObject session = new SessionStore(a, host.origin()).read();
                    if (session == null || !"approved".equals(session.optString("status"))) throw new Exception();
                    LinkOutboxDispatch.box(a, host.origin(), session).add(url, selection);
                }
                LinkOutboxDispatch.schedule(a, host.origin());
                String label = Texts.quality(a, selection);
                a.runOnUiThread(() -> { host.message(a.tf("Agregado: %s. Lo verás en “Ahora”.", label)); host.refresh(); });
            } catch (Exception e) {
                a.runOnUiThread(() -> host.message(a.t("No se pudo guardar. Revisa sesión y almacenamiento.")));
            }
        });
    }
}
