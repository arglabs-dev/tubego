package dev.arglabs.tubego;

import org.json.JSONObject;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Transport base. No auth or resource operations exist in this foundation. */
public final class ApiClient {
    private final String baseUrl;

    public ApiClient(String url) {
        URI uri = URI.create(url.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Usa una URL HTTPS del servidor, sin ruta ni credenciales.");
        }
        baseUrl = url.trim().replaceAll("/+$", "");
    }

    public String getBaseUrl() { return baseUrl; }

    public JSONObject request(String method, String path, JSONObject body, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(baseUrl + "/api/v1" + path).toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (var stream = connection.getOutputStream()) { stream.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status + ": no se pudo completar la operación");
            try (var stream = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > 65536) throw new IOException("Respuesta demasiado grande");
                    bytes.write(buffer, 0, count);
                }
                String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                return text.startsWith("[") ? new JSONObject().put("items", new org.json.JSONArray(text)) : new JSONObject(text);
            }
        } finally { connection.disconnect(); }
    }

    public JSONObject health() throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
            URI.create(baseUrl + "/api/v1/health").toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("El servidor respondió HTTP " + status);
            try (var stream = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > 65536) throw new IOException("Respuesta demasiado grande");
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
