package com.example.ftpmanager.server.security;

import java.io.IOException;
import java.nio.file.*;

/** Rejects symlinks on every path component, including broken links. Root is canonical. */
public final class Sandbox {
    private final Path root;
    public Sandbox(String home) throws IOException { root = Path.of(home).toRealPath(); if (!Files.isDirectory(root)) throw new IOException("Invalid home directory"); }
    public Path resolve(String cwd, String input) throws IOException {
        if (input.indexOf('\0') >= 0 || input.indexOf('\\') >= 0 || input.indexOf(':') >= 0 || input.indexOf('\r') >= 0 || input.indexOf('\n') >= 0) throw new IOException("Invalid path");
        String relative = input.startsWith("/") ? input.substring(1) : (cwd.equals("/") ? "" : cwd.substring(1)+"/") + input;
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) throw new IOException("Access outside FTP root denied");
        Path next = root;
        for (Path part : root.relativize(p)) {
            next = next.resolve(part);
            if (Files.isSymbolicLink(next)) throw new IOException("Symbolic links are not permitted");
            if (Files.exists(next,LinkOption.NOFOLLOW_LINKS) && !next.toRealPath().startsWith(root)) throw new IOException("Root escape denied");
        }
        return p;
    }
    public String virtual(Path p) { String s = root.relativize(p).toString().replace('\\','/'); return "/"+s; }
    public boolean isRoot(Path p) { return root.equals(p); }
}
