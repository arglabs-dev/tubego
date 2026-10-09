package dev.arglabs.tubego;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class DeviceStoragePolicyTest {
 @Test public void strictThresholdAndKnownRemainingBytes(){
  assertTrue(DeviceStoragePolicy.evaluate(1000,99,0,10).paused);
  assertFalse(DeviceStoragePolicy.evaluate(1000,100,0,10).paused);
  assertFalse(DeviceStoragePolicy.evaluate(1000,250,150,10).paused);
  assertTrue(DeviceStoragePolicy.evaluate(1000,250,151,10).paused);
  assertEquals("insufficient_space",DeviceStoragePolicy.evaluate(1000,250,151,10).reason);
  assertEquals(101,DeviceStoragePolicy.evaluate(1001,101,0,10).reserve);
 }
 @Test public void hugeVolumesAndUnknownSampleFailClosed(){
  assertFalse(DeviceStoragePolicy.evaluate(Long.MAX_VALUE,Long.MAX_VALUE,0,90).paused);
  assertTrue(DeviceStoragePolicy.evaluate(0,0,0,10).paused);
  assertTrue(DeviceStoragePolicy.evaluate(100,-1,0,10).paused);
  for(int invalid:new int[]{0,91,-1})try{DeviceStoragePolicy.validPercent(invalid);fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void thresholdPersistsAndDoesNotLeakAcrossAccountDeviceOrServer(){
  Map<String,Integer> values=new HashMap<>();DeviceStoragePreferences.Store store=new DeviceStoragePreferences.Store(){public int get(String key,int fallback){return values.getOrDefault(key,fallback);}public void put(String key,int value){values.put(key,value);}};
  String user=UUID.randomUUID().toString(),device=UUID.randomUUID().toString();
  DeviceStoragePreferences a=new DeviceStoragePreferences(store,"https://one.example",user,device);assertEquals(10,a.threshold());a.threshold(25);
  assertEquals(25,new DeviceStoragePreferences(store,"https://one.example",user,device).threshold());
  assertEquals(10,new DeviceStoragePreferences(store,"https://two.example",user,device).threshold());
  assertEquals(10,new DeviceStoragePreferences(store,"https://one.example",UUID.randomUUID().toString(),device).threshold());
  assertEquals(10,new DeviceStoragePreferences(store,"https://one.example",user,UUID.randomUUID().toString()).threshold());
  try{a.threshold(0);fail();}catch(IllegalArgumentException expected){}assertEquals(25,a.threshold());
 }
}
