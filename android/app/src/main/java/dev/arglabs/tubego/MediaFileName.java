package dev.arglabs.tubego;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/** Portable ASCII basenames with underscores, fixed extensions and no reserved device names. */
public final class MediaFileName {
    private MediaFileName() {}
    public static String stem(String title) {
        String value=Normalizer.normalize(title==null?"":title,Normalizer.Form.NFKD)
            .replaceAll("\\p{M}+", "").replaceAll("[^A-Za-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        if(value.isEmpty())value="Video";
        if(value.toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))value="Video_"+value;
        return value.substring(0,Math.min(160,value.length())).replaceAll("_+$", "");
    }
    public static String display(String title,boolean audio) {return numbered(title,audio,1);}
    static String numbered(String title,boolean audio,int number) {
        String base=stem(title),suffix=number>1?"_"+number:"";
        return base.substring(0,Math.min(base.length(),160-suffix.length())).replaceAll("_+$", "")+suffix+(audio?".mp3":".mp4");
    }
    static boolean normalized(String name){return name!=null&&name.matches("[A-Za-z0-9]+(?:_[A-Za-z0-9]+)*\\.(mp4|mp3)");}
    static boolean validStored(String value,String id) {
        return value!=null && value.startsWith(id+"--") && value.getBytes(StandardCharsets.UTF_8).length<=240
            && !java.util.regex.Pattern.compile("[\\p{Cntrl}<>:\"/\\\\|?*]").matcher(value).find() && (value.endsWith(".mp4")||value.endsWith(".mp3"));
    }
}
