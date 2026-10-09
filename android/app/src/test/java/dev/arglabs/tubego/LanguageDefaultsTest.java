package dev.arglabs.tubego;
import org.junit.Test;
import static org.junit.Assert.*;
public final class LanguageDefaultsTest {
 @Test public void spanishSystemDefaultOtherwiseEnglish(){assertEquals("es",LanguageDefaults.system("es"));assertEquals("es",LanguageDefaults.system("es-MX"));assertEquals("en",LanguageDefaults.system("fr"));assertEquals("en",LanguageDefaults.system(null));assertFalse(LanguageDefaults.valid("fr"));}
 @Test public void keysStableAcrossLocalesAndUnique(){assertEquals(TextKey.of("Idioma"),TextKey.of("Idioma"));assertNotEquals(TextKey.of("Idioma"),TextKey.of("Idioma "));assertTrue(TextKey.of("¿Sí?").matches("ui_[a-f0-9]{12}"));}
}
