package dev.arglabs.tubego.spike;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;

/** Manual diagnostic caller. Raw callbacks are evidence; never infer completion. */
@SuppressWarnings("deprecation")
public final class ProbeActivity extends Activity {
    private TextView evidence;
    private EditText scenario, position, rewind;
    private boolean resumed;
    private android.widget.CheckBox longClip;

    /** Diagnostic automation still launches VLC from this real foreground Activity. */
    private void command() {
        Intent command = getIntent();
        if (command == null || !resumed) return;
        String action = command.getAction();
        if (!(getPackageName() + ".RUN").equals(action)
                && !(getPackageName() + ".ABANDON").equals(action)) return;
        command.setAction(null); // Consume once, including after recreation.
        if (action.endsWith(".ABANDON")) {
            log("abandoned", object("source", "adb_command"));
            getPreferences(0).edit().remove("pending").commit();
            return;
        }
        // Android may restore the original launch Intent after process death.
        // Persist command consumption, not just the mutated in-memory action.
        String commandId = command.getStringExtra("command_id");
        if (commandId == null) commandId = command.getStringExtra("scenario");
        if (commandId == null || commandId.isEmpty()) {
            log("error", object("message", "RUN requires command_id or scenario"));
            return;
        }
        String consumed = "command_seen_" + commandId;
        if (getPreferences(0).getBoolean(consumed, false)) return;
        if (!getPreferences(0).edit().putBoolean(consumed, true).commit()) {
            log("error", object("message", "Could not persist command consumption"));
            return;
        }
        scenario.setText(command.getStringExtra("scenario"));
        position.setText(Long.toString(command.getLongExtra("saved_ms", 20000)));
        rewind.setText(Integer.toString(command.getIntExtra("rewind_s", 10)));
        longClip.setChecked(command.getBooleanExtra("long_clip", false));
        launch();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        log("new_intent", object("action", intent.getAction()));
        command();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(24, 24, 24, 24);
        TextView title = new TextView(this); title.setText("PLA-257 · VLC externo · clip sintético de 45 s"); body.addView(title);
        scenario = field(body, "Caso (normal, Home, PiP, bloqueo, audio, cierre…)", "normal");
        position = field(body, "Posición guardada (milisegundos)", "20000");
        rewind = field(body, "Retroceso (segundos)", "10");
        longClip = new android.widget.CheckBox(this); longClip.setText("Usar fixture largo sintético previamente cargado"); body.addView(longClip);
        Button launch = new Button(this); launch.setText("Abrir clip en VLC y capturar resultado"); body.addView(launch);
        launch.setOnClickListener(v -> launch());
        Button abandon = new Button(this); abandon.setText("Cerrar ensayo pendiente (sin resultado)"); body.addView(abandon);
        abandon.setOnClickListener(v -> { log("abandoned", new JSONObject()); getPreferences(0).edit().remove("pending").commit(); });
        Button refresh = new Button(this); refresh.setText("Actualizar evidencia"); body.addView(refresh); refresh.setOnClickListener(v -> show());
        evidence = new TextView(this); evidence.setTextIsSelectable(true); body.addView(evidence);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
        log(state == null ? "created" : "recreated", new JSONObject()); show();
    }

    private EditText field(LinearLayout body, String hint, String value) {
        TextView label = new TextView(this); label.setText(hint); body.addView(label);
        EditText input = new EditText(this); input.setText(value); body.addView(input); return input;
    }

