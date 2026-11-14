# FTP Manager — Computer Networking Lab 2

Ứng dụng gồm **FTP Server** và **FTP Client** JavaFX trong **một dự án Maven**, Java 21. Giao thức FTP được viết trực tiếp bằng `ServerSocket`/`Socket`, không dùng thư viện FTP có sẵn. Giao diện dùng Catppuccin Mocha; Server lưu tài khoản, phiên, hoạt động và lịch sử truyền trong MySQL.

**Trạng thái bàn giao:** chỉ rà soát mã nguồn tĩnh. Chưa chạy Maven, biên dịch, kiểm thử, MySQL, Server, Client hoặc FileZilla. Người dùng cần thực hiện các bước dưới đây để xác nhận trên máy thật.

## 1. Công nghệ và cấu trúc

- Java 21, JavaFX Controls/FXML 21.0.2, Maven.
- MySQL 8, Connector/J 8.4.0, JDBC `PreparedStatement`.
- BCrypt, SLF4J Simple, ExecutorService, Java NIO.
- Một `pom.xml`; toàn bộ Java và tài nguyên ứng dụng ở `src/main/`; không có module Maven phụ hoặc mã kiểm thử.

```text
src/main/java/com/example/ftpmanager/
  common/config/AppConfig.java       cấu hình mặc định + cấu hình ngoài
  common/protocol/FtpWire.java       CRLF, phản hồi đa dòng, streaming, NVT ASCII
  common/util/Ui.java                chuyển cập nhật sang JavaFX thread
  server/ServerMain.java             điểm vào Server
  server/core/                      vòng accept, dashboard, controller Server
  server/handler/FtpSession.java     trạng thái độc lập và xử lý lệnh mỗi phiên
  server/command/Listing.java        LIST, MLSD, MLST, thời gian UTC
  server/database/Database.java      JDBC, BCrypt, transaction tạo tài khoản
  server/security/Sandbox.java      giới hạn đường dẫn theo home
  server/model/FtpUser.java
  client/ClientMain.java             điểm vào Client
  client/controller/                controller giao diện Client
  client/network/FtpClient.java     FTP client bằng Socket
  client/service/TransferQueue.java hàng đợi truyền, ghi đè, tiếp tục
  client/model/                     bản ghi tệp và trạng thái truyền
  client/util/RemotePaths.java
src/main/resources/
  fxml/client.fxml, fxml/server.fxml
  css/mocha.css
  config/application.properties
  database/schema.sql
  images/                           dành cho tài nguyên hình ảnh
```

## 2. Kiến trúc và luồng xử lý

Server có một accept thread và `ExecutorService` cho nhiều phiên. Mỗi `FtpSession` giữ user, cwd, TYPE, điểm cuối active/passive, RNFR, REST và trạng thái truyền riêng. Mỗi phiên có một worker truyền dữ liệu, cho phép control reader tiếp tục nhận ABOR. Giới hạn số phiên bằng `server.max.sessions`.

Client có một executor tuần tự cho kết nối, duyệt thư mục và các phần tử hàng đợi. Không chạy hai giao dịch FTP đồng thời trên cùng control socket. Mô hình `FtpClient` độc lập cho phép mở rộng bằng nhiều session sau này. Các thao tác JDBC, socket và filesystem nằm ngoài JavaFX Application Thread; thuộc tính UI được cập nhật qua `Platform.runLater`.

SQL mỗi thao tác sở hữu connection/statement/result set riêng. Tạo tài khoản dùng transaction commit/rollback. Lỗi xác thực/database ngăn login; lỗi ghi audit sau đó được ghi vào server log. Nếu database mất kết nối giữa phiên, audit có thể thiếu; không có cơ chế lưu bù offline.

## 3. FTP và hai kết nối TCP

**Control connection:** Client kết nối TCP tới cổng 21 của Server (hoặc 2121 khi cấu hình). Control socket sống trong suốt phiên, mang lệnh và phản hồi dạng văn bản UTF-8, kết thúc bằng CRLF. Phản hồi có mã ba chữ số, ví dụ `220`, `331`, `230`, `150`, `226`, `550`. PASS được che trong cả hai log và trong database audit.

**Data connection:** mỗi LIST/MLSD/NLST/RETR/STOR dùng một TCP socket khác, đóng sau giao dịch. Nội dung tệp không đi qua control socket. Server gửi `150` trước khi mở/truyền dữ liệu, `226` chỉ sau khi truyền thành công; thất bại mở kết nối hoặc lỗi truyền có phản hồi lỗi. Tệp được stream với bộ đệm; không nạp toàn bộ tệp vào RAM.

