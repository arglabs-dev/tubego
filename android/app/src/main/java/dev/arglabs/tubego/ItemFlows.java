package dev.arglabs.tubego;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONObject;

/** Hojas de detalle y acciones sobre un archivo. Usa las mismas colas y reglas que la versión anterior. */
final class ItemFlows {
    private ItemFlows() {}

    // ---------- Textos de estado ----------

    static String stateLabel(DsActivity a, Item i) {
        switch (i.state) {
            case WAITING_WIFI: return a.t("En espera · Wi-Fi");
            case WAITING_OFFLINE: return a.t("En espera · sin conexión");
            case PREPARING: return "queued".equals(i.taskState) ? Texts.state(a, "queued") : a.t("En proceso · paso 1 de 2");
            case SAVING: return a.t("En proceso · paso 2 de 2");
            case READY: return a.t("Listo · en este teléfono");
            case PAUSED_SPACE: return a.t("En pausa · poco espacio");
            case FAILED: return retryable(i.failureCode) ? a.t("Se interrumpió") : a.t("No se pudo descargar");
            case CANCELLED: return a.t("Cancelado");
            case UNAVAILABLE: return a.t("Borrado de este teléfono");
            default: return a.t("En el servidor");
        }
    }

    static Ds.Tone tone(Item i) {
        switch (i.state) {
            case WAITING_WIFI: case WAITING_OFFLINE: case PAUSED_SPACE: return Ds.Tone.WAIT;
            case PREPARING: case SAVING: return Ds.Tone.PROGRESS;
            case READY: return Ds.Tone.OK;
            case FAILED: return Ds.Tone.ATTENTION;
            default: return Ds.Tone.NEUTRAL;
        }
    }

    static boolean retryable(String code) {
        switch (code == null ? "" : code) {
            case "private": case "authentication_required": case "unavailable": case "source_restricted":
            case "unsupported": case "unsupported_url": case "invalid_url": return false;
            default: return true;
        }
    }

    static boolean needsOtherQuality(String code) {
        return "format_unavailable".equals(code) || "conversion_failed".equals(code);
    }

    /** Causa del error en una frase, con el vocabulario de la propuesta. */
    static String failureText(DsActivity a, String code) {
        switch (code == null ? "" : code) {
            case "temporary_failure": case "temporary_network_failure": case "network_error": case "download_timeout": case "download_failed_transient":
                return a.t("La conexión o la fuente falló por un momento. Casi siempre se resuelve al reintentar.");
            case "integrity_mismatch": case "checksum_mismatch": case "size_mismatch":
                return a.t("El archivo llegó incompleto o dañado. Vuelve a intentarlo.");
            case "format_unavailable":
                return a.t("Esta calidad no está disponible para este enlace. Prueba con otra o con solo audio.");
            case "conversion_failed":
                return a.t("No se pudo preparar el archivo en esta calidad. Prueba con otra o con solo audio.");
            case "private": case "authentication_required":
                return a.t("Este contenido es privado o pide iniciar sesión en su plataforma. TubeGo no puede descargarlo.");
            case "unavailable":
                return a.t("Este contenido ya no está disponible en su plataforma.");
            case "source_restricted":
                return a.t("La plataforma limita el acceso a este contenido, por ejemplo por país, edad o permisos.");
            case "unsupported": case "unsupported_url":
                return a.t("TubeGo no admite este enlace.");
            case "invalid_url":
                return a.t("Este enlace no es válido. Pega un solo enlace que empiece con http o https.");
            case "resource_unavailable": case "file_missing":
                return a.t("El archivo ya no está en el servidor. Puedes pedirlo otra vez.");
            default:
                return a.t("No se pudo descargar. Revisa el enlace y vuelve a intentarlo.");
        }
    }

    // ---------- Piezas de interfaz ----------

    private static LinearLayout header(DsActivity a, Item i) {
        LinearLayout row = Ds.row(a);
        row.addView(Ds.thumb(a, i.isAudio(), i.state == ItemState.FAILED ? Ds.ERR_BG : Ds.SOFT, i.state == ItemState.FAILED ? Ds.ERR : Ds.BRAND_DARK, 68),
                new LinearLayout.LayoutParams(Ds.dp(a, 68), Ds.dp(a, 68)));
        LinearLayout col = Ds.column(a);
        col.addView(Ds.text(a, i.title, 20, Ds.INK, 800));
        TextView badge = Ds.badge(a, stateLabel(a, i), tone(i));
        Ds.add(col, badge, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0);
        Ds.addWeighted(row, col, 1, 14, 0);
        return row;
    }

