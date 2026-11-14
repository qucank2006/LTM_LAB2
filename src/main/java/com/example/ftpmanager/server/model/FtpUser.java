package com.example.ftpmanager.server.model;
public record FtpUser(long id, String username, String passwordHash, String home, boolean enabled) {
    @Override public String toString() { return username + (enabled ? "" : " [disabled]") + " — " + home; }
}
