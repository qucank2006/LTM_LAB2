package com.example.ftpmanager.common.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Control and listing lines use UTF-8 with RFC 959 CRLF framing. */
public final class FtpWire {
    private FtpWire() {}
    public static String line(InputStream in) throws IOException {
        var bytes = new ByteArrayOutputStream(); int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] a = bytes.toByteArray();
                int n = a.length; if (n > 0 && a[n-1] == '\r') n--;
                return new String(a, 0, n, StandardCharsets.UTF_8);
            }
            if (bytes.size() >= 8192) throw new IOException("Control line too long");
            bytes.write(b);
        }
        if (bytes.size() != 0) throw new EOFException("Truncated control line");
        return null;
    }
    public static void write(OutputStream out, String text) throws IOException {
        if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) throw new IOException("Invalid CR/LF in protocol line");
        out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8)); out.flush();
    }
    public record Reply(int code, List<String> lines) {
        public String message() { return String.join("\n", lines); }
        public void expect(int... codes) throws IOException {
            for (int c: codes) if (c == code) return;
            throw new IOException(message());
        }
    }
    public static Reply read(InputStream in) throws IOException {
        String first = line(in);
        if (first == null) throw new EOFException("Control connection closed");
        if (first.length() < 4) throw new IOException("Malformed FTP reply");
        int code;
        try { code = Integer.parseInt(first.substring(0,3)); } catch (NumberFormatException e) { throw new IOException(first,e); }
        var lines = new ArrayList<String>(); lines.add(first);
        if (first.charAt(3) == '-') {
            String next;
            do {
                next = line(in); if (next == null) throw new EOFException("Truncated multiline reply");
                lines.add(next); if (lines.size() > 1000) throw new IOException("Reply too long");
            } while (!next.startsWith(code + " "));
        }
        return new Reply(code, List.copyOf(lines));
    }
    /** NVT ASCII output: local CRLF, LF and CR become CRLF, CRLF and CR-NUL. */
    public static long copy(InputStream in, OutputStream out, boolean ascii, boolean sending,
                            java.util.function.LongConsumer progress, java.util.function.BooleanSupplier cancelled) throws IOException {
        long total = 0;
        if (!ascii) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) { if (cancelled.getAsBoolean()) throw new InterruptedIOException("Cancelled"); out.write(b,0,n); total += n; progress.accept(n); }
        } else {
            int pending = -1, c;
            while ((c = pending >= 0 ? pending : in.read()) != -1) {
                pending = -1; if (cancelled.getAsBoolean()) throw new InterruptedIOException("Cancelled");
                if (sending) {
                    if (c == '\r') { int d = in.read(); out.write('\r'); out.write(d == '\n' ? '\n' : 0); if (d != '\n') pending = d; }
                    else if (c == '\n') { out.write('\r'); out.write('\n'); } else out.write(c);
                } else {
                    if (c == '\r') { int d = in.read(); if (d == '\n') out.write('\n'); else { out.write('\r'); if (d != 0) pending = d; } }
                    else out.write(c);
                }
                total++; progress.accept(1);
            }
        }
        out.flush(); return total;
    }
}