    static String meta(DsActivity a, Item i) {
        StringBuilder sb = new StringBuilder(Texts.quality(a, i.selection));
        String d = Item.formatDuration(i.durationSeconds), s = Item.formatSize(i.sizeBytes);
        if (!d.isEmpty()) sb.append(" · ").append(d);
        if (!s.isEmpty()) sb.append(" · ").append(s);
        return sb.toString();
    }

    private static void line(DsActivity a, LinearLayout parent, int icon, String text) {
        LinearLayout row = Ds.row(a);
        row.addView(Ds.icon(a, icon, Ds.MUTED), new LinearLayout.LayoutParams(Ds.dp(a, 22), Ds.dp(a, 22)));
        Ds.addWeighted(row, Ds.text(a, text, 15, Ds.INK, 500), 1, 12, 0);
        Ds.add(parent, row, 10);
    }

    private static void notice(DsActivity a, LinearLayout parent, int icon, String text, int bg, int fg) {
        LinearLayout box = Ds.row(a);
        box.setBackground(Ds.rounded(a, bg, 0, 16));
        box.setPadding(Ds.dp(a, 14), Ds.dp(a, 14), Ds.dp(a, 14), Ds.dp(a, 14));
        box.addView(Ds.icon(a, icon, fg), new LinearLayout.LayoutParams(Ds.dp(a, 22), Ds.dp(a, 22)));
        Ds.addWeighted(box, Ds.text(a, text, 14, fg, 500), 1, 12, 0);
        Ds.add(parent, box, 14);
    }

    private static Button big(DsActivity a, LinearLayout parent, String label, Ds.Kind kind, float top, Runnable run) {
        Button b = Ds.button(a, label, kind);
        b.setOnClickListener(v -> run.run());
        Ds.add(parent, b, LinearLayout.LayoutParams.MATCH_PARENT, kind == Ds.Kind.TEXT || kind == Ds.Kind.DANGER_TEXT ? Ds.dp(a, 48) : Ds.bigButtonHeight(a), 0, top, 0, 0);
        return b;
    }

    // ---------- Detalle ----------