### Passive và Active

| Chế độ | Bên listen dữ liệu | Bên connect dữ liệu | Thương lượng |
|---|---|---|---|
| Passive | Server | Client | PASV (IPv4), EPSV (IPv4/IPv6) |
| Active | Client | Server | PORT (IPv4), EPRT (IPv4/IPv6) |

PASV trả `(h1,h2,h3,h4,p1,p2)`, cổng là `p1*256+p2`. EPSV trả `229 ... (|||port|)`. Passive listener chỉ dùng cho một giao dịch; PASV/EPSV mới thay listener cũ, các listener được đóng khi kết thúc hoặc đóng phiên. Endpoint passive hết hạn sau timeout nếu chưa dùng. Dải passive cấu hình được; khi hết cổng Server trả lỗi.

PORT/EPRT chỉ chấp nhận IP giống IP của control peer và cổng >=1024 để chặn FTP bounce. Server xác minh IP peer của passive data socket. Client active cũng kiểm tra IP Server. Custom Client ưu tiên EPSV và dự phòng PASV, dùng IP của control peer để tránh PASV trả địa chỉ LAN sai hoặc địa chỉ bên thứ ba. Đây là lựa chọn an toàn, phù hợp Server cùng IP control/data trong bài lab.

### TYPE và REST

`TYPE I` truyền nguyên byte: dùng cho ZIP, PDF, ảnh, tài liệu và kiểm tra hash. `TYPE A` chuyển đổi newline theo NVT ASCII: CRLF trên đường truyền, CR đơn dùng CR-NUL; phía nhận lưu newline LF. Không dùng ASCII cho tệp nhị phân. `REST` chỉ hỗ trợ binary; offset là số byte, phải hợp lệ so với tệp hiện có. Sau giao dịch offset bị tiêu thụ; TYPE mới xóa offset.

Upload ghi vào temporary file trong thư mục đích; chỉ publish sau khi nhận xong và flush. Khi hủy/lỗi, xóa temporary file, giữ tệp đích cũ. Resume upload dùng prefix của tệp đích hiện có, không giữ phần upload lỗi trên Server. Download Client dùng `<filename>.ftp-part`; giữ partial khi gián đoạn để thử lại, đổi tên thành tệp đích chỉ khi nhận phản hồi thành công. Khi chọn Resume, người dùng phải bảo đảm đó là cùng phiên bản tệp và prefix giống nhau; REST không xác minh hash prefix.

## 4. Bảng lệnh FTP

| Lệnh | Chức năng / phản hồi tiêu biểu |
|---|---|
| USER / PASS | 331 yêu cầu mật khẩu / 230 login / 530 thất bại |
| QUIT | 221 và đóng phiên |
| SYST / FEAT | 215 UNIX / 211 danh sách tính năng đa dòng |
| PWD / CWD / CDUP | 257 đường dẫn / 250 đổi thư mục |
| TYPE | 200 binary I, L 8 hoặc ASCII A, A N |
| PASV / EPSV | 227 / 229 điểm cuối passive |
| PORT / EPRT | 200 điểm cuối active, 501 nếu vi phạm bounce protection |
| LIST / NLST | Danh sách UNIX / chỉ tên |
| MLSD / MLST | Facts type, size, modify; thời gian UTC |
| RETR / STOR | Download / upload qua data socket |
| DELE / MKD / RMD | Xóa tệp / tạo thư mục / xóa thư mục rỗng |
| RNFR / RNTO | 350 nguồn rename / 250 hoàn thành |
| SIZE / MDTM | 213 kích thước binary / thời gian UTC |
| NOOP | 200 giữ phiên |
| ABOR | Hủy worker/socket: 426 rồi 226; 225 nếu không truyền |
| REST | 350 offset tiếp tục, chỉ TYPE I |
| OPTS | UTF8 ON, lựa chọn MLST facts |
| MODE / STRU | Hỗ trợ S / F; giá trị khác trả 504 |

Có alias XPWD/XMKD/XRMD. Lệnh chưa hỗ trợ trả `502`, yêu cầu login trả `530`, sai thứ tự trả `503`. Không quảng bá FTPS/TLS, AUTH TLS, APPE, đồng bộ thư mục đệ quy hay tính năng không cài đặt. MLST trả tập facts type/size/modify (có thể nhiều hơn facts được yêu cầu). FileZilla có thể phát sinh lệnh bổ sung và nhận 502, sau đó dùng tính năng cơ bản đã quảng bá. Khả năng tương thích thực tế cần xác nhận theo DEMO_GUIDE.

