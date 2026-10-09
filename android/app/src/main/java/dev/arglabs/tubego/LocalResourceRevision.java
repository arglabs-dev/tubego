package dev.arglabs.tubego;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
/** Per-resource server mutation fence, stored with the account/device library. */
public final class LocalResourceRevision {
 private LocalResourceRevision(){}
 public static long current(File root,String id)throws IOException{File file=new File(root,UUID.fromString(id)+".revision");if(!file.exists())return 0;if(Files.isSymbolicLink(file.toPath())||file.length()>32)throw new IOException("Invalid resource revision");try{return Long.parseLong(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.US_ASCII));}catch(NumberFormatException invalid){throw new IOException("Invalid resource revision",invalid);}}
 public static boolean accept(File root,String id,long revision)throws IOException{long previous=current(root,id);if(revision<previous)return false;if(revision>previous){if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Cannot persist revision");File file=new File(root,UUID.fromString(id)+".revision"),temp=new File(root,id+".revision.tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(Long.toString(revision).getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.getFD().sync();}Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}return true;}
}
