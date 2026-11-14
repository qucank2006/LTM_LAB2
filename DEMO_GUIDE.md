# Hướng dẫn demo FTP Manager

Các lệnh dưới đây dành cho **người dùng chạy**. Khi bàn giao, tác giả chưa build, chạy chương trình hoặc kiểm thử. Thực hiện từ thư mục chứa pom.xml bằng PowerShell; JDK 21, Maven và MySQL 8 phải có sẵn.

## A. Chuẩn bị

```powershell
cd C:\Users\qank0\IdeaProjects\LAB2
java -version
mvn -version
mysql -u root -p
```

Trong MySQL:

```sql
SOURCE C:/Users/qank0/IdeaProjects/LAB2/src/main/resources/database/schema.sql;
CREATE USER 'ftp_manager'@'localhost' IDENTIFIED BY 'YourDatabasePassword';
GRANT SELECT, INSERT, UPDATE ON ftp_manager.* TO 'ftp_manager'@'localhost';
EXIT;
```

Nếu user tồn tại, dùng ALTER USER thay CREATE USER. Sửa `src/main/resources/config/application.properties`: db.password đúng giá trị vừa tạo, server.port=2121 cho demo, passive.min=50000 và max=50100. Khi dùng tệp cấu hình ngoài, xem README và đặt FTP_CONFIG cho terminal chạy Server.

```powershell
mvn clean compile
mvn -Pserver javafx:run
```

Trong terminal khác:

```powershell
cd C:\Users\qank0\IdeaProjects\LAB2
mvn javafx:run
```

## B. Kịch bản demo tại một máy

1. **Server GUI:** giải thích bind 0.0.0.0, port 2121, dải passive 50000–50100; bấm Start Server, chỉ ra RUNNING/IP/dashboard.
2. **Tài khoản:** nhập `student`, mật khẩu ví dụ `LabDemo2026!`, home `C:/ftp/student`, Create account, Refresh users. Mật khẩu demo này chỉ dùng trong lab. Có thể tạo user trước khi Start.
3. **Login Client:** Host `127.0.0.1`, Port `2121`, user và mật khẩu trên. Chọn Passive, Binary; Quick Connect. Quan sát `220 → USER → 331 → PASS [redacted] → 230`.
4. **Duyệt:** ở local chọn thư mục chứa `ảnh Việt Nam.jpg`, PDF và ZIP. Ở remote thấy `/`. Double-click thư mục hoặc nhập đường dẫn và Enter. Chuột phải tạo folder `Bài thực hành`.
5. **Upload:** chọn nhiều tệp, Upload. Quan sát queue, byte, speed, progress, Completed; Server active transfers và uploaded bytes thay đổi. Tệp lớn giúp dễ quan sát hơn.
6. **Download:** chọn thư mục local khác, chọn remote file, Download. Tệp `.ftp-part` chỉ đổi thành tên cuối sau phản hồi thành công.
7. **Quản lý:** remote chuột phải Rename, View properties, Delete; tạo và xóa folder rỗng; Refresh sau thao tác. Thử `../..` vượt root để thấy bị chặn.
8. **Hủy/thử lại:** download tệp lớn, chọn hàng queue và Cancel selected. Quan sát Cancelled và partial local. Retry / Resume selected, chọn Resume; log có REST và 350. Client reconnect vì cancel chủ động đóng control để tránh reply tồn dư.
9. **Ghi đè:** tải/đẩy lại tệp trùng tên, chọn Overwrite. Với Resume upload, chỉ chọn khi tệp remote là prefix đúng của tệp local; Server không giữ phần upload lỗi vì upload là atomic publish.
10. **User management:** Disable user đang login, thấy phiên bị đóng. Enable lại; đổi password bằng ô Password, login lại với password mới. Assign home sử dụng ô Home.
11. **Đa client:** mở thêm Client hoặc FileZilla, login user khác; chỉ ra các cwd và TYPE độc lập. Chọn client trong Server, Disconnect selected client.
12. **Stop:** bấm Stop Server; các phiên và listener đóng. Start lại để chứng minh lifecycle.

Kiểm tra SHA-256 cho binary, thay đường dẫn bằng hai tệp thực tế:

```powershell
Get-FileHash 'C:\demo\source.zip' -Algorithm SHA256
Get-FileHash 'C:\downloads\source.zip' -Algorithm SHA256
```

