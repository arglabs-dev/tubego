package dev.arglabs.tubego;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pantalla única "Mi biblioteca": agregar, ver el avance y reproducir sin cambiar de lugar. */
public final class LibraryActivity extends DsActivity implements Host {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String origin = "";
    private NetworkMonitor monitor;
    private volatile DownloadPolicy.NetworkState network = DownloadPolicy.NetworkState.offline();
    private final List<Item> items = new ArrayList<>();
    private String nextCursor = "";
    private boolean online, loaded, loading, resumed, reloadPending;
    private String filter = "all"; // all | video | audio
    private String query = "";

    private LinearLayout list;
    private TextView banner, toast;
    private LinearLayout chips;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (isDestroyed()) return;
            if (hasActive()) {
                TransferJobs.wake(LibraryActivity.this, origin, false);
                refresh();
            }
            ui.postDelayed(this, 4000);
        }
    };

    // ---- Host ----
    @Override public DsActivity act() { return this; }
    @Override public String origin() { return origin; }
    @Override public ExecutorService io() { return io; }
    @Override public DownloadPolicy.NetworkState network() { return network; }
    @Override public void message(String text) {
        toast.setText(text);
        toast.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, 5000);
    }
    private final Runnable hideToast = () -> { if (toast != null) toast.setVisibility(View.GONE); };
    @Override public void refresh() { load(false); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        origin = savedOrigin();
        if (origin.isEmpty() || !"approved".equals(AccountSync.state(this, origin))) {
            startActivity(new Intent(this, OnboardingActivity.class).putExtra("server_url", origin));
            finish();
            return;
        }
        build();
        monitor = new NetworkMonitor(this, s -> {
            network = s;
            if (resumed) {
                TransferJobs.wake(this, origin, false);
                refresh();
            }
        });
        network = monitor.current();
        handleShare(getIntent());
        io.execute(() -> {
            if (AccountSync.refresh(this, origin)) runOnUiThread(this::recreate);
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        String configured = getSharedPreferences("server_connection", MODE_PRIVATE).getString("server_url", origin);
        if (!origin.equals(configured)) {
            getIntent().putExtra("server_url", configured);
            recreate();
            return;
        }
        if (origin.isEmpty() || list == null) return;
        resumed = true;
        network = monitor.current();
        TransferJobs.register(this, origin);
        ui.removeCallbacks(poll);
        ui.post(poll);
        load(true);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(poll);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (monitor != null) monitor.close();
        io.shutdownNow();
    }

    private void handleShare(Intent intent) {
        String shared = intent == null ? null : intent.getStringExtra("shared_url");
        if (shared == null || shared.isEmpty()) return;
        intent.removeExtra("shared_url");
        io.execute(() -> {
            boolean ask = true;
            String selection = "720";
            try {
                org.json.JSONObject session = new SessionStore(this, origin).read();
                org.json.JSONObject prefs = new LocalMediaPreferences(LinkOutboxDispatch.root(this, origin, session)).read();
                ask = prefs.optBoolean("ask_every_time", true);
                selection = prefs.optString("selection", "720");
            } catch (Exception ignored) { }
            if (ask) runOnUiThread(() -> AddLinkSheet.show(this, shared, null, ""));
            else AddLinkSheet.addDirect(this, shared, selection);
        });
    }

    // ---- Estructura ----
    private void build() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout page = Ds.column(this);

        LinearLayout top = Ds.row(this);
        top.setPadding(Ds.dp(this, 20), Ds.dp(this, 14), Ds.dp(this, 8), 0);
        Ds.addWeighted(top, Ds.text(this, t("Mi biblioteca"), 30, Ds.INK, 800), 1, 0, 8);
        ImageButton settings = Ds.iconButton(this, R.drawable.ic_ds_sliders, t("Ajustes"));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class).putExtra("server_url", origin)));
        top.addView(settings, new LinearLayout.LayoutParams(Ds.dp(this, 48), Ds.dp(this, 48)));
        page.addView(top, new LinearLayout.LayoutParams(-1, -2));

        banner = Ds.text(this, "", 14, Ds.WARN_DEEP, 600);
        banner.setBackground(Ds.rounded(this, Ds.WARN_BG, 0, 14));
        banner.setPadding(Ds.dp(this, 14), Ds.dp(this, 10), Ds.dp(this, 14), Ds.dp(this, 10));
        banner.setVisibility(View.GONE);
        Ds.add(page, banner, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 20, 10, 20, 0);

        LinearLayout search = Ds.row(this);
        search.setBackground(Ds.rounded(this, 0xFFFFFFFF, Ds.OUTLINE, 16));
        search.setPadding(Ds.dp(this, 14), 0, Ds.dp(this, 14), 0);
        search.addView(Ds.icon(this, R.drawable.ic_ds_search, Ds.MUTED), new LinearLayout.LayoutParams(Ds.dp(this, 22), Ds.dp(this, 22)));
        EditText box = new EditText(this);
        box.setBackground(null);
        box.setSingleLine(true);
        box.setHint(t("Buscar en tu biblioteca"));
        box.setTextSize(16);
        box.setTextColor(Ds.INK);
        box.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) { query = e.toString().trim().toLowerCase(Locale.ROOT); render(); }
        });
        Ds.addWeighted(search, box, 1, 10, 0);
        search.setMinimumHeight(Ds.dp(this, 52));
        Ds.add(page, search, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 20, 12, 20, 0);

        chips = Ds.row(this);
        Ds.add(page, chips, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 20, 10, 20, 0);
        paintChips();

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = Ds.column(this);
        list.setPadding(Ds.dp(this, 20), Ds.dp(this, 4), Ds.dp(this, 20), Ds.dp(this, 110));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        Button fab = Ds.button(this, "  " + t("Agregar enlace"), Ds.Kind.PRIMARY);
        fab.setCompoundDrawablesWithIntrinsicBounds(tinted(R.drawable.ic_ds_plus), null, null, null);
        fab.setOnClickListener(v -> AddLinkSheet.show(this, "", null, ""));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, Ds.bigButtonHeight(this), Gravity.BOTTOM | Gravity.END);
        fp.setMargins(0, 0, Ds.dp(this, 20), Ds.dp(this, 20));
        root.addView(fab, fp);

        toast = Ds.text(this, "", 15, 0xFFFFFFFF, 600);
        toast.setBackground(Ds.rounded(this, Ds.INK, 0, 16));
        toast.setPadding(Ds.dp(this, 16), Ds.dp(this, 12), Ds.dp(this, 16), Ds.dp(this, 12));
        toast.setVisibility(View.GONE);
        toast.setOnClickListener(v -> toast.setVisibility(View.GONE));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        tp.setMargins(Ds.dp(this, 20), 0, Ds.dp(this, 20), Ds.dp(this, 92));
        root.addView(toast, tp);

        setScreen(root);
    }

    private android.graphics.drawable.Drawable tinted(int res) {
        android.graphics.drawable.Drawable d = getDrawable(res).mutate();
        d.setTint(0xFFFFFFFF);
        d.setBounds(0, 0, Ds.dp(this, 22), Ds.dp(this, 22));
        return d;
    }

    private void paintChips() {
        chips.removeAllViews();
        String[][] defs = {{"all", t("Todo")}, {"video", t("Video")}, {"audio", t("Audio")}};
        for (String[] d : defs) {
            boolean on = d[0].equals(filter);
            Button b = Ds.button(this, d[1], Ds.Kind.TEXT);
            b.setMinHeight(Ds.dp(this, 40));
            b.setTextColor(on ? 0xFFFFFFFF : Ds.BRAND_DARK);
            b.setBackground(Ds.ripple(this, Ds.rounded(this, on ? Ds.BRAND : Ds.SOFT, 0, 20)));
            b.setOnClickListener(v -> { filter = d[0]; paintChips(); render(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ds.dp(this, 40));
            lp.setMarginEnd(Ds.dp(this, 8));
            chips.addView(b, lp);
        }
    }

    // ---- Datos ----
    private boolean hasActive() {
        for (Item i : items) if (i.state.inProgress() || i.state == ItemState.WAITING_OFFLINE || i.state == ItemState.WAITING_WIFI
                || (i.state == ItemState.REMOTE_ONLY && i.serverAvailable && i.localState.isEmpty())) return true;
        return false;
    }

    /** Recarga la biblioteca; con {@code fetch} intenta actualizar desde el servidor. */
    private void load(boolean fetch) {
        if (io.isShutdown()) return;
        if (loading) { reloadPending = true; return; }
        loading = true;
        final DownloadPolicy.NetworkState net = network;
        io.execute(() -> {
            try {
                LibraryRepo.Result r = LibraryRepo.load(this, origin, DownloadPolicy.allowsControl(net), "", net);
                runOnUiThread(() -> {
                    loading = false;
                    if (isDestroyed()) return;
                    items.clear();
                    items.addAll(r.outbox);
                    items.addAll(r.items);
                    nextCursor = r.nextCursor;
                    online = r.online;
                    loaded = true;
                    render();
                    if (resumed && reloadPending) { reloadPending = false; refresh(); }
                });
            } catch (LibraryRepo.NoSession e) {
                runOnUiThread(() -> {
                    loading = false;
                    startActivity(new Intent(this, OnboardingActivity.class).putExtra("server_url", origin));
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { loading = false; loaded = true; render(); });
            }
        });
    }

    private void loadMore() {
        if (loading || nextCursor.isEmpty()) return;
        loading = true;
        final String cursor = nextCursor;
        final DownloadPolicy.NetworkState net = network;
        io.execute(() -> {
            try {
                LibraryRepo.Result r = LibraryRepo.load(this, origin, true, cursor, net);
                runOnUiThread(() -> {
                    loading = false;
                    for (Item n : r.items) {
                        boolean known = false;
                        for (Item o : items) if (o.id.equals(n.id)) { known = true; break; }
                        if (!known) items.add(n);
                    }
                    nextCursor = r.nextCursor;
                    render();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { loading = false; message(t("No se pudo cargar más. Revisa tu conexión.")); });
            }
        });
    }

    // ---- Dibujo ----
    private boolean matches(Item i) {
        if ("video".equals(filter) && i.isAudio()) return false;
        if ("audio".equals(filter) && !i.isAudio()) return false;
        if (query.isEmpty()) return true;
        return (i.title + " " + i.host).toLowerCase(Locale.ROOT).contains(query);
    }

    private void render() {
        if (list == null) return;
        boolean offline = !DownloadPolicy.allowsControl(network);
        banner.setVisibility(offline ? View.VISIBLE : View.GONE);
        banner.setText(t("Sin conexión. Puedes reproducir lo guardado y agregar enlaces; se envían al volver la red."));

        list.removeAllViews();
        List<Item> shown = new ArrayList<>();
        for (Item i : items) if (matches(i)) shown.add(i);
        Collections.sort(shown, (x, y) -> y.createdAt.compareTo(x.createdAt));

        if (!loaded) {
            list.addView(Ds.text(this, t("Cargando…"), 16, Ds.MUTED, 600));
            return;
        }
        if (items.isEmpty()) { empty(); return; }
        if (shown.isEmpty()) {
            Ds.add(list, Ds.text(this, t("No hay resultados. Prueba con otra búsqueda o filtro."), 16, Ds.MUTED, 600), 24);
            return;
        }

        section(shown, ItemState.Group.ATTENTION, t("Necesita atención"));
        section(shown, ItemState.Group.NOW, t("Ahora"));
        section(shown, ItemState.Group.SAVED, t("En tu teléfono"));
        section(shown, ItemState.Group.PAST, t("Anteriores"));

        if (!nextCursor.isEmpty()) {
            Button more = Ds.button(this, t("Ver más"), Ds.Kind.SECONDARY);
            more.setOnClickListener(v -> loadMore());
            Ds.add(list, more, LinearLayout.LayoutParams.MATCH_PARENT, Ds.bigButtonHeight(this), 0, 16, 0, 0);
        }
    }

    private void section(List<Item> shown, ItemState.Group group, String title) {
        List<Item> rows = new ArrayList<>();
        for (Item i : shown) if (i.state.group() == group) rows.add(i);
        if (rows.isEmpty()) return;
        TextView h = Ds.text(this, title + " · " + rows.size(), 15, group == ItemState.Group.ATTENTION ? Ds.ERR : Ds.MUTED, 800);
        Ds.add(list, h, 20);
        for (Item i : rows) Ds.add(list, card(i), 10);
    }

    private View card(Item i) {
        LinearLayout card = Ds.row(this);
        card.setBackground(Ds.ripple(this, Ds.rounded(this, 0xFFFFFFFF, i.state == ItemState.FAILED ? Ds.ERR : Ds.LINE, 20)));
        card.setPadding(Ds.dp(this, 12), Ds.dp(this, 12), Ds.dp(this, 14), Ds.dp(this, 12));
        card.setMinimumHeight(Ds.dp(this, 80));
        card.setClickable(true);
        card.setContentDescription(i.title + ", " + ItemFlows.stateLabel(this, i));
        boolean bad = i.state == ItemState.FAILED;
        card.addView(Ds.thumb(this, i.isAudio(), bad ? Ds.ERR_BG : Ds.SOFT, bad ? Ds.ERR : Ds.BRAND_DARK, 56),
                new LinearLayout.LayoutParams(Ds.dp(this, 56), Ds.dp(this, 56)));

        LinearLayout col = Ds.column(this);
        TextView title = Ds.text(this, i.title, 16, Ds.INK, 800);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title);
        String meta = i.outbox ? i.host : ItemFlows.meta(this, i);
        TextView m = Ds.text(this, meta, 13, Ds.MUTED, 500);
        m.setMaxLines(1);
        Ds.add(col, m, 2);
        Ds.add(col, Ds.badge(this, ItemFlows.stateLabel(this, i), ItemFlows.tone(i)),
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0);
        int pct = i.state == ItemState.SAVING ? i.phonePercent() : i.state == ItemState.PREPARING ? i.serverPercent() : -1;
        if (pct >= 0) Ds.add(col, Ds.progress(this, pct, Ds.BRAND, Ds.TRACK, pct + " %"), LinearLayout.LayoutParams.MATCH_PARENT, Ds.dp(this, 6), 0, 8, 0, 0);
        Ds.addWeighted(card, col, 1, 12, 0);

        if (i.state == ItemState.READY) {
            ImageButton play = Ds.iconButton(this, R.drawable.ic_ds_play, t("Reproducir"));
            play.setBackground(Ds.ripple(this, Ds.rounded(this, Ds.BRAND, 0, 24)));
            play.setColorFilter(0xFFFFFFFF);
            play.setOnClickListener(v -> ItemFlows.play(this, i));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Ds.dp(this, 48), Ds.dp(this, 48));
            pl.setMarginStart(Ds.dp(this, 8));
            card.addView(play, pl);
        } else {
            card.addView(Ds.icon(this, R.drawable.ic_ds_chevron, Ds.MUTED), new LinearLayout.LayoutParams(Ds.dp(this, 22), Ds.dp(this, 22)));
        }
        card.setOnClickListener(v -> ItemFlows.detail(this, i, items));
        return card;
    }

    private void empty() {
        LinearLayout box = Ds.column(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, Ds.dp(this, 48), 0, 0);
        box.addView(Ds.thumb(this, false, Ds.SOFT, Ds.BRAND_DARK, 96), new LinearLayout.LayoutParams(Ds.dp(this, 96), Ds.dp(this, 96)));
        TextView h = Ds.text(this, t("Tu biblioteca empieza aquí"), 22, Ds.INK, 800);
        h.setGravity(Gravity.CENTER);
        Ds.add(box, h, 18);
        TextView p = Ds.text(this, t("Comparte un enlace desde otra app o pégalo aquí. Lo que descargues se queda en tu teléfono, listo para ver sin conexión."), 15, Ds.MUTED, 500);
        p.setGravity(Gravity.CENTER);
        Ds.add(box, p, 8);
        Button add = Ds.button(this, t("Pegar un enlace"), Ds.Kind.PRIMARY);
        add.setOnClickListener(v -> AddLinkSheet.show(this, "", null, ""));
        Ds.add(box, add, LinearLayout.LayoutParams.WRAP_CONTENT, Ds.bigButtonHeight(this), 0, 20, 0, 0);
        list.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }
}
