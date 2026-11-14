package com.example.ftpmanager.server.database;

import com.example.ftpmanager.common.config.AppConfig;
import com.example.ftpmanager.server.model.FtpUser;
import org.mindrot.jbcrypt.BCrypt;
import java.sql.*;
import java.nio.file.*;
import java.util.*;

/** Each operation owns its connection; JDBC resources never cross session threads. */
public final class Database {
    private final AppConfig config;
    public Database(AppConfig config) { this.config = config; }
    private Connection open() throws SQLException { return DriverManager.getConnection(config.get("db.url"), config.get("db.username"), config.get("db.password")); }
    public void check() throws SQLException { try (var c = open(); var s = c.prepareStatement("SELECT id FROM users LIMIT 1")) { s.executeQuery().close(); } }
    private FtpUser user(ResultSet r) throws SQLException { return new FtpUser(r.getLong("id"),r.getString("username"),r.getString("password_hash"),r.getString("home_directory"),r.getBoolean("enabled")); }
    public FtpUser authenticate(String name, String password) throws SQLException {
        try (var c = open(); var s = c.prepareStatement("SELECT * FROM users WHERE username=?")) {
            s.setString(1,name); try (var r = s.executeQuery()) {
                if (!r.next()) { BCrypt.checkpw(password, DUMMY); return null; }
                FtpUser u = user(r); return u.enabled() && BCrypt.checkpw(password,u.passwordHash()) ? u : null;
            }
        }
    }
    private static final String DUMMY = BCrypt.hashpw("unused", BCrypt.gensalt(12));
    public List<FtpUser> users() throws SQLException {
        var list = new ArrayList<FtpUser>();
        try (var c = open(); var s = c.prepareStatement("SELECT * FROM users ORDER BY username"); var r = s.executeQuery()) { while (r.next()) list.add(user(r)); }
        return list;
    }
    private static void password(String p) { if (p.length() < 8 || p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw new IllegalArgumentException("Password must contain 8–72 UTF-8 bytes"); }
    public void createUser(String name, String pass, String home) throws Exception {
        if (!name.matches("[\\p{L}\\p{N}_.-]{1,64}")) throw new IllegalArgumentException("Invalid username");
        password(pass); Path path = Path.of(home).toAbsolutePath().normalize(); Files.createDirectories(path);
        String hash = BCrypt.hashpw(pass, BCrypt.gensalt(12));
        try (var c = open()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("INSERT INTO users(username,password_hash,home_directory) VALUES(?,?,?)")) {
                s.setString(1,name); s.setString(2,hash); s.setString(3,path.toRealPath().toString()); s.executeUpdate(); c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }
    public void changePassword(long id, String pass) throws SQLException { password(pass); update("UPDATE users SET password_hash=? WHERE id=?", BCrypt.hashpw(pass,BCrypt.gensalt(12)),id); }
    public void home(long id, String home) throws Exception { Path p = Path.of(home).toAbsolutePath(); Files.createDirectories(p); update("UPDATE users SET home_directory=? WHERE id=?",p.toRealPath().toString(),id); }
    public void enabled(long id, boolean enabled) throws SQLException { update("UPDATE users SET enabled=? WHERE id=?",enabled,id); }
    private void update(String sql, Object... args) throws SQLException { try (var c = open(); var s = c.prepareStatement(sql)) { bind(s,args); s.executeUpdate(); } }
    private void bind(PreparedStatement s, Object... args) throws SQLException { for (int i=0;i<args.length;i++) s.setObject(i+1,args[i]); }
    private long insert(String sql, Object... args) throws SQLException {
        try (var c = open(); var s = c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) { bind(s,args); s.executeUpdate(); try (var r = s.getGeneratedKeys()) { if (!r.next()) throw new SQLException("Missing generated ID"); return r.getLong(1); } }
    }
    public long session(long user, String ip) throws SQLException { return insert("INSERT INTO ftp_sessions(user_id,client_ip) VALUES(?,?)",user,ip); }
    public void disconnected(long id) throws SQLException { update("UPDATE ftp_sessions SET disconnected_at=CURRENT_TIMESTAMP WHERE id=?",id); }
    public void activity(Long user, String command, String details) throws SQLException { update("INSERT INTO activity_logs(user_id,command,details) VALUES(?,?,?)",user,command,details); }
    public long transfer(long user, String filename, String direction) throws SQLException { return insert("INSERT INTO transfer_history(user_id,filename,direction,status) VALUES(?,?,?,'RUNNING')",user,filename,direction); }
    public void finished(long id, long bytes, String status) throws SQLException { update("UPDATE transfer_history SET file_size=?,status=?,completed_at=CURRENT_TIMESTAMP WHERE id=?",bytes,status,id); }
}
