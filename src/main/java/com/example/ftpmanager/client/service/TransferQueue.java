package com.example.ftpmanager.client.service;

import com.example.ftpmanager.client.model.TransferItem;
import com.example.ftpmanager.client.network.FtpClient;
import com.example.ftpmanager.common.util.Ui;
import java.nio.file.*;
import java.io.IOException;
import java.util.function.*;

/** Runs on the same serial executor as navigation: one outstanding FTP transaction. */
public final class TransferQueue {
    public enum Decision { OVERWRITE, RESUME, CANCEL }
    @FunctionalInterface public interface Conflict { Decision decide(String description,boolean resume) throws Exception; }
    private final FtpClient client;
    private final Conflict conflict;
    private final Consumer<String> notify;
    public TransferQueue(FtpClient client,Conflict conflict,Consumer<String> notify) { this.client=client; this.conflict=conflict; this.notify=notify; }
    public void run(TransferItem item,boolean ascii) throws Exception {
        if(item.cancelled) return;
        Path partial=item.local.resolveSibling(item.local.getFileName()+".ftp-part"); long offset=0;
        if(!item.upload && (Files.isSymbolicLink(partial)||Files.isSymbolicLink(item.local))) throw new IOException("Refusing a symbolic link download destination");
        if(item.upload) {
            long existing=client.size(item.remote);
            if(existing>=0) {
                Decision d=conflict.decide("Remote file exists: "+item.remote+" ("+existing+" B)",!ascii&&existing<=Files.size(item.local));
                if(d==Decision.CANCEL) { item.cancelled=true; Ui.later(()->item.status.set("Cancelled")); return; } if(d==Decision.RESUME) offset=existing;
            }
        } else {
            if(Files.exists(partial)||Files.exists(item.local)) {
                Path candidate=Files.exists(partial)?partial:item.local; long existing=Files.size(candidate);
                Decision d=conflict.decide("Local file / partial exists: "+candidate+" ("+existing+" B)",!ascii&&existing<=item.total);
                if(d==Decision.CANCEL) { item.cancelled=true; Ui.later(()->item.status.set("Cancelled")); return; }
                if(d==Decision.RESUME) { offset=existing; if(!candidate.equals(partial)) Files.copy(candidate,partial,StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        if(item.cancelled) return;
        long base=offset,start=System.nanoTime(); long[] done={offset},last={0};
        Ui.later(()->item.status.set("Transferring"));
        client.transfer(item.upload,item.upload?item.local:partial,item.remote,offset,n->{
            done[0]+=n; long now=System.nanoTime();
            if(now-last[0]>100_000_000) {
                last[0]=now; long count=done[0]; double speed=(count-base)/Math.max(0.001,(now-start)/1e9);
                Ui.later(()-> { item.bytes.set(count+" / "+item.total+" B"); item.speed.set(String.format(java.util.Locale.ROOT,"%.1f KiB/s",speed/1024)); item.progress.set(item.total==0?0:Math.min(1,(double)count/item.total)); });
            }
            if(item.cancelled) client.cancel();
        });
        if(item.cancelled) throw new IOException("Cancelled");
        if(!item.upload) {
            try { Files.move(partial,item.local,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException e) { Files.move(partial,item.local,StandardCopyOption.REPLACE_EXISTING); }
        }
        long completed=done[0]; Ui.later(()-> { item.status.set("Completed"); item.progress.set(1); item.bytes.set(completed+" B"); });
        notify.accept("Completed: "+item.filename());
    }
}