    static void detail(Host host, Item i, List<Item> all) {
        DsActivity a = host.act();
        Sheet sheet = new Sheet(a);
        sheet.title(i.state == ItemState.FAILED ? (retryable(i.failureCode) ? a.t("Se interrumpió la descarga") : a.t("No se pudo descargar")) : a.t("Detalle"), a.t("Cerrar"));
        LinearLayout body = sheet.body();
        Ds.add(body, header(a, i), 6);
        if (!i.outbox) Ds.add(body, Ds.text(a, meta(a, i), 15, Ds.MUTED, 500), 8);

        if(i.downloaded) {
            TextView location=Ds.text(a,a.t("Ubicación del archivo"),13,Ds.MUTED,500);location.setTextIsSelectable(true);Ds.add(body,location,10);
            host.io().execute(()->{try{
                org.json.JSONObject session=new SessionStore(a,host.origin()).read();if(session==null)return;
                java.io.File root=LinkOutboxDispatch.root(a,host.origin(),session);
                TransferRecord record=OfflineMediaAccess.record(root,i.id);String path=SharedMedia.location(a,record);
                a.runOnUiThread(()->{try{if(sheet.isShowing()&&session.getString("token").equals(new SessionStore(a,host.origin()).token()))location.setText(a.t("Ubicación del archivo")+"\n"+path);}catch(Exception ignored){}});
            }catch(Exception ignored){}});
        }
        switch (i.state) {
            case READY:
                big(a, body, a.t("Reproducir"), Ds.Kind.PRIMARY, 16, () -> { sheet.dismiss(); play(host, i); });
                Button move=Ds.button(a,a.t("Mover a la carpeta de descargas"),Ds.Kind.SECONDARY);
                move.setVisibility(android.view.View.GONE);Ds.add(body,move,8);
                host.io().execute(()->{try{org.json.JSONObject s=new SessionStore(a,host.origin()).read();if(s==null)return;TransferRecord r=OfflineMediaAccess.record(LinkOutboxDispatch.root(a,host.origin(),s),i.id);if(r.sharedUri.isEmpty())a.runOnUiThread(()->{if(sheet.isShowing())move.setVisibility(android.view.View.VISIBLE);});}catch(Exception ignored){}});
                move.setOnClickListener(v->{move.setEnabled(false);host.message(a.t("Moviendo el archivo a la carpeta elegida…"));host.io().execute(()->{
                    try{org.json.JSONObject s=new SessionStore(a,host.origin()).read();if(s==null)return;TransferRecord r;
                        synchronized(SessionStore.class){if(!s.getString("token").equals(new SessionStore(a,host.origin()).token()))return;r=OfflineMediaAccess.record(LinkOutboxDispatch.root(a,host.origin(),s),i.id);r.publishRequested=true;r.save();}
                        SharedMedia.publish(a,host.origin(),s,r);a.runOnUiThread(()->{sheet.dismiss();host.message(a.t("Archivo movido. Se conserva una sola copia."));host.refresh();});
                    }catch(Exception error){a.runOnUiThread(()->{move.setEnabled(true);host.message(Texts.error(a,error));});}
                });});
                if (!i.isAudio()) big(a, body, a.t("Compartir video"), Ds.Kind.SECONDARY, 8,
                        () -> { sheet.dismiss(); share(host, i); });
                LinearLayout two = Ds.row(a);
                if (i.serverAvailable) {
                    Button next = Ds.button(a, a.t("Poner como siguiente"), Ds.Kind.SECONDARY);
                    next.setOnClickListener(v -> { sheet.dismiss(); command(host, "resource_priority", "resource_id", i.id, a.t("Listo: se descargará antes que los demás.")); });
                    Ds.addWeighted(two, next, 1, 0, 5);
                }
                Button other = Ds.button(a, a.t("Pedir otra calidad"), Ds.Kind.SECONDARY);
                other.setOnClickListener(v -> { sheet.dismiss(); AddLinkSheet.show(host, i.sourceUrl, i.selection, i.title); });
                Ds.addWeighted(two, other, 1, i.serverAvailable ? 5 : 0, 0);
                Ds.add(body, two, 10);
                String warn = Texts.qualityNotice(a, i.qualityNoticeCode);
                if (!warn.isEmpty()) notice(a, body, R.drawable.ic_ds_info, warn, Ds.WARN_BG, 0xFF5E3700);
                line(a, body, R.drawable.ic_ds_check, a.t("Disponible sin conexión"));
                line(a, body, R.drawable.ic_ds_play, a.t("Se abre con tu reproductor (VLC u otro)"));
                big(a, body, a.t("Borrar…"), Ds.Kind.DANGER_TEXT, 8, () -> { sheet.dismiss(); deleteSheet(host, i); });
                break;

            case WAITING_WIFI:
                notice(a, body, R.drawable.ic_ds_wifi, a.t("Esta descarga empezará en cuanto te conectes a una red Wi-Fi."), Ds.WARN_BG, Ds.WARN);
                big(a, body, a.t("Usar datos móviles esta vez"), Ds.Kind.PRIMARY, 14, () -> { sheet.dismiss(); allowMobile(host, i); });
                addCommonProgressActions(host, sheet, i);
                break;

            case WAITING_OFFLINE:
                if (i.outbox) {
                    if ("error".equals(i.outboxState)) {
                        notice(a, body, R.drawable.ic_ds_alert, failureText(a, i.failureCode), Ds.ERR_BG, Ds.ERR);
                        big(a, body, a.t("Reintentar"), Ds.Kind.PRIMARY, 14, () -> { sheet.dismiss(); retryOutbox(host, i); });
                    } else {
                        notice(a, body, R.drawable.ic_ds_wifi_off, a.t("Se enviará cuando vuelva la conexión. No tienes que hacer nada."), Ds.WARN_BG, Ds.WARN);
                    }
                } else {
                    notice(a, body, R.drawable.ic_ds_wifi_off, a.t("Sin conexión. La descarga sigue cuando vuelva la red."), Ds.WARN_BG, Ds.WARN);
                    addCommonProgressActions(host, sheet, i);
                }
                break;

            case PREPARING:
            case SAVING:
                int pct = i.state == ItemState.SAVING ? i.phonePercent() : i.serverPercent();
                Ds.add(body, Ds.text(a, i.state == ItemState.SAVING ? a.t("Guardando en tu teléfono") : a.t("Preparando en el servidor"), 15, Ds.INK, 700), 14);
                if (pct >= 0) Ds.add(body, Ds.progress(a, pct, Ds.BRAND, Ds.TRACK, pct + " %"), LinearLayout.LayoutParams.MATCH_PARENT, Ds.dp(a, 10), 0, 8, 0, 0);
                if (i.mobileAuthorized) {
                    big(a, body, a.t("Volver a usar solo Wi-Fi"), Ds.Kind.SECONDARY, 14, () -> { sheet.dismiss(); revokeMobile(host, i); });
                }
                addCommonProgressActions(host, sheet, i);
                break;

            case PAUSED_SPACE:
                notice(a, body, R.drawable.ic_ds_storage, a.t("Tu teléfono tiene menos espacio libre que el mínimo que elegiste. No se borró nada; la descarga sigue sola al liberar espacio."), Ds.WARN_BG, Ds.WARN);
                big(a, body, a.t("Liberar espacio"), Ds.Kind.PRIMARY, 14, () -> { sheet.dismiss(); freeSpace(host, all); });
                big(a, body, a.t("Cambiar el mínimo libre"), Ds.Kind.TEXT, 4, () -> { sheet.dismiss(); a.startActivity(new Intent(a, SettingsActivity.class).putExtra("server_url", host.origin())); });
                break;

            case FAILED:
                if (i.outbox) {
                    Ds.add(body, Ds.text(a, failureText(a, i.failureCode), 17, Ds.INK, 500), 14);
                    big(a, body, a.t("Reintentar"), Ds.Kind.PRIMARY, 16, () -> { sheet.dismiss(); retryOutbox(host, i); });
                    break;
                }
                Ds.add(body, Ds.text(a, failureText(a, i.failureCode), 17, Ds.INK, 500), 14);
                if (retryable(i.failureCode)) {
                    int saved = i.phonePercent();
                    if (saved > 0) {
                        LinearLayout box = Ds.column(a);
                        box.setBackground(Ds.rounded(a, Ds.PAPER, 0, 18));
                        box.setPadding(Ds.dp(a, 14), Ds.dp(a, 14), Ds.dp(a, 14), Ds.dp(a, 14));
                        box.addView(Ds.text(a, a.tf("Tu avance se conserva · %d %%", saved), 14, Ds.MUTED, 600));
                        Ds.add(box, Ds.progress(a, saved, Ds.BRAND, Ds.TRACK, saved + " %"), LinearLayout.LayoutParams.MATCH_PARENT, Ds.dp(a, 10), 0, 8, 0, 0);
                        box.addView(Ds.text(a, a.t("Al reintentar sigue desde ahí, no vuelve a empezar."), 14, Ds.MUTED, 500));
                        Ds.add(body, box, 14);
                    }
                    big(a, body, a.t("Reintentar"), Ds.Kind.PRIMARY, 16, () -> { sheet.dismiss(); retry(host, i); });
                } else if (needsOtherQuality(i.failureCode)) {
                    big(a, body, a.t("Elegir otra calidad"), Ds.Kind.PRIMARY, 16, () -> { sheet.dismiss(); AddLinkSheet.show(host, i.sourceUrl, null, i.title); });
                }
                if (retryable(i.failureCode) && needsOtherQuality(i.failureCode)) {
                    big(a, body, a.t("Elegir otra calidad"), Ds.Kind.SECONDARY, 8, () -> { sheet.dismiss(); AddLinkSheet.show(host, i.sourceUrl, null, i.title); });
                }
                big(a, body, a.t("Borrar…"), Ds.Kind.DANGER_TEXT, 6, () -> { sheet.dismiss(); deleteSheet(host, i); });
                break;

            default: // CANCELLED, UNAVAILABLE, REMOTE_ONLY
                Ds.add(body, Ds.text(a, i.state == ItemState.UNAVAILABLE ? a.t("El archivo ya no está en este teléfono. Puedes pedirlo otra vez.") : a.t("Este archivo no está en tu teléfono."), 16, Ds.INK, 500), 14);
                big(a, body, a.t("Pedir otra vez"), Ds.Kind.PRIMARY, 16, () -> { sheet.dismiss(); recover(host, i); });
                big(a, body, a.t("Pedir otra calidad"), Ds.Kind.SECONDARY, 8, () -> { sheet.dismiss(); AddLinkSheet.show(host, i.sourceUrl, i.selection, i.title); });
                big(a, body, a.t("Borrar…"), Ds.Kind.DANGER_TEXT, 6, () -> { sheet.dismiss(); deleteSheet(host, i); });
                break;
        }
        sheet.show();
    }

