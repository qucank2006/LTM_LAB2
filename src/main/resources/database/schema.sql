CREATE DATABASE IF NOT EXISTS ftp_manager CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ftp_manager;
CREATE TABLE IF NOT EXISTS users (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 username VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 password_hash VARCHAR(60) NOT NULL,
 home_directory VARCHAR(1024) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS ftp_sessions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 client_ip VARCHAR(45) NOT NULL,
 connected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 disconnected_at TIMESTAMP NULL,
 FOREIGN KEY (user_id) REFERENCES users(id), INDEX ix_session_user_time(user_id,connected_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS transfer_history (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 filename VARCHAR(2048) NOT NULL,
 direction VARCHAR(16) NOT NULL,
 file_size BIGINT NOT NULL DEFAULT 0,
 status VARCHAR(32) NOT NULL,
 started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 completed_at TIMESTAMP NULL,
 FOREIGN KEY (user_id) REFERENCES users(id), INDEX ix_transfer_user_time(user_id,started_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS activity_logs (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NULL,
 command VARCHAR(32) NOT NULL,
 details TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (user_id) REFERENCES users(id), INDEX ix_activity_time(created_at), INDEX ix_activity_user(user_id)
) ENGINE=InnoDB;
-- Execute as MySQL administrator, choosing your own password, then update configuration:
-- CREATE USER 'ftp_manager'@'localhost' IDENTIFIED BY 'change-me';
-- GRANT SELECT,INSERT,UPDATE ON ftp_manager.* TO 'ftp_manager'@'localhost';
