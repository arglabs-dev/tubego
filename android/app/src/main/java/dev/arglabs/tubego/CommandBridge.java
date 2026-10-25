package dev.arglabs.tubego;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Transitional legacy UI records; the global command sequence is authoritative. */
public final class CommandBridge {
 private CommandBridge(){}
 public static boolean production(File dir){return Arrays.asList("outbox","recovery-outbox","deletion-outbox","server-cleanup-outbox").contains(dir.getName());}
 public static String quote(String value){StringBuilder out=new StringBuilder("\"");for(char c:value.toCharArray())switch(c){case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;default:if(c<32)out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}return out.append('"').toString();}
 public static String payload(String kind,Properties p){
  if(kind.equals("submit"))return "{\"url\":"+quote(p.getProperty("url"))+",\"selection\":"+quote(p.getProperty("selection"))+"}";
  if(kind.equals("server_cleanup"))return "{\"scope\":"+quote(p.getProperty("scope"))+",\"confirmed\":true}";
  return "{\"resource_id\":"+quote(p.getProperty("resource"))+","+(kind.equals("recover")?"\"approve_redownload\":"+p.getProperty("scope").equals("approved"):"\"scope\":"+quote(p.getProperty("scope")))+"}";
 }
 public static void add(File dir,String id,String kind,Properties p)throws IOException{
  if(production(dir))new CommandQueue(dir.getParentFile()).importIntent(id,kind,payload(kind,p));
 }
 public static void finishLegacy(File root,CommandQueue.Entry entry,String state,String error,String resource)throws IOException {
  String sub=entry.kind.equals("submit")?"outbox":entry.kind.equals("recover")?"recovery-outbox":entry.kind.equals("delete")?"deletion-outbox":entry.kind.equals("server_cleanup")?"server-cleanup-outbox":null;
  if(sub==null)return;File file=new File(new File(root,sub),entry.id+".properties");if(!file.isFile())return;
  Properties p=new Properties();try(InputStream in=new FileInputStream(file)){p.load(in);}p.setProperty("state",state);p.setProperty("error",error);p.setProperty("resource_id",resource);
  File temp=new File(file.getParentFile(),entry.id+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){p.store(out,null);out.getFD().sync();}Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
 }
}
