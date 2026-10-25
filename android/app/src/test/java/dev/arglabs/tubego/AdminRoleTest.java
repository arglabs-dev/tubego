package dev.arglabs.tubego;
import org.junit.Test;import static org.junit.Assert.*;
public final class AdminRoleTest {
 @Test public void onlyApprovedAdminWithSessionCanSeeProtectedNavigation(){assertTrue(AdminRole.allowed("approved","admin","session"));for(String status:new String[]{"pending_verification","pending_approval","blocked","rejected","deleted",""})assertFalse(AdminRole.allowed(status,"admin","session"));assertFalse(AdminRole.allowed("approved","user","session"));assertFalse(AdminRole.allowed("approved","admin",null));assertFalse(AdminRole.allowed("approved","admin",""));}
}
