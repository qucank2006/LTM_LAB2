package com.example.ftpmanager.client.model;

public record RemoteEntry(String name, boolean directory, long size, String modified) {
    public String type() { return directory?"Folder":"File"; }
}
