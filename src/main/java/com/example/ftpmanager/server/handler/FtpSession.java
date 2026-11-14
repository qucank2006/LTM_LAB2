package com.example.ftpmanager.server.handler;

import com.example.ftpmanager.common.protocol.FtpWire;
import com.example.ftpmanager.server.core.FtpServer;
import com.example.ftpmanager.server.command.Listing;
import com.example.ftpmanager.server.model.FtpUser;
import com.example.ftpmanager.server.security.Sandbox;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One control reader per session; one transfer worker, with ABOR processed by reader. */
public final class FtpSession implements Runnable, AutoCloseable {
    public final UUID id=UUID.randomUUID();
    public final String ip;
    public final java.time.Instant connected=java.time.Instant.now();
    public volatile String username="(anonymous)", operation="Connected";
    private final FtpServer server;
    private final Socket control;
    private final int min,max,timeout;
    private final ExecutorService transferWorker=Executors.newSingleThreadExecutor();
    private final Object gate=new Object();
    private volatile boolean closed, busy, abortRequested;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private volatile Socket data;
    private volatile ServerSocket passive;
    private InetSocketAddress active;
    private FtpUser user;
    private Sandbox sandbox;
    private String cwd="/", pendingUser;
    private Path rename;
    private long restart, sessionId;
    private boolean binary=true;
    private long passiveExpires;
    private int failures;
    public FtpSession(FtpServer server, Socket control, int min, int max) throws SocketException {
        this.server=server; this.control=control; this.min=min; this.max=max;
        timeout=server.config.number("socket.timeout.ms"); control.setSoTimeout(timeout); control.setKeepAlive(true); ip=control.getInetAddress().getHostAddress();
    }
    public long userId() { return user==null?0:user.id(); }
    private synchronized void raw(String line) throws IOException { FtpWire.write(control.getOutputStream(),line); server.log(ip+" < "+line); }
    private void reply(int code,String message) throws IOException { raw(code+" "+message.replace('\r',' ').replace('\n',' ')); }
    private void audit(String cmd,String text) { try { server.db.activity(user==null?null:user.id(),cmd,text); } catch(Exception e) { server.log("Database audit error: "+e.getMessage()); } }
    @Override public void run() {
        try {
            reply(220,"FTP Manager ready — UTF-8"); String line;
            while(!closed && (line=nextCommand())!=null) {
                int space=line.indexOf(' '); String cmd=(space<0?line:line.substring(0,space)).toUpperCase(Locale.ROOT);
                String arg=space<0?"":line.substring(space+1);
                String safe=cmd.equals("PASS")?"[redacted]":arg;
                server.log(ip+" > "+cmd+" "+safe); audit(cmd,safe);
                try { synchronized(gate) { dispatch(cmd,arg); } }
                catch (IllegalArgumentException e) { reply(501,"Invalid argument"); }
                catch (Exception e) { server.log(ip+" Error: "+e.getMessage()); reply(550,"Operation failed: "+e.getMessage()); }
            }
        } catch(SocketTimeoutException e) { try { reply(421,"Control connection timed out"); } catch(IOException ignored) {} }
        catch(IOException e) { if(!closed) server.log(ip+" disconnected: "+e.getMessage()); }
        finally { close(); try { transferWorker.awaitTermination(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } if(sessionId!=0) try { server.db.disconnected(sessionId); } catch(Exception e) { server.log("Session audit error: "+e.getMessage()); } server.sessions.remove(id); server.log(ip+" disconnected"); }
    }
    private String nextCommand() throws IOException {
        while(!closed) {
            try { return FtpWire.line(control.getInputStream()); }
            catch(SocketTimeoutException e) { if(!busy) throw e; }
        }
        return null;
    }
    private void dispatch(String cmd,String arg) throws Exception {
        if(cmd.equals("QUIT")) { reply(221,"Goodbye"); close(); return; }
        if(cmd.equals("ABOR")) {
            synchronized(gate) {
                if(busy) { abortRequested=true; cancelled.set(true); closeData(); }
                else { resetEndpoint(); restart=0; reply(225,"No transfer active"); }
            } return;
        }
        if(busy) { reply(450,"Transfer active; use ABOR first"); return; }
        if(!cmd.equals("RNFR") && !cmd.equals("RNTO")) rename=null;
        switch(cmd) {
            case "USER" -> {
                if(user!=null) { reply(503,"Already logged in; reconnect to change user"); return; }
                pendingUser=arg; reply(331,"Password required");
            }
            case "PASS" -> {
                if(user!=null || pendingUser==null) { reply(503,"Send USER first"); return; }
                FtpUser found=server.db.authenticate(pendingUser,arg);
                if(found==null) { reply(530,"Authentication failed"); server.log(ip+" login failed: "+pendingUser); if(++failures>=5) { reply(421,"Too many login failures"); close(); } }
                else {
                    Sandbox root=new Sandbox(found.home()); long sid=server.db.session(found.id(),ip);
                    user=found; sandbox=root; sessionId=sid; username=user.username(); cwd="/"; reply(230,"Login successful"); operation="Idle";
                }
            }
            case "SYST" -> reply(215,"UNIX Type: L8");
            case "FEAT" -> { raw("211-Features"); raw(" UTF8"); raw(" EPSV"); raw(" EPRT"); raw(" SIZE"); raw(" MDTM"); raw(" REST STREAM"); raw(" MLST type*;size*;modify*;"); raw("211 End"); }
            case "OPTS" -> { if(arg.equalsIgnoreCase("UTF8 ON") || arg.toUpperCase(Locale.ROOT).startsWith("MLST ")) reply(200,"Options accepted"); else reply(501,"Supported: UTF8 ON, MLST facts"); }
            case "NOOP" -> reply(200,"OK");
            default -> { if(user==null) { reply(530,"Please login"); return; } authenticated(cmd,arg); }
        }
    }
    private Path path(String a) throws IOException { return sandbox.resolve(cwd,a); }
    private void authenticated(String cmd,String arg) throws Exception {
        switch(cmd) {
            case "PWD", "XPWD" -> reply(257,"\""+cwd.replace("\"","\"\"")+"\" is current directory");
            case "CWD", "CDUP" -> { Path p=path(cmd.equals("CDUP")?(cwd.equals("/")?"/":".."):arg); if(!Files.isDirectory(p)) throw new IOException("Not a directory"); cwd=sandbox.virtual(p); reply(250,"Directory changed"); }
            case "TYPE" -> { if(arg.equalsIgnoreCase("I") || arg.equalsIgnoreCase("L 8")) binary=true; else if(arg.equalsIgnoreCase("A") || arg.equalsIgnoreCase("A N")) binary=false; else { reply(504,"Unsupported type"); return; } restart=0; reply(200,"Type set"); }
            case "MODE" -> reply(arg.equalsIgnoreCase("S")?200:504,"Stream mode only");
            case "STRU" -> reply(arg.equalsIgnoreCase("F")?200:504,"File structure only");
            case "PASV", "EPSV" -> passive(cmd,arg);
            case "PORT", "EPRT" -> active(cmd,arg);
            case "REST" -> { if(!binary) { reply(504,"REST requires TYPE I"); return; } long n=Long.parseLong(arg); if(n<0) throw new IllegalArgumentException(); restart=n; reply(350,"Restart offset accepted: "+n); }
            case "LIST", "NLST", "MLSD", "RETR", "STOR" -> { try { transfer(cmd,arg); } catch(Exception e) { resetEndpoint(); restart=0; throw e; } }
            case "MLST" -> { Path p=path(arg); if(!Files.exists(p)) throw new IOException("Not found"); raw("250-Listing"); raw(" "+Listing.facts(p,sandbox.virtual(p))); raw("250 End"); }
            case "SIZE" -> { if(!binary) { reply(550,"SIZE requires TYPE I"); return; } Path p=path(arg); if(!Files.isRegularFile(p)) throw new IOException("Not a file"); reply(213,""+Files.size(p)); }
            case "MDTM" -> reply(213,Listing.timestamp(path(arg)));
            case "MKD", "XMKD" -> { Path p=path(arg); Files.createDirectory(p); reply(257,"\""+sandbox.virtual(p).replace("\"","\"\"")+"\" created"); }
            case "DELE" -> { Path p=path(arg); if(!Files.isRegularFile(p)) throw new IOException("Not a regular file"); Files.delete(p); reply(250,"Deleted"); }
            case "RMD", "XRMD" -> { Path p=path(arg); if(sandbox.isRoot(p) || !Files.isDirectory(p) || path(cwd).startsWith(p)) throw new IOException("Directory is in use or protected"); Files.delete(p); reply(250,"Removed"); }
            case "RNFR" -> { rename=null; Path p=path(arg); if(sandbox.isRoot(p) || !Files.exists(p) || path(cwd).startsWith(p)) throw new IOException("Invalid rename source"); rename=p; reply(350,"Send RNTO"); }
            case "RNTO" -> { Path from=rename; rename=null; if(from==null) { reply(503,"Send RNFR first"); return; } Path to=path(arg); if(sandbox.isRoot(to)) throw new IOException("Protected root"); path(sandbox.virtual(from)); Files.move(from,to); reply(250,"Renamed"); }
            default -> reply(502,"Command not implemented");
        }
    }
    private void passive(String cmd,String arg) throws Exception {
        resetEndpoint();
        if(cmd.equals("EPSV") && !arg.isBlank() && !arg.equals("1") && !arg.equals("2")) { reply(522,"Use EPSV with no argument"); return; }
        for(int i=0;i<=max-min;i++) {
            int port=min+Math.floorMod(id.hashCode()+i,max-min+1); ServerSocket ss=new ServerSocket();
            try { ss.bind(new InetSocketAddress(control.getLocalAddress(),port)); ss.setSoTimeout(timeout); passive=ss; passiveExpires=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout); break; }
            catch(IOException e) { ss.close(); }
        }
        if(passive==null) { reply(425,"No passive port available"); return; }
        if(cmd.equals("EPSV")) reply(229,"Entering Extended Passive Mode (|||"+passive.getLocalPort()+"|)");
        else {
            String configured=server.config.get("server.passive.address"); InetAddress addr=configured.isBlank()?control.getLocalAddress():InetAddress.getByName(configured);
            byte[] b=addr.getAddress(); if(b.length!=4) { resetEndpoint(); reply(522,"Use EPSV for IPv6"); return; }
            int port=passive.getLocalPort(); reply(227,"Entering Passive Mode ("+(b[0]&255)+","+(b[1]&255)+","+(b[2]&255)+","+(b[3]&255)+","+(port/256)+","+(port%256)+")");
        }
    }
    private void active(String cmd,String arg) throws Exception {
        resetEndpoint(); InetAddress address; int port;
        if(cmd.equals("PORT")) {
            String[] n=arg.split(",",-1); if(n.length!=6) throw new IllegalArgumentException(); int[] v=new int[6]; for(int i=0;i<6;i++) { v[i]=Integer.parseInt(n[i]); if(v[i]<0 || v[i]>255) throw new IllegalArgumentException(); }
            address=InetAddress.getByAddress(new byte[]{(byte)v[0],(byte)v[1],(byte)v[2],(byte)v[3]}); port=v[4]*256+v[5];
        } else {
            if(arg.length()<5) throw new IllegalArgumentException(); String[] n=arg.split(java.util.regex.Pattern.quote(arg.substring(0,1)),-1);
            if(n.length!=5 || !(n[1].equals("1")||n[1].equals("2"))) throw new IllegalArgumentException();
            address=InetAddress.getByName(n[2]); if((n[1].equals("1"))!=(address instanceof Inet4Address)) throw new IllegalArgumentException(); port=Integer.parseInt(n[3]);
        }
        if(!address.equals(control.getInetAddress()) || port<1024 || port>65535) { reply(501,"FTP bounce protection: use peer IP and port >=1024"); return; }
        active=new InetSocketAddress(address,port); reply(200,"Active endpoint accepted");
    }
    private Socket openData() throws IOException {
        Socket s;
        ServerSocket ss=passive;
        if(ss!=null) {
            long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
            while(true) {
                ss.setSoTimeout((int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(until-System.nanoTime())));
                s=ss.accept(); if(s.getInetAddress().equals(control.getInetAddress())) break; s.close();
                if(System.nanoTime()>=until) throw new SocketTimeoutException("Data peer timeout");
            }
            ss.close(); passive=null;
        } else if(active!=null) {
            InetSocketAddress endpoint=active; s=new Socket();
            synchronized(gate) { if(cancelled.get()) { s.close(); throw new InterruptedIOException("Aborted"); } data=s; }
            s.connect(endpoint,timeout);
        }
        else throw new IOException("Send PASV/EPSV or PORT/EPRT first");
        synchronized(gate) { if(cancelled.get()) { s.close(); throw new InterruptedIOException("Aborted"); } data=s; }
        s.setSoTimeout(timeout); return s;
    }
    private void transfer(String cmd,String arg) throws Exception {
        if(passive!=null && System.nanoTime()>passiveExpires) resetEndpoint();
        if(passive==null && active==null) { reply(425,"Use PASV/EPSV or PORT/EPRT"); return; }
        long offset=restart; restart=0;
        // LIST flags used by FileZilla: only recognized leading options are ignored.
        String name=arg; if(cmd.equals("LIST") && (name.equals("-a") || name.equals("-l") || name.equals("-al"))) name="";
        Path target=path(name);
        boolean upload=cmd.equals("STOR"), download=cmd.equals("RETR"), file=upload||download;
        if(download && !Files.isRegularFile(target)) throw new IOException("Not a file");
        if(upload && (sandbox.isRoot(target) || !Files.isDirectory(target.getParent()) || Files.isDirectory(target))) throw new IOException("Invalid destination");
        if(!file && !Files.exists(target)) throw new IOException("Not found");
        if(file && offset>0 && (!Files.isRegularFile(target)||offset>Files.size(target))) throw new IOException("Restart exceeds existing file");
        if(!file && offset!=0) { reply(503,"REST is for file transfers"); return; }
        final long history=file?server.db.transfer(user.id(),sandbox.virtual(target),upload?"UPLOAD":"DOWNLOAD"):0;
        cancelled.set(false); abortRequested=false; busy=true; operation=cmd+" "+sandbox.virtual(target); server.transfers.incrementAndGet();
        try { reply(150,"Opening "+(binary?"binary":"ASCII")+" data connection"); }
        catch(IOException e) { busy=false; server.transfers.decrementAndGet(); resetEndpoint(); if(history!=0) server.db.finished(history,0,"FAILED"); throw e; }
        try { transferWorker.submit(() -> {
            long[] bytes={0}; Path temp=null; boolean success=false; boolean opened=false; String failure="Transfer failed";
            try(Socket socket=openData()) {
                opened=true;
                if(upload) {
                    temp=Files.createTempFile(target.getParent(),".ftp-upload-",".part");
                    if(offset>0) Files.copy(target,temp,StandardCopyOption.REPLACE_EXISTING);
                    try(var out=new RandomAccessFile(temp.toFile(),"rw"); var in=new BufferedInputStream(socket.getInputStream())) {
                        out.setLength(offset); out.seek(offset);
                        OutputStream sink=new OutputStream() { public void write(int b) throws IOException { out.write(b); } public void write(byte[] b,int o,int n) throws IOException { out.write(b,o,n); } };
                        FtpWire.copy(in,sink,!binary,false,n->{ bytes[0]+=n; server.uploaded.addAndGet(n); },cancelled::get);
                        out.getFD().sync();
                    }
                    synchronized(gate) {
                        if(cancelled.get()) throw new InterruptedIOException("Aborted");
                        path(sandbox.virtual(target)); // Revalidate before publishing the temporary upload.
                        try { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
                        catch(AtomicMoveNotSupportedException e) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
                        temp=null;
                    }
                } else if(download) {
                    try(var in=new BufferedInputStream(Files.newInputStream(target)); var out=new BufferedOutputStream(socket.getOutputStream())) {
                        in.skipNBytes(offset); FtpWire.copy(in,out,!binary,true,n->{ bytes[0]+=n; server.downloaded.addAndGet(n); },cancelled::get);
                    }
                } else {
                    try(var out=new BufferedOutputStream(socket.getOutputStream())) {
                        List<Path> entries;
                        if(Files.isDirectory(target)) { try(var stream=Files.list(target)) { entries=stream.sorted().toList(); } } else entries=List.of(target);
                        for(Path p:entries) {
                            if(cancelled.get()) throw new InterruptedIOException("Aborted");
                            if(Files.isSymbolicLink(p)) continue;
                            String filename=p.getFileName().toString(); if(filename.contains("\r")||filename.contains("\n")||filename.startsWith(".ftp-upload-")) continue;
                            path(sandbox.virtual(p));
                            FtpWire.write(out,cmd.equals("NLST")?filename:cmd.equals("MLSD")?Listing.facts(p,filename):Listing.unix(p));
                        }
                    }
                }
                success=true;
            } catch(Exception e) { failure=e.getMessage()==null?"Data error":e.getMessage(); server.log(ip+" transfer error: "+failure); }
            finally {
                if(temp!=null) try { Files.deleteIfExists(temp); } catch(IOException e) { server.log("Temporary file cleanup: "+e.getMessage()); }
                synchronized(gate) {
                    success=success&&!cancelled.get(); closeData(); active=null;
                    if(history!=0) try { server.db.finished(history,bytes[0],success?"COMPLETED":cancelled.get()?"CANCELLED":"FAILED"); } catch(Exception e) { server.log("Transfer audit error: "+e.getMessage()); }
                    try { if(success) reply(226,"Transfer complete"); else { reply(opened||cancelled.get()?426:425,"Transfer interrupted: "+failure); if(abortRequested) reply(226,"Abort successful"); } } catch(IOException ignored) {}
                    busy=false; operation="Idle"; server.transfers.decrementAndGet();
                }
            }
        }); } catch(RejectedExecutionException e) {
            busy=false; operation="Disconnected"; server.transfers.decrementAndGet(); resetEndpoint();
            if(history!=0) try { server.db.finished(history,0,"CANCELLED"); } catch(Exception auditError) { server.log("Transfer audit error: "+auditError.getMessage()); }
            throw new IOException("Session is shutting down",e);
        }
    }
    private void closeData() { Socket d=data; data=null; if(d!=null) try { d.close(); } catch(IOException ignored) {} ServerSocket p=passive; passive=null; if(p!=null) try { p.close(); } catch(IOException ignored) {} }
    private void resetEndpoint() { closeData(); active=null; }
    @Override public void close() { closed=true; cancelled.set(true); closeData(); try { control.close(); } catch(IOException ignored) {} transferWorker.shutdownNow(); }
}
