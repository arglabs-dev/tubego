package dev.arglabs.tubego;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Lee la biblioteca de la cuenta: historial guardado, archivos del teléfono y, con conexión, la página
 * más reciente del servidor. Usa las mismas fuentes y reglas que la pantalla anterior; no cambia datos.
 */
final class LibraryRepo {
    private LibraryRepo() {}

    static final class Result {
        final List<Item> items = new ArrayList<>();
        final List<Item> outbox = new ArrayList<>();
        boolean online;
        String nextCursor = "";
        String userId = "", deviceId = "";
    }

    /** La cuenta no está lista (sin sesión aprobada). */
    static final class NoSession extends Exception {}

    static Result load(Context ctx, String origin, boolean fetch, String cursor, DownloadPolicy.NetworkState network) throws Exception {
        final JSONObject session;
        final String token;
        final File root;
        final Map<String, JSONObject> rows = new HashMap<>();
        synchronized (SessionStore.class) {
            session = new SessionStore(ctx, origin).read();
            if (session == null || !"approved".equals(session.optString("status"))) throw new NoSession();
            token = session.getString("token");
            root = LocalLibraryStorage.root(ctx, origin, session.getString("user_id"), session.getString("device_id"));
            for (Map.Entry<String, String> e : new HistoryCache(root).read().entrySet()) {
                try { rows.put(e.getKey(), new JSONObject(e.getValue())); } catch (Exception ignored) { }
            }
            File[] manifests = root.listFiles((d, n) -> n.endsWith(".properties"));
            if (manifests != null) {
                for (File f : manifests) {
                    try {
                        TransferRecord r = OfflineMediaAccess.record(root, f.getName().replace(".properties", ""));
                        if (!rows.containsKey(r.id)) {
                            rows.put(r.id, new JSONObject().put("id", r.id).put("title", r.title)
                                    .put("media_format", r.mediaFormat).put("created_at", r.createdAt));
                        }
                    } catch (Exception ignored) { }
                }
            }
        }

        Result result = new Result();
        result.userId = session.getString("user_id");
        result.deviceId = session.getString("device_id");

        if (fetch) {
            try {
                HistoryOpened.flush(ctx, origin, token, root);
                String path = "/resources?order=newest&limit=50&cursor=" + java.net.URLEncoder.encode(cursor == null ? "" : cursor, "UTF-8");
                JSONObject page = new ApiClient(origin).request("GET", path, null, token);
                JSONArray items = page.getJSONArray("items");
                Map<String, String> cache = new HashMap<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject row = items.getJSONObject(i);
                    JSONObject local = rows.get(row.getString("id"));
                    if (local != null && local.optString("last_opened_at", "").compareTo(row.optString("last_opened_at", "")) > 0) {
                        row.put("last_opened_at", local.getString("last_opened_at"));
                    }
                    rows.put(row.getString("id"), row);
                    cache.put(row.getString("id"), row.toString());
                }
                synchronized (SessionStore.class) {
                    String current = new SessionStore(ctx, origin).token();
                    if (current == null || !current.equals(token)) throw new NoSession();
                    new HistoryCache(root).merge(cache);
                }
                result.nextCursor = page.isNull("next_cursor") ? "" : page.getString("next_cursor");
                result.online = true;
            } catch (NoSession e) {
                throw e;
            } catch (Exception offline) {
                result.online = false;
            }
        }

        DownloadPermissions permissions = AndroidDownloadPermissions.create(ctx);
        List<JSONObject> ordered = new ArrayList<>(rows.values());
        ordered.sort(Comparator.comparing((JSONObject r) -> r.optString("created_at")).reversed()
                .thenComparing(r -> r.optString("id")));
        for (JSONObject row : ordered) {
            result.items.add(toItem(row, root, origin, result.userId, result.deviceId, permissions, network));
        }