    private static void addCommonProgressActions(Host host, Sheet sheet, Item i) {
        DsActivity a = host.act();
        LinearLayout body = sheet.body();
        if (i.serverAvailable || i.state.inProgress()) {
            big(a, body, a.t("Poner como siguiente"), Ds.Kind.SECONDARY, 8, () -> { sheet.dismiss(); command(host, "resource_priority", "resource_id", i.id, a.t("Listo: se descargará antes que los demás.")); });
        }
        if (i.canCancelOnServer()) {
            big(a, body, a.t("Cancelar descarga"), Ds.Kind.DANGER_TEXT, 4, () -> { sheet.dismiss(); command(host, "task_cancel", "task_id", i.taskId, a.t("Cancelación guardada. Se enviará con cualquier conexión.")); });
        }
    }

    // ---------- Acciones ----------

    /** Guarda una orden para el servidor (prioridad, cancelar, reintentar) y la envía en cuanto hay red. */
    static void command(Host host, String kind, String key, String value, String okMessage) {
        DsActivity a = host.act();
        host.io().execute(() -> {
            try {
                synchronized (SessionStore.class) {
                    CommandDispatch.enqueue(a, host.origin(), kind, new JSONObject().put(key, value));
                }
                a.runOnUiThread(() -> host.message(okMessage));
                CommandDispatch.flush(a, host.origin());
                a.runOnUiThread(host::refresh);
            } catch (Exception e) {
                a.runOnUiThread(() -> host.message(a.t("No se pudo guardar la acción. Revisa la sesión y el espacio del teléfono.")));
            }
        });
    }

