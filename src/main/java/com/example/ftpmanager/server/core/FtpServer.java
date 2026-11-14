package com.example.ftpmanager.server.core;

import com.example.ftpmanager.common.config.AppConfig;
import com.example.ftpmanager.server.database.Database;
import com.example.ftpmanager.server.handler.FtpSession;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

public final class FtpServer implements AutoCloseable {
    public final AppConfig config;
    public final Database db;
    public final ConcurrentMap<UUID,FtpSession> sessions = new ConcurrentHashMap<>();
    public final AtomicLong uploaded = new AtomicLong(), downloaded = new AtomicLong();
    public final AtomicInteger transfers = new AtomicInteger();
    private final Consumer<String> log;
    private volatile ServerSocket listener;
    private ExecutorService workers;
    private Thread acceptThread;
    public FtpServer(AppConfig config, Database db, Consumer<String> log) { this.config=config; this.db=db; this.log=log; }
    public void log(String s) { LoggerFactory.getLogger(FtpServer.class).info(s); log.accept(java.time.LocalTime.now().withNano(0)+" "+s); }
    public synchronized void start(String bind, int port, int min, int max) throws Exception {
        if (listener != null) throw new IOException("Server already running");
        if (min<1024 || max>65535 || min>max || port<1 || port>65535) throw new IOException("Invalid port range");
        db.check(); ServerSocket socket = new ServerSocket();
        try { socket.setReuseAddress(true); socket.bind(new InetSocketAddress(bind,port)); } catch (Exception e) { socket.close(); throw e; }
        listener=socket; ExecutorService pool=Executors.newCachedThreadPool(); workers=pool;
        acceptThread = new Thread(() -> {
            while (!socket.isClosed()) try {
                Socket client=socket.accept();
                if(socket.isClosed()) { client.close(); break; }
                if (sessions.size()>=config.number("server.max.sessions")) { try(client) { com.example.ftpmanager.common.protocol.FtpWire.write(client.getOutputStream(),"421 Too many sessions"); } continue; }
                FtpSession session=null;
                try { session=new FtpSession(this,client,min,max); sessions.put(session.id,session); pool.submit(session); }
                catch(Exception e) { if(session!=null) { sessions.remove(session.id); session.close(); } else client.close(); log("Session initialization error: "+e.getMessage()); }
            } catch (IOException e) { if (!socket.isClosed()) log("Accept error: "+e.getMessage()); }
        },"ftp-accept"); acceptThread.setDaemon(true); acceptThread.start(); log("Listening on "+bind+":"+port);
    }
    public boolean running() { return listener!=null; }
    public synchronized void close() {
        ServerSocket old=listener; listener=null;
        if(old!=null) try { old.close(); } catch(IOException ignored) {}
        if(acceptThread!=null) try { acceptThread.join(1000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        for(var s:sessions.values()) s.close();
        if(workers!=null) { workers.shutdown(); try { if(!workers.awaitTermination(5,TimeUnit.SECONDS)) workers.shutdownNow(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); workers.shutdownNow(); } }
        log("Server stopped");
    }
    public void disconnectUser(long id) { sessions.values().stream().filter(s->s.userId()==id).forEach(FtpSession::close); }
}
