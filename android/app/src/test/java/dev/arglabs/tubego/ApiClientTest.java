package dev.arglabs.tubego;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ApiClientTest {
    @Test public void normalizesHttpsOrigin() {
        assertEquals("https://example.com:8443", new ApiClient(" https://example.com:8443/ ").getBaseUrl());
    }

    @Test public void refusesCleartextAndAmbiguousOrigins() {
        String[] invalid = {"http://example.com", "https://user:secret@example.com", "https://example.com/api",
            "https://example.com?token=secret", "https://example.com#fragment", "not a URL"};
        for (String value : invalid) {
            assertThrows(IllegalArgumentException.class, () -> new ApiClient(value));
        }
    }
}
