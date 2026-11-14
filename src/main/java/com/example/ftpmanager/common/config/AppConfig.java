package com.example.ftpmanager.common.config;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public final class AppConfig {
    private final Properties values = new Properties();
    public AppConfig() throws IOException {
        try (InputStream in = AppConfig.class.getResourceAsStream("/config/application.properties")) {
            if (in == null) throw new IOException("Missing application.properties");
            values.load(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        String external = System.getProperty("ftp.config", System.getenv("FTP_CONFIG"));
        if (external != null && !external.isBlank()) try (var r = Files.newBufferedReader(Path.of(external))) { values.load(r); }
    }
    public String get(String key) { return System.getProperty(key, values.getProperty(key, "")); }
    public int number(String key) { return Integer.parseInt(get(key)); }
}