    private void launch() {
        try {
            if (!getPreferences(0).getString("pending", "").isEmpty()) throw new IllegalStateException("Cierra el ensayo pendiente antes de abrir otro");
            long saved = Long.parseLong(position.getText().toString());
            int seconds = Integer.parseInt(rewind.getText().toString());
            android.net.Uri fixture = longClip.isChecked() ? ProbeMediaProvider.LONG_CLIP : ProbeMediaProvider.CLIP;
            long duration;
            try (android.content.res.AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(fixture, "r");
                 android.media.MediaMetadataRetriever metadata = new android.media.MediaMetadataRetriever()) {
                metadata.setDataSource(fd.getFileDescriptor());
                duration = Long.parseLong(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
            }
            if (saved < 0 || saved > duration || seconds < 0 || seconds > 120) throw new IllegalArgumentException("Posición dentro del clip; retroceso 0–120 s");
            String id = UUID.randomUUID().toString();
            int request = getPreferences(0).getInt("last_request", VlcProbe.REQUEST) + 1;
            if (request > 65535) throw new IllegalStateException("Se agotaron los identificadores del probe");
            if (!getPreferences(0).edit().putString("pending", id).putInt("last_request", request)
                    .putString("trial_" + request, id).commit()) throw new IllegalStateException("No se pudo persistir el ensayo");
            JSONObject detail = new JSONObject().put("scenario", scenario.getText().toString())
                    .put("saved_ms", saved).put("rewind_s", seconds).put("requested_ms", ResumePosition.startMillis(saved, seconds))
                    .put("request", request).put("model", Build.MODEL).put("api", Build.VERSION.SDK_INT)
                    .put("vlc", version("org.videolan.vlc")).put("tubego", version("dev.arglabs.tubego"))
                    .put("clip_uri", fixture.toString()).put("clip_duration_ms", duration);
            try (var input = getContentResolver().openInputStream(fixture)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] block = new byte[8192];
                for (int n; (n = input.read(block)) != -1;) digest.update(block, 0, n);
                StringBuilder hash = new StringBuilder();
                for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                detail.put("clip_sha256", hash.toString());
            }
            log("launch", detail);
            VlcProbe.Launch result = VlcProbe.open(this, fixture, "video/mp4", saved, seconds, request);
            log("launch_return", new JSONObject().put("status", result.name()));
            String callerEvent = getIntent().getStringExtra("caller_event");
            getIntent().removeExtra("caller_event");
            if (result == VlcProbe.Launch.STARTED && ("die".equals(callerEvent) || "recreate".equals(callerEvent))) {
                new android.os.Handler(getMainLooper()).postDelayed(() -> {
                    log("caller_" + callerEvent, object("pid", android.os.Process.myPid()), id);
                    if ("die".equals(callerEvent)) android.os.Process.killProcess(android.os.Process.myPid());
                    else recreate();
                }, 3000);
            }
            if (result != VlcProbe.Launch.STARTED) getPreferences(0).edit().remove("pending").commit();
        } catch (Exception error) { log("error", object("message", error.getMessage())); }
    }

    private String version(String name) {
        try { var info = getPackageManager().getPackageInfo(name, 0); return info.versionName + " (" + info.getLongVersionCode() + ")"; }
        catch (Exception missing) { return "unavailable"; }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        String trial = getPreferences(0).getString("trial_" + request, "");
        JSONObject detail = object("request", request);
        try {
            detail.put("result", result).put("action", data == null ? JSONObject.NULL : data.getAction());
            Bundle extras = data == null ? null : data.getExtras();
            JSONObject raw = new JSONObject();
            if (extras != null) for (String key : extras.keySet()) {
                Object value = extras.get(key);
                JSONObject item = new JSONObject().put("type", value == null ? "null" : value.getClass().getName());
                if (value instanceof Number || value instanceof Boolean || value instanceof String) item.put("value", value);
                raw.put(key, item);
            }
            detail.put("extras", raw);
            for (String key : new String[]{"extra_position", "extra_duration"}) {
                Object value = extras == null ? null : extras.get(key);
                detail.put(key + "_type", value == null ? "missing" : value.getClass().getName());
                detail.put(key, value instanceof Number ? value : JSONObject.NULL);
            }
            VlcProbe.Checkpoint checkpoint = trial.isEmpty() ? null : VlcProbe.result(VlcProbe.REQUEST, result, data);
            detail.put("accepted_checkpoint", checkpoint != null);
        } catch (RuntimeException | org.json.JSONException malformed) { detail = object("malformed", true); }
        log("activity_result", detail, trial);
        if (!trial.isEmpty() && trial.equals(getPreferences(0).getString("pending", ""))) {
            getPreferences(0).edit().remove("pending").commit();
        }
    }

    @Override protected void onResume() { super.onResume(); resumed = true; log("resume", new JSONObject()); command(); }
    @Override protected void onPause() { resumed = false; log("pause", new JSONObject()); super.onPause(); }
    @Override protected void onStop() { log("stop", new JSONObject()); super.onStop(); }
    @Override protected void onDestroy() { log("destroy", object("finishing", isFinishing())); super.onDestroy(); }

    private JSONObject object(String key, Object value) {
        try { return new JSONObject().put(key, value); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private void log(String event, JSONObject detail) {
        log(event, detail, getPreferences(0).getString("pending", ""));
    }

    private void log(String event, JSONObject detail, String trial) {
        try {
            JSONObject record = new JSONObject().put("utc", java.time.Instant.now().toString()).put("elapsed_ms", SystemClock.elapsedRealtime())
                    .put("event", event).put("trial", trial).put("detail", detail);
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "evidence.jsonl"), true)) {
                out.write((record + "\n").getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            show();
        } catch (Exception failed) { if (evidence != null) evidence.setText("No se pudo guardar evidencia: " + failed); }
    }

    private void show() {
        if (evidence == null) return;
        try { evidence.setText(new String(Files.readAllBytes(new File(getFilesDir(), "evidence.jsonl").toPath()), StandardCharsets.UTF_8)); }
        catch (Exception missing) { evidence.setText("Sin evidencia todavía"); }
    }
}
