package dev.arglabs.tubego;
import org.junit.Test;
import static org.junit.Assert.*;
public final class MediaFailureTextTest {
    @Test public void apiSourceFailuresHaveDistinctActionableReasons() {
        assertTrue(MediaFailureText.source("invalid_url").contains("HTTP"));
        assertTrue(MediaFailureText.source("unsupported").contains("compatible"));
        assertEquals(MediaFailureText.source("private"), MediaFailureText.source("authentication_required"));
        assertTrue(MediaFailureText.source("authentication_required").contains("cookies"));
        assertTrue(MediaFailureText.source("unavailable").contains("eliminado"));
        assertTrue(MediaFailureText.source("source_restricted").contains("restringe"));
        assertTrue(MediaFailureText.source("format_unavailable").contains("otra calidad"));
        assertTrue(MediaFailureText.source("temporary_failure").contains("más tarde"));
    }
    @Test public void unknownDiagnosticsNeverLeakIntoUserMessage() {
        String unsafe="extractor token=secret /srv/private/file";
        assertFalse(MediaFailureText.known(unsafe));
        assertFalse(MediaFailureText.source(unsafe).contains("secret"));
        assertFalse(MediaFailureText.known(null));
    }
    @Test public void qualityNoticesDoNotPromiseRequestedResolution() {
        assertTrue(QualityNoticeText.source("lower_quality_available").contains("inferior"));
        assertTrue(QualityNoticeText.source("quality_unknown").contains("no se ha confirmado"));
        assertEquals("",QualityNoticeText.source(null));
        assertEquals("",QualityNoticeText.source("unknown_new_code"));
    }
}