## 5. Chuẩn bị MySQL

Yêu cầu đã cài JDK 21, Maven, MySQL 8; dự án không tự cài phần mềm hay tự tạo database. Từ thư mục dự án, mở MySQL CLI:

```powershell
mysql -u root -p
```

Trong MySQL, thay đường dẫn nếu checkout ở nơi khác:

```sql
SOURCE C:/Users/qank0/IdeaProjects/LAB2/src/main/resources/database/schema.sql;
CREATE USER 'ftp_manager'@'localhost' IDENTIFIED BY 'ChooseYourOwnDbPassword';
GRANT SELECT, INSERT, UPDATE ON ftp_manager.* TO 'ftp_manager'@'localhost';
```

Nếu user đã tồn tại, dùng `ALTER USER 'ftp_manager'@'localhost' IDENTIFIED BY '...';`. Không dùng tài khoản root để chạy ứng dụng. Client không cần truy cập MySQL; MySQL chỉ phục vụ Server.

Schema có `users`, `ftp_sessions`, `transfer_history`, `activity_logs`; utf8mb4, foreign keys, chỉ mục theo user/thời gian. `file_size` trong lịch sử là số byte đã xử lý trong lần truyền (không cộng prefix REST), status RUNNING/COMPLETED/FAILED/CANCELLED. Khi tiến trình bị kill/mất điện, có thể còn bản ghi RUNNING và phiên chưa có disconnected_at; không được hiểu là vẫn đang online.

## 6. Cấu hình

Sửa `src/main/resources/config/application.properties` trước build, hoặc tạo tệp ngoài và đặt biến môi trường:

```powershell
Copy-Item src/main/resources/config/application.properties application-local.properties
$env:FTP_CONFIG = (Resolve-Path application-local.properties).Path
```

Tệp ngoài là bản cấu hình vận hành do người dùng tạo, không phải tài nguyên nguồn mới. Không commit mật khẩu. `AppConfig` đọc mặc định trong classpath, chồng tệp `FTP_CONFIG` hoặc system property `ftp.config`, cuối cùng system property theo từng key.

| Key | Mặc định / ý nghĩa |
|---|---|
| server.bind | 0.0.0.0, lắng nghe các interface IPv4 |
| server.port | 21; có thể đổi 2121 khi phát triển |
| server.passive.min/max | 50000 / 50100 |
| server.passive.address | Rỗng: IP local của control socket; LAN/NAT: IP mà Client truy cập được |
| server.max.sessions | 50 |
| socket.timeout.ms | 60000; control idle và data timeout |
| db.url | JDBC URL MySQL, UTF-8, timeout connect/read |
| db.username/password | Tài khoản MySQL riêng |

Start Server dùng giá trị bind/port/range trên GUI; passive address, session limit, timeout và DB dùng cấu hình. Thay chúng cần khởi động lại ứng dụng. Không cần hardcode localhost cho FTP: nhập IP thật ở Client. `localhost` mặc định chỉ là địa chỉ MySQL trên máy Server.

## 7. IntelliJ và Maven

1. Open thư mục chứa `pom.xml`, chọn **Load Maven Project**.
2. Project SDK và Maven Runner JRE: **JDK 21**.
3. Reload Maven để lấy JavaFX, JDBC, BCrypt, SLF4J.
4. Dùng Maven tool window chạy goal `javafx:run`; profile `server` chọn Server. Cách này tự xử lý JavaFX module path theo nền tảng.
5. Không có `module-info.java`; dự án chạy classpath, FXML controller khai báo tên package đầy đủ.

Các lệnh sau **do người dùng tự chạy**:

```powershell
mvn clean compile
mvn -Pserver javafx:run
```

Trong terminal khác, cùng thư mục dự án:

```powershell
mvn javafx:run
```

Maven profile server chỉ thay main class; không tạo module khác. Nếu dùng cấu hình ngoài, đặt `FTP_CONFIG` trong từng terminal hoặc trong Environment variables của Run Configuration. Trong IntelliJ nên dùng Maven run configuration; chạy trực tiếp Application có thể cần JavaFX module path tùy cấu hình IDE.

## 8. Sử dụng Server

