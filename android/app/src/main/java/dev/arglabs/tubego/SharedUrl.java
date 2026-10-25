package dev.arglabs.tubego;
import java.net.URI;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Exactly one web URL; sharing descriptive text is supported, never batches. */
public final class SharedUrl {
    private static final Pattern URL=Pattern.compile("https?://[^\\s<>]+",Pattern.CASE_INSENSITIVE);
    public static String parse(String text) {
        if(text==null || text.length()>8192) throw new IllegalArgumentException("Comparte un enlace HTTP o HTTPS.");
        Matcher matcher=URL.matcher(text);String candidate=null;int count=0;
        while(matcher.find()) {count++;candidate=matcher.group();}
        if(count!=1) throw new IllegalArgumentException("Agrega un solo enlace por solicitud.");
        while(!candidate.isEmpty() && ".,;\"' )]".indexOf(candidate.charAt(candidate.length()-1))>=0) candidate=candidate.substring(0,candidate.length()-1);
        try {
            URI uri=new URI(candidate);
            if(candidate.length()>4096 || uri.getHost()==null || uri.getUserInfo()!=null || uri.getPort()<-1 || uri.getPort()>65535) throw new Exception();
            for(char ch:candidate.toCharArray()) if(Character.isISOControl(ch)) throw new Exception();
            return candidate;
        } catch(Exception e) {throw new IllegalArgumentException("El enlace no es válido o contiene credenciales.");}
    }
}