        try {
            for (LinkOutbox.Entry entry : LinkOutboxDispatch.box(ctx, origin, session).entries()) {
                if ("submitted".equals(entry.state)) continue;
                Item item = new Item();
                item.outbox = true;
                item.outboxId = entry.id;
                item.outboxState = entry.state;
                item.sourceUrl = entry.url;
                item.selection = entry.selection;
                item.mediaFormat = "audio".equals(entry.selection) ? "audio" : "video";
                item.title = hostOf(entry.url) + shortPath(entry.url);
                item.host = hostOf(entry.url);
                item.failureCode = entry.error == null ? "" : entry.error;
                item.state = "error".equals(entry.state) ? ItemState.FAILED : ItemState.WAITING_OFFLINE;
                result.outbox.add(item);
            }
        } catch (Exception ignored) { /* La cola local no se pudo leer: se omite. */ }
        return result;
    }

    private static Item toItem(JSONObject row, File root, String origin, String userId, String deviceId,
                               DownloadPermissions permissions, DownloadPolicy.NetworkState network) {
        Item item = new Item();
        item.id = row.optString("id");
        item.title = row.isNull("title") ? "" : row.optString("title", "");
        item.hasThumbnail = row.optBoolean("has_thumbnail",false);
        item.mediaFormat = row.optString("media_format", "video");
        item.selection = "audio".equals(item.mediaFormat) ? "audio" : row.optString("quality", "best");
        item.sourceUrl = row.optString("source_url", "");
        item.host = hostOf(item.sourceUrl);
        item.createdAt = row.optString("created_at", "");
        item.lastOpenedAt = row.isNull("last_opened_at") ? "" : row.optString("last_opened_at", "");
        item.serverAvailable = row.optBoolean("server_available", false);
        if (!row.isNull("duration_seconds")) item.durationSeconds = row.optDouble("duration_seconds", -1);
        if (!row.isNull("size_bytes")) item.sizeBytes = row.optLong("size_bytes", -1);

        TransferRecord record = null;
        try { record = OfflineMediaAccess.record(root, item.id); } catch (Exception ignored) { }
        boolean downloaded = false;
        try { downloaded = record != null && OfflineMediaAccess.available(record); } catch (Exception ignored) { }
        item.downloaded = downloaded;

        JSONObject task = row.optJSONObject("latest_task");
        String taskState = task == null ? "" : task.optString("status");
        String localState = record == null ? "" : record.state;
        if (task != null) {
            item.taskId = task.optString("id", "");
            item.taskState = taskState;
            if (!task.isNull("progress")) item.serverProgress = task.optDouble("progress", -1);
            item.qualityNoticeCode = task.isNull("quality_notice") ? "" : task.optString("quality_notice", "");
            if (!task.isNull("error_message")) item.failureCode = task.optString("error_code", "");
        }
        if (record != null) {
            item.localState = localState;
            item.pauseReason = record.pauseReason == null ? "" : record.pauseReason;
            if (!record.failureCode.isEmpty()) item.failureCode = record.failureCode;
            item.sizeBytes = record.size;
            item.offsetBytes = record.offset();
            try {
                item.mobileAuthorized = permissions.authorized(new TransferKey(origin, userId, deviceId, item.id, record.sha256));
            } catch (Exception ignored) { }
            if (item.title.isEmpty()) item.title = record.title;
        }
        if (item.title.isEmpty()) item.title = item.host.isEmpty() ? "Archivo" : item.host;

        boolean pending = "queued".equals(taskState) || "running".equals(taskState) || "paused".equals(taskState)
                || "pending".equals(localState) || "downloading".equals(localState) || "paused".equals(localState) || "retry_wait".equals(localState);
        boolean error = "failed".equals(taskState) || "failed".equals(localState);
        JSONObject delivery = row.optJSONObject("device_delivery");
        boolean unavailable = !downloaded && ((row.has("server_deleted_at") && !row.isNull("server_deleted_at"))
                || "deleted".equals(localState) || "unavailable".equals(localState)
                || (delivery != null && !delivery.isNull("deleted_at"))
                || (!pending && !error && !item.serverAvailable));

        DownloadPolicy.Decision decision = DownloadPolicy.mediaDecision(network == null ? DownloadPolicy.NetworkState.offline() : network, item.mobileAuthorized);
        item.state = ItemState.of(downloaded, unavailable, taskState, localState, item.pauseReason, decision);
        return item;
    }

    static String hostOf(String url) {
        try {
            String host = android.net.Uri.parse(url).getHost();
            return host == null ? "" : host.replaceFirst("^www\\.", "").toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private static String shortPath(String url) {
        try {
            String path = android.net.Uri.parse(url).getPath();
            if (path == null || path.length() < 2) return "";
            return path.length() > 28 ? path.substring(0, 27) + "…" : path;
        } catch (Exception e) {
            return "";
        }
    }
}
