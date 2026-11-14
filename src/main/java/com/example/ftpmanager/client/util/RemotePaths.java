package com.example.ftpmanager.client.util;
public final class RemotePaths {
    private RemotePaths() {}
    public static String child(String parent,String name) { return (parent.equals("/")?"/":parent+"/")+name; }
    public static String parent(String path) { int i=path.lastIndexOf('/'); return i<=0?"/":path.substring(0,i); }
    public static String name(String name) { if(name.isBlank()||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\")||name.contains("\r")||name.contains("\n")) throw new IllegalArgumentException("Invalid filename"); return name; }
}
