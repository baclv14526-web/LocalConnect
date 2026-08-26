# LocalConnect

Ứng dụng Android (Kotlin) cho **nhóm tối đa 5 người** liên lạc qua **Wi-Fi hotspot băng tần 2.4GHz nội bộ, không cần Internet**:

- 🔎 **Tự động tìm nhau** trong mạng bằng Network Service Discovery (mDNS) — mở app là thấy nhau, không cần nhập IP.
- 💬 **Chat nhóm** (tối đa 5 người) + **chat riêng 1-1**, lưu lịch sử bằng Room (SQLite), gửi qua kênh TCP nội bộ.
- 📎 **Gửi/nhận file** (ảnh, video, tài liệu...) qua kênh TCP riêng, lưu vào `Downloads/LocalConnect`.
- 📞 **Gọi thoại** và 🎥 **gọi video 1-1** bằng WebRTC — chạy trực tiếp LAN, **không cần STUN/TURN/Internet** vì mọi máy đã cùng subnet của hotspot.
- 🔔 Chạy nền ổn định nhờ Foreground Service, tự kết nối lại khi có người mới vào mạng.

## Giới hạn có chủ đích (thực tế, tránh nổ app)
- Gọi video/thoại là **1-1**, không phải mesh video nhóm 5 người cùng lúc (mesh WebRTC 5 chiều rất nặng CPU/băng thông trên điện thoại tầm trung). Chat nhóm và gửi file thì đúng cho **cả 5 người cùng lúc**.
- File được đọc vào RAM khi gửi (phù hợp file vài chục–vài trăm MB trong LAN nội bộ); nếu cần gửi file rất lớn (nhiều GB) nên tách stream, có thể nâng cấp sau.

## Cấu trúc dự án
```
app/src/main/java/com/localconnect/app/
  net/     -> NSD discovery, TCP ConnectionManager, ConnectionService (foreground), FileTransferManager
  call/    -> CallManager (WebRTC), CallActivity (UI gọi thoại/video)
  data/    -> Room database (lịch sử chat)
  model/   -> Peer, WireMessage (giao thức JSON qua TCP), MessageType
  ui/      -> Màn hình Compose: danh sách người dùng, chat
```

**Giao thức mạng**: TCP cổng `8988`, mỗi gói tin là JSON đóng khung bằng 4 byte độ dài (length-prefixed). File và tín hiệu cuộc gọi WebRTC (SDP/ICE) cũng đi qua kênh này, riêng dữ liệu file thực tế đi qua 1 cổng TCP tạm mở riêng để không lẫn với JSON.

## Build & chạy thử bằng Android Studio
1. Mở thư mục này bằng Android Studio (Hedgehog trở lên).
2. Đợi Gradle sync xong (lần đầu cần Internet để tải dependency).
3. Kết nối 2+ điện thoại vào **cùng một điểm phát Wi-Fi hotspot 2.4GHz**, cài & mở app trên từng máy.
4. Cấp đủ quyền Camera/Micro/Thông báo khi được hỏi.
5. Ở danh sách, các máy khác sẽ tự xuất hiện sau vài giây (mDNS).

## Build APK ký sẵn bằng GitHub Actions (không cần máy tính có Android Studio)
Repo đã có sẵn `.github/workflows/build.yml`:

1. Đẩy (push) toàn bộ project này lên một repo GitHub của bạn.
2. Vào tab **Actions** → workflow **"Build & Sign LocalConnect APK"** sẽ tự chạy (hoặc bấm **Run workflow** để chạy tay).
3. Khi chạy xong, vào **Summary** của lần chạy, tải artifact:
   - `LocalConnect-release-apk` → file `app-release.apk`, **copy vào điện thoại Android 9 (API 28) trở lên rồi cài trực tiếp** (bật "Cài từ nguồn không xác định" nếu được hỏi).
   - `generated-release-keystore-KEEP-SAFE` → chỉ xuất hiện ở **lần build đầu tiên** khi bạn chưa cấu hình Secrets. **Tải file `release.jks` này về và giữ an toàn** — nó là chữ ký của app.

### (Khuyến nghị) Giữ cố định 1 chữ ký cho các lần build sau
Nếu không làm bước này, **mỗi lần build workflow sẽ tự sinh keystore MỚI** → APK bản sau sẽ không cài đè được lên bản trước (Android chặn vì khác chữ ký), phải gỡ app cũ rồi cài lại. Để tránh việc đó:

1. Sau lần build đầu tiên, tải artifact `generated-release-keystore-KEEP-SAFE` (file `release.jks`).
2. Chuyển file đó sang base64:
   ```bash
   base64 -w0 release.jks > release.jks.base64
   ```
3. Vào repo GitHub → **Settings → Secrets and variables → Actions → New repository secret**, tạo 4 secrets:
   - `RELEASE_KEYSTORE_BASE64` = nội dung file `release.jks.base64`
   - `RELEASE_KEYSTORE_PASSWORD` = `localconnect123` (hoặc mật khẩu bạn tự đặt nếu tự tạo keystore riêng bằng `keytool`)
   - `RELEASE_KEY_ALIAS` = `localconnect`
   - `RELEASE_KEY_PASSWORD` = `localconnect123`
4. Từ lần build sau, workflow sẽ dùng lại đúng keystore này → mọi bản APK cùng chữ ký, cài đè lên nhau bình thường.

## Cài đặt trên điện thoại
- Yêu cầu **Android 9 (API 28) trở lên**.
- Vì là app tự ký (không phải từ Google Play/Play Protect), khi cài lần đầu Android sẽ cảnh báo "nguồn không xác định" — vào **Cài đặt → Bảo mật → Cho phép cài từ nguồn này** rồi cài tiếp.
- Cài trên cả 5 máy, cùng nối vào 1 hotspot Wi-Fi 2.4GHz, mở app lên là dùng được — không cần cấu hình IP thủ công.

## Xin quyền khi mở app lần đầu
App sẽ hỏi quyền: Camera, Micro, Thông báo (Android 13+), quyền truy cập file media (để gửi/nhận file). Cần đồng ý đủ để gọi video/thoại và gửi file hoạt động.
