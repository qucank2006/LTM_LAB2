package com.example.ftpmanager.client.model;
import java.nio.file.Path;
public record LocalEntry(Path path, String name, boolean directory, long size, String modified) {
    public String type() { return directory?"Folder":"File"; }
}
