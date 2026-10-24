package dev.arglabs.tubego;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/** Portable basenames: Unicode normalization, no paths, controls or reserved device names. */
public final class MediaFileName {
    private MediaFileName() {}
    public static String stem(String title) {
        String value=Normalizer.normalize(title==null?"":title,Normalizer.Form.NFKC)
            .replaceAll("[\\p{Cntrl}\\p{Cf}<>:\"/\\\\|?*]", " ").replaceAll("\\s+", " ").trim()
            .replaceAll("^[. ]+|[. ]+$", "");
        if(value.isEmpty())value="Video";
        if(value.split("\\.",2)[0].toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))value="Video "+value;
        StringBuilder bounded=new StringBuilder();int bytes=0;
        for(int offset=0;offset<value.length();) {
            int cp=value.codePointAt(offset);String c=new String(Character.toChars(cp));int n=c.getBytes(StandardCharsets.UTF_8).length;
            if(bytes+n>160)break;bounded.append(c);bytes+=n;offset+=Character.charCount(cp);
        }
        return bounded.toString().replaceAll("[. ]+$", "");
    }
    public static String display(String title,boolean audio) {return stem(title)+(audio?".mp3":".mp4");}
    static boolean validStored(String value,String id) {
        return value!=null && value.startsWith(id+"--") && value.getBytes(StandardCharsets.UTF_8).length<=240
            && !java.util.regex.Pattern.compile("[\\p{Cntrl}<>:\"/\\\\|?*]").matcher(value).find() && (value.endsWith(".mp4")||value.endsWith(".mp3"));
    }
}