1. Nhập bind `0.0.0.0`, port `2121` hoặc `21`, passive `50000–50100`.
2. Tạo tài khoản: nhập Username, Password (tối thiểu 8 ký tự, tối đa 72 byte UTF-8), Home tuyệt đối, bấm **Create account**. BCrypt được tạo tự động. Không cần sinh hash thủ công.
3. Bấm **Refresh users**, chọn user để Enable/Disable, Change password hoặc Assign home. Change password dùng ô Password; Assign home dùng ô Home. Thay quyền/mật khẩu/home sẽ ngắt các phiên hiện tại của user đó.
4. Bấm **Start Server**. Start kiểm tra schema MySQL rồi mới bind cổng.
5. Dashboard hiển thị clients, transfers, uploaded/downloaded bytes từ lúc mở ứng dụng. Bảng clients hiển thị username, IP, thời gian, lệnh hiện tại; chọn hàng và **Disconnect selected client** để đóng thật socket.
6. **Stop Server** ngừng accept, đóng các phiên/data sockets và shutdown executor. Có thể Start lại.

Home phải được hệ điều hành cho phép đọc/ghi. Server từ chối `..` thoát root, đường dẫn drive/UNC, symlink và đường dẫn canonical ra ngoài root; không cho đổi tên/xóa root hoặc thư mục hiện tại. Không cho người dùng hệ điều hành không tin cậy sửa symlink/junction trong lúc Server đang chạy: việc kiểm tra đường dẫn rồi mở tệp không phải cơ chế chống mọi cuộc đua filesystem từ tiến trình cục bộ. Dùng thư mục home chuyên dụng và ACL phù hợp, không dùng toàn bộ ổ đĩa.

## 9. Sử dụng Client

Nhập Host, Port, Username, Password và **Quick Connect**. Chọn Passive/Active và Binary/ASCII trước khi thêm transfer. Local tree có thể mở nhánh và double-click để đi tới thư mục; ô đường dẫn cho phép nhập trực tiếp. Remote tree/navigation, nút ↑, Refresh và double-click dùng các lệnh FTP thật.

Chọn nhiều **tệp** rồi Upload/Download. Folder được mở để chọn các tệp bên trong; không có upload/download thư mục đệ quy. Context menu hai bảng có transfer, refresh, tạo thư mục, delete, rename và properties. Xóa thư mục yêu cầu rỗng. Hàng đợi lưu cả phần tử thành công/thất bại trong phiên ứng dụng, với progress, tốc độ, byte và trạng thái. Lịch sử bền vững nằm trong MySQL phía Server.

Khi trùng tên, chọn Overwrite, Resume (binary và offset hợp lệ) hoặc Cancel. Resume chỉ dành cho cùng tệp. **Cancel selected** hủy phần tử chờ hoặc đóng socket của phần tử đang truyền. Custom Client gửi ABOR theo kiểu best-effort ở background, reset data socket rồi đóng cả phiên để không để lại phản hồi FTP chưa đọc; lần truyền sau tự login lại bằng thông tin đang giữ trong RAM. Server vẫn hỗ trợ ABOR cho FileZilla và Client khác. **Retry / Resume selected** tạo lượt mới cho phần tử Failed/Cancelled, hỏi cách xử lý nếu có partial. Tệp download thành công chỉ thay tệp cũ sau khi truyền hoàn tất.

Thông báo hoàn thành/lỗi xuất hiện ở status và log; lỗi thao tác hiện dialog. Disconnect hủy các phần tử chưa xong, đóng socket. Tốc độ là lượng byte được stream theo thời gian, cập nhật khoảng 100 ms; không phải phép đo lưu lượng Ethernet. Với ASCII, số byte tiến độ là số đơn vị nguồn đã xử lý, có thể khác kích thước tệp sau chuyển newline.

## 10. Chạy trên hai máy LAN

Máy A chạy MySQL + Server. Máy B chỉ chạy Client; không cần MySQL hoặc mở cổng DB 3306 cho B.

Trên máy A, người dùng có thể chạy:

```powershell
ipconfig
```

Ví dụ IPv4 A là `192.168.1.10`: bind `0.0.0.0`, control `2121`, passive range `50000–50100`, đặt `server.passive.address=192.168.1.10` nếu cần. Máy B nhập Host `192.168.1.10`, Port `2121`.

Trên PowerShell Administrator của A (thay 2121 bằng 21 nếu dùng mặc định):

```powershell
New-NetFirewallRule -DisplayName "FTP Manager Control" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 2121 -Profile Private
New-NetFirewallRule -DisplayName "FTP Manager Passive" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 50000-50100 -Profile Private
```

