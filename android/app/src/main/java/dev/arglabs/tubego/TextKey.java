package dev.arglabs.tubego;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
public final class TextKey {
 public static String of(String source){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));StringBuilder key=new StringBuilder("ui_");for(int i=0;i<6;i++)key.append(String.format(java.util.Locale.ROOT,"%02x",bytes[i]&255));return key.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
