package dev.arglabs.tubego;
/** Presentation policy only. The API remains the authority for every protected operation. */
public final class AdminRole {
 public static boolean allowed(String status,String role,String token){return "approved".equals(status)&&"admin".equals(role)&&token!=null&&!token.isEmpty();}
}
