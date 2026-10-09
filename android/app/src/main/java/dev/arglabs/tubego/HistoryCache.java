package dev.arglabs.tubego;
import java.io.*;import java.nio.file.*;import java.util.*;
/** A bounded offline copy; the server retains the complete permanent history. */
public final class HistoryCache {
 public static final int LIMIT=2000;
 private final File root;
 public HistoryCache(File root){this.root=root;}
 public Map<String,String> read() throws IOException {
  Properties p=load();Map<String,String> rows=new HashMap<>();for(String key:p.stringPropertyNames())if(key.startsWith("entry."))rows.put(key.substring(6),p.getProperty(key));return rows;
 }
 private Properties load() throws IOException {Properties p=new Properties();File f=new File(root,"history.properties");if(f.exists())try(FileInputStream in=new FileInputStream(f)){p.load(in);}return p;}
 public void merge(Map<String,String> rows) throws IOException {
  Properties p=load();long clock=Long.parseLong(p.getProperty("clock","0"));
  for(Map.Entry<String,String> row:rows.entrySet()){String id=UUID.fromString(row.getKey()).toString();p.setProperty("entry."+id,row.getValue());p.setProperty("sequence."+id,Long.toString(++clock));}
  List<String> ids=new ArrayList<>();for(String key:p.stringPropertyNames())if(key.startsWith("entry."))ids.add(key.substring(6));
  ids.sort(Comparator.comparingLong(id->Long.parseLong(p.getProperty("sequence."+id,"0"))));
  for(int i=0;i<ids.size()-LIMIT;i++){p.remove("entry."+ids.get(i));p.remove("sequence."+ids.get(i));}
  p.setProperty("clock",Long.toString(clock));if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Biblioteca no disponible");
  File tmp=new File(root,"history.properties.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){p.store(out,"Private offline history");out.getFD().sync();}
  Files.move(tmp.toPath(),new File(root,"history.properties").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
 }
}
