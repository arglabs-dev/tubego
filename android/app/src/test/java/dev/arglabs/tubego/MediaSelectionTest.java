package dev.arglabs.tubego;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MediaSelectionTest {
    @Test public void choicesWorkOffline() {
        assertEquals("audio", MediaSelection.resolve(false, "audio", null));
        assertEquals("480", MediaSelection.resolve(false, "audio", "480"));
        assertEquals(3, MediaSelection.index("best"));
        assertThrows(IllegalArgumentException.class, () -> MediaSelection.resolve(true, "720", null));
        assertThrows(IllegalArgumentException.class, () -> MediaSelection.resolve(false, "4k", null));
    }
}
