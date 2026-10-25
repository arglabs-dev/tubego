package dev.arglabs.tubego;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class AlertPolicyTest {
 @Test public void onlyActionableKindsAreAccepted(){
  for(String kind:new String[]{"resource_available","task_updated","completed","transfers","unknown"}){assertFalse(AlertPolicy.accepts(kind,true));assertFalse(AlertPolicy.accepts(kind,false));}
  assertTrue(AlertPolicy.accepts("download_failed",false));assertTrue(AlertPolicy.accepts("device_storage",false));
  for(String kind:new String[]{"registration_pending","server_storage_paused","admin_maintenance"}){assertFalse(AlertPolicy.accepts(kind,false));assertTrue(AlertPolicy.accepts(kind,true));}
 }
 @Test public void durableIdentityDedupDoesNotConsumeRejectedAdminEvents(){
  Set<String> seen=new HashSet<>();AlertPolicy.Store store=new AlertPolicy.Store(){public boolean seen(String id){return seen.contains(id);}public void mark(String id){seen.add(id);}};
  assertFalse(AlertPolicy.remember(store,"server:1","registration_pending",false));assertTrue(seen.isEmpty());
  assertTrue(AlertPolicy.remember(store,"server:1","registration_pending",true));assertFalse(AlertPolicy.remember(store,"server:1","registration_pending",true));
  assertTrue(AlertPolicy.remember(store,"local:2:incident1","download_failed",false));assertFalse(AlertPolicy.remember(store,"local:2:incident1","download_failed",false));
  assertTrue(AlertPolicy.remember(store,"local:2:incident2","download_failed",false));
 }
}