Hai hash cần giống nhau; đây là bước xác minh do người dùng thực hiện, không phải kết quả đã chạy.

## C. LAN hai máy

Máy A chạy Server + MySQL, máy B chạy Client. Trên A:

```powershell
ipconfig
```

Ví dụ IPv4 A là 192.168.1.10. Đặt passive.address=192.168.1.10 trong cấu hình, khởi động lại Server. Trong PowerShell Administrator trên A:

```powershell
New-NetFirewallRule -DisplayName "FTP Manager Control" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 2121 -Profile Private
New-NetFirewallRule -DisplayName "FTP Manager Passive" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 50000-50100 -Profile Private
```

Trên B, kiểm tra control port và mở Client:

```powershell
Test-NetConnection 192.168.1.10 -Port 2121
mvn javafx:run
```

Nhập Host 192.168.1.10 thay localhost. Không mở 3306 cho B. Hai máy cần cùng mạng có thể liên lạc, không bị Wi-Fi client isolation.

## D. FileZilla và hai chế độ dữ liệu

1. FileZilla Site Manager → FTP → Host IP A → Port 2121 → Only use plain FTP → Normal login.
2. Transfer Settings → Passive. List, upload, download, rename, mkdir, delete. Quan sát Server log `EPSV` hoặc `PASV`, `MLSD` hoặc `LIST`, `150`, `226`.
3. Disconnect, đổi sang Active, Connect lại. Cho phép executable Java/FileZilla qua firewall Private trên máy Client; active cần inbound data port tại Client. Quan sát `PORT` hoặc `EPRT` và data connect từ Server.
4. Custom Client cũng có ComboBox Passive/Active; chọn Active **trước khi thêm tệp vào queue** rồi upload/download. Đổi mode được áp dụng tuần tự cho các thao tác mạng tiếp theo; transfer đã xếp hàng giữ mode lúc thêm vào queue.
5. Trong FileZilla hủy một transfer lớn để quan sát `ABOR` nếu phiên bản Client gửi lệnh này. Server trả `426`, sau đó `226`; đóng data socket không được ghi nhận thành công.
6. Giải thích: port 2121 luôn là control; mỗi lần truyền có TCP data connection riêng. Passive listener thuộc Server, active listener thuộc Client. Chỉ mở control port là chưa đủ.

FTP không mã hóa. Không chọn SFTP hoặc bắt buộc TLS, không sử dụng trên mạng công cộng.

## E. Kiểm tra MySQL khi vấn đáp

```powershell
mysql -u ftp_manager -p ftp_manager
```

```sql
SELECT id,username,password_hash,home_directory,enabled FROM users;
SELECT * FROM ftp_sessions ORDER BY id DESC LIMIT 20;
SELECT * FROM transfer_history ORDER BY id DESC LIMIT 20;
SELECT * FROM activity_logs ORDER BY id DESC LIMIT 50;
SELECT * FROM activity_logs WHERE command='PASS' ORDER BY id DESC LIMIT 10;
```

Chỉ ra hash BCrypt thay vì plaintext, PASS details là `[redacted]`, foreign keys user_id và timestamp. Hàng đợi Client là lịch sử trong phiên giao diện; MySQL là lịch sử lưu bền vững phía Server. Nếu kill tiến trình thay vì Stop/QUIT, có thể còn session chưa kết thúc hoặc history RUNNING.

## F. Tự kiểm tra sau build

- Login đúng/sai, năm lần sai bị đóng phiên; tài khoản disabled bị từ chối.
- Tên có khoảng trắng và tiếng Việt, thư mục con, root traversal/symlink bị chặn.
- Binary ảnh/PDF/ZIP, tệp rỗng và tệp lớn; so sánh hash khi upload/download.
- ASCII dùng một tệp text, kiểm tra newline; không so hash byte gốc nếu newline đã chuyển.
- Passive và Active, một và nhiều client, timeout data, ngắt mạng, Stop Server giữa transfer.
- Cancel và retry download bằng REST; upload lỗi không thay thế tệp đích trước đó.
- Overwrite/Resume/Cancel trong dialog; rename không tự ghi đè tên đích đã tồn tại.
- FXML mở đủ hai cửa sổ, context menus hoạt động, UI còn phản hồi lúc truyền lớn.

Các mục này là checklist người dùng thực hiện; không có kết quả chạy sẵn được tuyên bố trong dự án.