    static void retry(Host host, Item i) {
        DsActivity a = host.act();
        if (i.canRetryOnServer()) {
            command(host, "task_retry", "task_id", i.taskId, a.t("Reintento guardado. Se enviará con cualquier conexión."));
            return;
        }
        host.io().execute(() -> {
            try {
                synchronized (SessionStore.class) {
                    JSONObject session = new SessionStore(a, host.origin()).read();
                    File root = LinkOutboxDispatch.root(a, host.origin(), session);
                    if (LocalResourceDeletion.deleted(root, i.id)) throw new Exception();
                    TransferRecord latest = TransferRecord.read(new File(root, i.id + ".properties"));
                    if ("failed".equals(latest.state)) TransferRetry.reset(latest);
                }
                TransferJobs.wake(a, host.origin(), false);
                a.runOnUiThread(() -> { host.message(a.t("Reintento solicitado. Se mantienen los permisos de red de este archivo.")); host.refresh(); });
            } catch (Exception e) {
                a.runOnUiThread(() -> host.message(a.t("No se pudo reintentar. Revisa tu sesión.")));
            }
        });
    }

    static void retryOutbox(Host host, Item i) {
        DsActivity a = host.act();
        host.io().execute(() -> {
            try {
                synchronized (SessionStore.class) {
                    JSONObject session = new SessionStore(a, host.origin()).read();
                    LinkOutboxDispatch.box(a, host.origin(), session).retry(i.outboxId);
                }
                LinkOutboxDispatch.schedule(a, host.origin());
                a.runOnUiThread(() -> { host.message(a.t("Reintento guardado. Se enviará cuando haya conexión.")); host.refresh(); });
            } catch (Exception e) {
                a.runOnUiThread(() -> host.message(a.t("No se pudo reintentar. Revisa tu sesión.")));
            }
        });
    }

