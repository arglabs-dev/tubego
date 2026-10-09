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
