package com.example.ftpmanager.server.command;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Listing {
    private Listing() {}
    public static String timestamp(Path p) throws IOException { return DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(Files.getLastModifiedTime(p).toInstant()); }
    public static String facts(Path p, String name) throws IOException { var a = Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); return "type="+(a.isDirectory()?"dir":"file")+";size="+a.size()+";modify="+timestamp(p)+"; "+name; }
    public static String unix(Path p) throws IOException {
        var a = Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        return (a.isDirectory()?"drwxr-xr-x":"-rw-r--r--")+" 1 ftp ftp "+a.size()+" "+DateTimeFormatter.ofPattern("MMM dd HH:mm",Locale.US).withZone(ZoneId.systemDefault()).format(a.lastModifiedTime().toInstant())+" "+p.getFileName();
    }
}