    /** Pide de nuevo el mismo archivo (misma calidad) sin crear otro registro. */
    static void recover(Host host, Item i) {
        DsActivity a = host.act();
        new AlertDialog.Builder(a)
                .setTitle(a.t("Pedir otra vez"))
                .setMessage(a.t("Se recuperará este archivo sin crear otro registro. Si lo habías borrado, autorizas descargarlo de nuevo en este teléfono; tus otros dispositivos necesitarán su propia aprobación."))
                .setNegativeButton(a.t("Cancelar"), null)
                .setPositiveButton(a.t("Pedir otra vez"), (d, w) -> host.io().execute(() -> {
                    try {
                        synchronized (SessionStore.class) {
                            JSONObject session = new SessionStore(a, host.origin()).read();
                            RecoveryOutboxDispatch.box(a, host.origin(), session).add(i.id, "approved");
                        }
                        RecoveryOutboxDispatch.schedule(a, host.origin());
                        a.runOnUiThread(() -> host.message(a.t("Solicitud guardada. Se enviará con cualquier conexión; el archivo respetará Wi-Fi o tu permiso de datos.")));
                        RecoveryOutboxDispatch.flush(a);
                        a.runOnUiThread(host::refresh);
                    } catch (Exception e) {
                        a.runOnUiThread(() -> host.message(a.t("No se pudo guardar la solicitud. Revisa sesión y almacenamiento.")));
                    }
                })).show();
    }

    private static TransferKey keyFor(Host host, Item i) throws Exception {
        DsActivity a = host.act();
        JSONObject session = new SessionStore(a, host.origin()).read();
        File root = LinkOutboxDispatch.root(a, host.origin(), session);
        TransferRecord record = TransferRecord.read(new File(root, i.id + ".properties"));
        return new TransferKey(host.origin(), session.getString("user_id"), session.getString("device_id"), i.id, record.sha256);
    }

    static void allowMobile(Host host, Item i) {
        DsActivity a = host.act();
        String size = i.sizeBytes < 0 ? a.t("tamaño desconocido") : Item.formatSize(i.sizeBytes);
        new AlertDialog.Builder(a)
                .setTitle(a.t("Usar datos móviles esta vez"))
                .setMessage(a.tf("¿Usar datos móviles para “%s” (%s)? El permiso dura hasta completar o cancelar esta descarga. No incluye otros archivos ni dispositivos.", i.title, size))
                .setNegativeButton(a.t("Cancelar"), null)
                .setPositiveButton(a.t("Usar datos móviles"), (d, w) -> host.io().execute(() -> {
                    try {
                        synchronized (SessionStore.class) {
                            JSONObject session = new SessionStore(a, host.origin()).read();
                            File root = LinkOutboxDispatch.root(a, host.origin(), session);
                            if (new File(root, i.id + ".deleted").exists()) throw new Exception();
                            AndroidDownloadPermissions.create(a).authorize(keyFor(host, i));
                        }
                        TransferJobs.wake(a, host.origin(), false);
                        a.runOnUiThread(() -> { host.message(a.t("Listo: este archivo podrá usar datos móviles.")); host.refresh(); });
                    } catch (Exception e) {
                        a.runOnUiThread(() -> host.message(a.t("No se pudo guardar el permiso. Revisa tu sesión.")));
                    }
                })).show();
    }

    static void revokeMobile(Host host, Item i) {
        DsActivity a = host.act();
        host.io().execute(() -> {
            try {
                TransferKey key = keyFor(host, i);
                AndroidDownloadPermissions.create(a).revoke(key);
                TransferRuntime.permissionRemoved(host.origin(), key);
                a.runOnUiThread(() -> { host.message(a.t("Este archivo vuelve a esperar Wi-Fi.")); host.refresh(); });
            } catch (Exception e) {
                a.runOnUiThread(() -> host.message(a.t("No se pudo retirar el permiso.")));
            }
        });
    }

