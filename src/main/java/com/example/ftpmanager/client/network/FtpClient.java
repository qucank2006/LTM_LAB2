package com.example.ftpmanager.client.network;

import com.example.ftpmanager.common.protocol.FtpWire;
import com.example.ftpmanager.common.protocol.FtpWire.Reply;
import com.example.ftpmanager.client.model.RemoteEntry;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;

/** All control transactions serialized by caller and synchronized API. Cancel only closes sockets. */
public final class FtpClient implements AutoCloseable {
    private volatile Socket control,data;
    private volatile ServerSocket activeListener;
    private volatile boolean cancelled;
    private final Consumer<String> log;
    private final int timeout=60000;
    private boolean passive=true, ascii=false;
    private final Object writeLock=new Object();
    public FtpClient(Consumer<String> log) { this.log=log; }
    public boolean connected() { Socket s=control; return s!=null && !s.isClosed(); }
    public synchronized void mode(boolean passive, boolean ascii) { this.passive=passive; this.ascii=ascii; }
    public synchronized void connect(String host,int port,String user,String password) throws IOException {
        close(); cancelled=false; Socket s=new Socket(); control=s;
        try {
            s.connect(new InetSocketAddress(host,port),timeout); s.setSoTimeout(timeout); s.setKeepAlive(true); read().expect(220);
            Reply r=command("USER "+user); if(r.code()==331) command("PASS "+password).expect(230); else r.expect(230);
            Reply utf=command("OPTS UTF8 ON"); if(utf.code()>=400) log.accept("UTF8 negotiation rejected; UTF-8 remains selected locally");
            command("TYPE I").expect(200);
        } catch(IOException e) { close(); throw e; }
    }
    private Reply read() throws IOException { Socket s=control; if(s==null) throw new IOException("Not connected"); Reply r=FtpWire.read(s.getInputStream()); r.lines().forEach(x->log.accept("< "+x)); return r; }
    private Reply command(String line) throws IOException {
        Socket s=control; if(s==null) throw new IOException("Not connected"); log.accept("> "+(line.startsWith("PASS ")?"PASS [redacted]":line)); synchronized(writeLock) { FtpWire.write(s.getOutputStream(),line); } return read();
    }
    public synchronized Reply execute(String cmd,int... codes) throws IOException { Reply r=command(cmd); r.expect(codes); return r; }
    public synchronized String pwd() throws IOException {
        String line=execute("PWD",257).lines().getFirst(); int a=line.indexOf('"'); if(a<0) throw new IOException("Invalid PWD response");
        StringBuilder p=new StringBuilder(); for(int i=a+1;i<line.length();i++) { char c=line.charAt(i); if(c=='"') { if(i+1<line.length()&&line.charAt(i+1)=='"') { p.append('"'); i++; } else return p.toString(); } else p.append(c); } throw new IOException("Unterminated PWD path");
    }
    public synchronized void cwd(String path) throws IOException { execute("CWD "+path,250); }
    public synchronized long size(String path) throws IOException { Reply r=command("SIZE "+path); if(r.code()==550) return -1; r.expect(213); try { return Long.parseLong(r.lines().getFirst().substring(4).trim()); } catch(NumberFormatException e) { throw new IOException("Bad SIZE reply",e); } }
    public synchronized void rename(String from,String to) throws IOException { execute("RNFR "+from,350); execute("RNTO "+to,250); }
    private Socket prepare() throws IOException {
        cancelled=false;
        if(passive) {
            Reply r=command("EPSV"); int port;
            if(r.code()==229) {
                Matcher m=Pattern.compile("\\((.)(?:\\1){2}(\\d+)\\1\\)").matcher(r.message());
                if(!m.find()) throw new IOException("Bad EPSV response"); port=Integer.parseInt(m.group(2));
            } else {
                r=command("PASV"); r.expect(227); Matcher m=Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").matcher(r.message());
                if(!m.find()) throw new IOException("Bad PASV response"); port=Integer.parseInt(m.group(5))*256+Integer.parseInt(m.group(6));
                // Use control peer rather than untrusted/incorrect NAT PASV address.
            }
            Socket d=new Socket(); data=d; d.connect(new InetSocketAddress(control.getInetAddress(),port),timeout); d.setSoTimeout(timeout); return d;
        }
        ServerSocket ss=new ServerSocket(); activeListener=ss;
        ss.bind(new InetSocketAddress(control.getLocalAddress(),0)); ss.setSoTimeout(timeout);
        InetAddress a=control.getLocalAddress(); int port=ss.getLocalPort();
        if(a instanceof Inet4Address) execute("PORT "+a.getHostAddress().replace('.',',')+","+port/256+","+port%256,200);
        else execute("EPRT |2|"+a.getHostAddress()+"|"+port+"|",200);
        return null;
    }
    private Socket accept(Socket prepared) throws IOException {
        if(prepared!=null) return prepared;
        Socket d=activeListener.accept(); data=d; activeListener.close(); activeListener=null;
        if(!d.getInetAddress().equals(control.getInetAddress())) { d.close(); throw new IOException("Unexpected active data peer"); }
        d.setSoTimeout(timeout); return d;
    }
    private void cleanData() { Socket d=data; data=null; if(d!=null) try { d.close(); } catch(IOException ignored) {} ServerSocket s=activeListener; activeListener=null; if(s!=null) try { s.close(); } catch(IOException ignored) {} }
    private void preliminary(String cmd) throws IOException { Reply r=command(cmd); r.expect(125,150); }
    public synchronized List<RemoteEntry> list() throws IOException {
        var entries=new ArrayList<RemoteEntry>();
        try {
            Socket prepared=prepare(); preliminary("MLSD");
            try(Socket d=accept(prepared); var in=new BufferedInputStream(d.getInputStream())) {
                String line; while((line=FtpWire.line(in))!=null) {
                    int i=line.indexOf(' '); if(i<0) continue; String name=line.substring(i+1); var facts=new HashMap<String,String>();
                    for(String f:line.substring(0,i).split(";")) { int eq=f.indexOf('='); if(eq>0) facts.put(f.substring(0,eq).toLowerCase(Locale.ROOT),f.substring(eq+1)); }
                    String type=facts.getOrDefault("type","file"); if(type.equals("cdir")||type.equals("pdir")) continue;
                    long size; try { size=Long.parseLong(facts.getOrDefault("size","0")); } catch(NumberFormatException e) { size=0; }
                    entries.add(new RemoteEntry(name,type.equals("dir"),size,facts.getOrDefault("modify","")));
                }
            }
            read().expect(226,250); return entries;
        } catch(IOException e) { close(); throw e; } finally { cleanData(); }
    }
    public synchronized void transfer(boolean upload,Path local,String remote,long offset,LongConsumer progress) throws IOException {
        if(ascii && offset!=0) throw new IOException("Resume requires Binary mode");
        try {
            execute(ascii?"TYPE A":"TYPE I",200); Socket prepared=prepare();
            if(offset>0) execute("REST "+offset,350);
            preliminary((upload?"STOR ":"RETR ")+remote);
            try(Socket d=accept(prepared)) {
                if(upload) {
                    try(var in=new BufferedInputStream(Files.newInputStream(local)); var out=new BufferedOutputStream(d.getOutputStream())) {
                        try { in.skipNBytes(offset); FtpWire.copy(in,out,ascii,true,progress,()->cancelled); }
                        catch(IOException e) { resetSocket(d); throw e; }
                    }
                } else {
                    try(var in=new BufferedInputStream(d.getInputStream()); var file=new RandomAccessFile(local.toFile(),"rw")) {
                        file.setLength(offset); file.seek(offset);
                        OutputStream out=new OutputStream() { public void write(int b) throws IOException { file.write(b); } public void write(byte[] b,int o,int n) throws IOException { file.write(b,o,n); } };
                        FtpWire.copy(in,out,ascii,false,progress,()->cancelled); file.getFD().sync();
                    }
                }
            }
            read().expect(226,250); execute("TYPE I",200);
        } catch(IOException e) { close(); throw e; } finally { cleanData(); }
    }
    /** Safe cancellation tears down the session, preventing stale 426/226 replies affecting another command. */
    public void cancel() {
        cancelled=true; Socket s=control; control=null;
        Socket d=data; data=null; if(d!=null) resetSocket(d);
        ServerSocket listener=activeListener; activeListener=null;
        if(listener!=null) try { listener.close(); } catch(IOException ignored) {}
        // A best-effort ABOR must never perform a potentially blocking write on the FX thread.
        if(s!=null) Thread.ofVirtual().name("ftp-cancel").start(()-> {
            try { synchronized(writeLock) { FtpWire.write(s.getOutputStream(),"ABOR"); } log.accept("> ABOR (cancel and reconnect)"); }
            catch(IOException ignored) {} finally { try { s.close(); } catch(IOException ignored) {} }
        });
    }
    private void resetSocket(Socket s) { try { s.setSoLinger(true,0); s.close(); } catch(IOException ignored) {} }
    public synchronized void disconnect() { if(connected()) try { command("QUIT").expect(221); } catch(IOException ignored) {} close(); }
    @Override public void close() { cleanData(); Socket s=control; control=null; if(s!=null) try { s.close(); } catch(IOException ignored) {} }
}
