package dev.arglabs.tubego;
import org.junit.Test;
import static org.junit.Assert.*;
public final class SharedUrlTest{
 @Test public void extractsLinkFromSharedDescription(){assertEquals("https://www.youtube.com/watch?v=abc",SharedUrl.parse("Un video educativo\nhttps://www.youtube.com/watch?v=abc"));}
 @Test public void rejectsAmbiguousMissingAndCredentials(){for(String value:new String[]{"hola","https://one.example/a https://two.example/b","ftp://example.com/a","https://user:secret@example.com/v","https://example.com/%ZZ"})assertThrows(IllegalArgumentException.class,()->SharedUrl.parse(value));}
 @Test public void preservesQueryAndTrimsSharedPunctuation(){assertEquals("https://example.com/video?token=abc&v=xyz",SharedUrl.parse("Mira: https://example.com/video?token=abc&v=xyz."));}
}
