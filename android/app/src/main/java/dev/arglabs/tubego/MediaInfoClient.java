package dev.arglabs.tubego;

import org.json.JSONObject;

/** Network metadata helper; call on a background executor, never the UI thread. */
public final class MediaInfoClient {
    @FunctionalInterface
    public interface Transport {
        JSONObject request(String method, String path, JSONObject body, String token) throws Exception;
    }

    private final Transport transport;

    /** Pass ApiClient::request after account transport integration. */
    public MediaInfoClient(Transport transport) { this.transport = transport; }

    public Info analyze(String url, String token) throws Exception {
        JSONObject response = transport.request("POST", "/media/analyze",
                new JSONObject().put("url", url), token);
        return Info.fromJson(response);
    }

    public static final class Info {
        public final String url, title, sourceId, extractor;
        public final Double durationSeconds;

        private Info(String url, String title, String sourceId, String extractor, Double duration) {
            this.url = url;
            this.title = title;
            this.sourceId = sourceId;
            this.extractor = extractor;
            this.durationSeconds = duration;
        }

        public static Info fromJson(JSONObject json) throws Exception {
            Double duration = json.isNull("duration_seconds") ? null : json.getDouble("duration_seconds");
            return new Info(json.getString("url"), optional(json, "title"),
                    optional(json, "source_id"), optional(json, "extractor"), duration);
        }

        private static String optional(JSONObject json, String key) {
            return json.isNull(key) ? null : json.optString(key, null);
        }
    }
}