Passive cần inbound range trên A; Active cần inbound ephemeral data port trên B. Với demo Active, cho phép Java của Client qua Windows Firewall mạng Private, hoặc rule giới hạn IP Server và executable Java thực tế. Không mở toàn bộ cổng cho mạng Public. Với router NAT, forward control và cả range passive tới A, đặt passive.address thành public IPv4; EPSV vẫn dùng IP control. Active qua NAT phía Client cần cấu hình router tương ứng nên Passive thuận tiện hơn.

## 11. FileZilla

Trong Site Manager tạo site: Protocol **FTP**, Encryption **Only use plain FTP (insecure)**, Host IP A, Port tương ứng, Logon Type Normal và user vừa tạo. Transfer Settings chọn Passive; kết nối rồi upload/download/rename/mkdir/delete. Đổi Transfer Settings sang Active để demo PORT/EPRT, bảo đảm firewall Client cho phép data socket. Có thể chọn Binary hoặc ASCII ở Transfer → Transfer type. Không chọn SFTP hoặc bắt buộc FTP over TLS: dự án không có SSH/TLS.

## 12. An toàn và giới hạn thực tế

**FTP chuẩn truyền credentials và dữ liệu không mã hóa. Chỉ dùng mạng lab đáng tin cậy.** BCrypt bảo vệ mật khẩu lưu trong DB; không mã hóa gói tin FTP. Không dùng tài khoản/mật khẩu thật ngoài bài lab.

Mỗi session độc lập; collections/counters dùng concurrent/atomic. Timeout, tối đa phiên, giới hạn độ dài control line, kiểm tra data peer và bounce protection hạn chế lạm dụng cơ bản. Không phải Server production: không có FTPS, quota, rate limit theo IP hoặc phân quyền từng file ngoài ACL/home. Hai session cùng tài khoản có thể ghi cùng tên; tệp hoàn tất sau cùng sẽ thay thế tệp trước. Log/audit chứa tên tệp và đường dẫn, không chứa PASS argument. Tắt ứng dụng đột ngột có thể để temporary upload `.ftp-upload-*.part`; chỉ xóa thủ công khi Server đã dừng.

## 13. Khắc phục lỗi

| Hiện tượng | Kiểm tra |
|---|---|
| JavaFX runtime components missing | Chạy bằng Maven javafx:run; kiểm tra JDK 21, Maven reload |
| Access denied MySQL / Unknown database | Import schema, grants, db.username/password/url; DB đã chạy |
| IDE Unable to resolve table users | IDE chưa gắn datasource/schema; đối chiếu schema.sql, không phải lỗi Java |
| Bind failed / Address in use | Đổi 21 thành 2121; kiểm tra dịch vụ FTP khác, bind IP tồn tại |
| Login thành công nhưng LIST treo | Mở passive range, NAT, đúng chế độ, xem 150/425/426 |
| Active không truyền | Firewall/NAT máy Client, IP PORT/EPRT phải trùng peer |
| 550 đường dẫn | Home/ACL, file tồn tại, folder rỗng, symlink/junction, không thoát root |
| Chữ Việt lỗi | Dùng Client UTF-8; schema utf8mb4; OPTS UTF8 ON |
| ZIP/PDF hỏng | Chọn TYPE I; không truyền nhị phân bằng ASCII |
| Resume bị từ chối | TYPE I, offset <= kích thước, cùng phiên bản tệp; partial upload lỗi đã bị xóa theo thiết kế |
| 421 khi để lâu | Control idle timeout; Quick Connect lại |
| Server audit lỗi | Kiểm tra DB; thông báo nằm trong Server log |

## 14. Vị trí mã cho vấn đáp

1. TCP accept và đa client: `server/core/FtpServer.java`.
2. State machine lệnh, reply sequencing, ABOR và REST: `server/handler/FtpSession.java`.
3. Active/passive: `passive`, `active`, `openData` trong FtpSession; `prepare`, `accept` trong FtpClient.
4. CRLF, multiline replies và NVT ASCII: `common/protocol/FtpWire.java`.
5. Sandboxing và symlink: `server/security/Sandbox.java`.
6. Prepared statements, BCrypt, transactions: `server/database/Database.java`.
7. UI không bị block, context menus: `client/controller/ClientController.java`.
8. Progress, cancel, retry, temporary download: controller và `client/service/TransferQueue.java`.
9. FXML/CSS: `src/main/resources/fxml/`, `css/mocha.css`.
10. SQL quan hệ và index: `src/main/resources/database/schema.sql`.

Xem [DEMO_GUIDE.md](DEMO_GUIDE.md) cho thứ tự demo và các lệnh kiểm tra do người dùng tự thực hiện.
