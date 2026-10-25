package dev.arglabs.tubego;
public final class LanguageDefaults {
 public static String system(String language){return language!=null&&language.toLowerCase(java.util.Locale.ROOT).startsWith("es")?"es":"en";}
 public static boolean valid(String language){return "es".equals(language)||"en".equals(language);}
}
