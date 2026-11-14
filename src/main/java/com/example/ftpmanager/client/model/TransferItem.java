package com.example.ftpmanager.client.model;

import javafx.beans.property.*;
import java.nio.file.Path;

public final class TransferItem {
    public final boolean upload;
    public final Path local;
    public final String remote;
    public final long total;
    public final StringProperty status=new SimpleStringProperty("Queued"),speed=new SimpleStringProperty("0 B/s"),bytes=new SimpleStringProperty("0 B");
    public final DoubleProperty progress=new SimpleDoubleProperty(0);
    public volatile boolean cancelled;
    public TransferItem(boolean upload, Path local, String remote, long total) { this.upload=upload; this.local=local; this.remote=remote; this.total=total; }
    public String filename() { return local.getFileName().toString(); }
    public String source() { return upload?local.toString():remote; }
    public String destination() { return upload?remote:local.toString(); }
}
