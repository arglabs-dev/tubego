package dev.arglabs.tubego;
import org.junit.*;import org.junit.rules.TemporaryFolder;import static org.junit.Assert.*;import java.util.*;import java.io.*;
public final class HistoryCacheTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void offlineCachePersistsWithNoMediaAndIsBounded() throws Exception {
  HistoryCache cache=new HistoryCache(temp.getRoot());Map<String,String> rows=new LinkedHashMap<>();for(int i=0;i<2003;i++)rows.put(new UUID(0,i+1).toString(),"metadata-"+i);cache.merge(rows);
  Map<String,String> read=new HistoryCache(temp.getRoot()).read();assertEquals(2000,read.size());assertFalse(read.containsKey(new UUID(0,1).toString()));assertEquals("metadata-2002",read.get(new UUID(0,2003).toString()));
 }
 @Test public void cacheIsPrivateToNamespaceAndUpdatesOneResourceWithoutDuplication() throws Exception {
  File first=temp.newFolder("first"),second=temp.newFolder("second");String id=UUID.randomUUID().toString();HistoryCache cache=new HistoryCache(first);cache.merge(Collections.singletonMap(id,"title-original"));cache.merge(Collections.singletonMap(id,"title-new"));assertEquals(Collections.singletonMap(id,"title-new"),cache.read());assertTrue(new HistoryCache(second).read().isEmpty());
 }
}