    /** Comparte el video local verificado mediante la hoja nativa del sistema. */
    static void share(Host host, Item i) {
        DsActivity a = host.act();
        if (i.state != ItemState.READY || i.isAudio() || i.outbox) return;
        host.io().execute(() -> {
            try {
                String token = new SessionStore(a, host.origin()).token();
                Intent send = LocalVideoShare.prepare(a, host.origin(), i.id);
                a.runOnUiThread(() -> {
                    if (a.isDestroyed() || a.isFinishing()) return;
                    try {
                        if (!token.equals(new SessionStore(a, host.origin()).token())) throw new SecurityException();
                        // Android propagates ClipData and its temporary read grant to the chosen app.
                        // Dismissing the chooser has no side effects and is not reported as success.
                        a.startActivity(Intent.createChooser(send, a.t("Compartir video")));
                    } catch (Exception e) {
                        host.message(a.t("No se pudo abrir Compartir. Vuelve a intentarlo."));
                    }
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> {
                    if (a.isDestroyed()) return;
                    host.message(a.t("No se puede compartir este video. Comprueba que siga descargado y que tu sesión esté disponible."));
                    host.refresh();
                });
            }
        });
    }

    /** Abre el archivo local con un reproductor externo (VLC primero). */
    static void play(Host host, Item i) {
        DsActivity a = host.act();
        host.io().execute(() -> {
            try {
                String openingToken = new SessionStore(a, host.origin()).token();
                Uri uri = PrivateMediaContentProvider.create(a, host.origin(), i.id);
                if (!openingToken.equals(new SessionStore(a, host.origin()).token())) throw new SecurityException(a.t("La sesión cambió"));
                String mime = a.getContentResolver().getType(uri);
                a.runOnUiThread(() -> {
                    if (a.isDestroyed()) return;
                    Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    view.setClipData(ClipData.newRawUri("TubeGo", uri));
                    List<ResolveInfo> players = a.getPackageManager().queryIntentActivities(view, 0);
                    players.sort(Comparator.comparing(p -> !"org.videolan.vlc".equals(p.activityInfo.packageName)));
                    if (players.isEmpty()) {
                        new AlertDialog.Builder(a).setMessage(a.t("No hay un reproductor compatible. Instala VLC u otro y vuelve a intentarlo."))
                                .setPositiveButton(a.t("Aceptar"), null).show();
                        return;
                    }
                    Runnable launch = null;
                    final ResolveInfo only = players.size() == 1 ? players.get(0) : null;
                    if (only != null) { start(host, i, view, only, openingToken); return; }
                    String[] names = new String[players.size()];
                    for (int n = 0; n < names.length; n++) names[n] = players.get(n).loadLabel(a.getPackageManager()).toString();
                    new AlertDialog.Builder(a).setTitle(a.t("Reproducir con…"))
                            .setItems(names, (dialog, which) -> start(host, i, view, players.get(which), openingToken)).show();
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> host.message(a.t("No se pudo abrir el archivo local: ") + Texts.error(a, e)));
            }
        });
    }

    private static void start(Host host, Item i, Intent view, ResolveInfo player, String token) {
        DsActivity a = host.act();
        Intent explicit = new Intent(view).setComponent(new ComponentName(player.activityInfo.packageName, player.activityInfo.name));
        try {
            a.startActivity(explicit);
            host.io().execute(() -> { try { HistoryOpened.record(a, host.origin(), i.id, token); } catch (Exception ignored) { } });
        } catch (ActivityNotFoundException e) {
            host.message(a.t("El reproductor ya no está disponible."));
        }
    }

    // ---------- Borrar ----------

    static void deleteSheet(Host host, Item i) {
        DsActivity a = host.act();
        Sheet sheet = new Sheet(a);
        LinearLayout body = sheet.body();
        body.addView(Ds.text(a, a.tf("¿Borrar “%s”?", i.title), 23, Ds.INK, 800));
        String size = Item.formatSize(i.sizeBytes);
        Ds.add(body, Ds.text(a, size.isEmpty() ? a.t("Elige hasta dónde.") : a.tf("Libera %s en este teléfono. Elige hasta dónde.", size), 15, Ds.MUTED, 500), 6);

        final String[] scope = {"devices"};
        final RadioButton[] radios = new RadioButton[2];
        final LinearLayout[] cards = new LinearLayout[2];
        String[][] copy = {
                {a.t("Quitar de mis dispositivos"), a.t("Se borra el archivo en todos tus dispositivos. Sigue en tu historial y puedes volver a pedirlo. Los equipos sin conexión lo harán al reconectar.")},
                {a.t("Quitar también del servidor"), a.t("Además libera espacio en el servidor. Para tenerlo otra vez, habrá que volver a solicitarlo.")}};
        String[] values = {"devices", "devices_and_server"};
        Runnable paint = () -> {
            for (int n = 0; n < 2; n++) {
                boolean on = values[n].equals(scope[0]);
                radios[n].setChecked(on);
                cards[n].setBackground(on ? Ds.rounded(a, Ds.SOFT_SELECTED, Ds.BRAND, 20) : Ds.rounded(a, 0xFFFFFFFF, Ds.OUTLINE, 20));
            }
        };
        for (int n = 0; n < 2; n++) {
            final int index = n;
            LinearLayout card = Ds.row(a);
            card.setGravity(Gravity.TOP);
            card.setPadding(Ds.dp(a, 16), Ds.dp(a, 16), Ds.dp(a, 16), Ds.dp(a, 16));
            RadioButton radio = new RadioButton(a);
            radio.setClickable(false);
            radio.setButtonTintList(android.content.res.ColorStateList.valueOf(Ds.BRAND));
            radios[n] = radio;
            cards[n] = card;
            card.addView(radio, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout col = Ds.column(a);
            col.addView(Ds.text(a, copy[n][0], 17, Ds.INK, 800));
            Ds.add(col, Ds.text(a, copy[n][1], 14, Ds.MUTED, 500), 4);
            Ds.addWeighted(card, col, 1, 8, 0);
            card.setClickable(true);
            card.setOnClickListener(v -> { scope[0] = values[index]; paint.run(); });
            Ds.add(body, card, 12);
        }
        paint.run();
        Button del = Ds.button(a, a.t("Borrar"), Ds.Kind.DANGER);
        del.setOnClickListener(v -> { sheet.dismiss(); confirmDelete(host, i, scope[0]); });
        Ds.add(body, del, LinearLayout.LayoutParams.MATCH_PARENT, Ds.bigButtonHeight(a), 0, 16, 0, 0);
        big(a, body, a.t("Cancelar"), Ds.Kind.TEXT, 4, sheet::dismiss);
        sheet.show();
    }

    private static void confirmDelete(Host host, Item i, String scope) {
        DsActivity a = host.act();
        host.io().execute(() -> {
            try {
                JSONObject session = new SessionStore(a, host.origin()).read();
                if (session == null) throw new Exception();
                String token = session.getString("token");
                TransferRuntime.stopOrigin(host.origin());
                synchronized (SessionStore.class) {
                    if (!token.equals(new SessionStore(a, host.origin()).token())) return;
                    DeletionOutboxDispatch.box(a, host.origin(), session).add(i.id, scope);
                    SharedMedia.delete(a,DeletionOutboxDispatch.root(a, host.origin(), session), i.id);
                }
                DeletionOutboxDispatch.schedule(a, host.origin());
                a.runOnUiThread(() -> { host.message(a.t("Borrado. Los dispositivos sin conexión lo quitarán al reconectar.")); host.refresh(); });
                DeletionOutboxDispatch.flush(a);
            } catch (Exception e) {
                a.runOnUiThread(() -> host.message(a.t("No se completó la limpieza local. Los borrados pendientes se reintentarán al conectar.")));
            }
        });
    }

    // ---------- Liberar espacio ----------

    /** Lista lo guardado en el teléfono, de mayor a menor tamaño, para decidir qué borrar. */
    static void freeSpace(Host host, List<Item> all) {
        DsActivity a = host.act();
        List<Item> saved = new ArrayList<>();
        for (Item it : all) if (it.state == ItemState.READY) saved.add(it);
        saved.sort(Comparator.comparingLong((Item it) -> it.sizeBytes).reversed());
        Sheet sheet = new Sheet(a);
        sheet.title(a.t("Liberar espacio"), a.t("Cerrar"));
        LinearLayout body = sheet.body();
        Ds.add(body, Ds.text(a, a.t("Estos archivos ocupan espacio en tu teléfono. Los más grandes, primero."), 15, Ds.MUTED, 500), 4);
        if (saved.isEmpty()) {
            Ds.add(body, Ds.text(a, a.t("No hay archivos guardados en este teléfono."), 16, Ds.INK, 600), 16);
        }
        for (Item it : saved) {
            LinearLayout row = Ds.row(a);
            LinearLayout col = Ds.column(a);
            col.addView(Ds.text(a, it.title, 16, Ds.INK, 700));
            col.addView(Ds.text(a, meta(a, it), 14, Ds.MUTED, 500));
            Ds.addWeighted(row, col, 1, 0, 8);
            Button b = Ds.button(a, a.t("Borrar…"), Ds.Kind.DANGER_TEXT);
            b.setOnClickListener(v -> { sheet.dismiss(); deleteSheet(host, it); });
            row.addView(b, new LinearLayout.LayoutParams(-2, Ds.dp(a, 48)));
            Ds.add(body, row, 10);
        }
        sheet.show();
    }
}
