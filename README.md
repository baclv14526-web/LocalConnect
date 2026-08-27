# LocalConnect

Ứng dụng Android (Kotlin) cho **nhóm tối đa 5 người** liên lạc qua **Wi-Fi Direct**, không cần Internet, không cần bật Điểm phát Wi-Fi (Hotspot) thủ công:

- 🔗 **Tự tạo mạng bằng Wi-Fi Direct** (`WifiP2pManager`) — 1 máy "Tạo nhóm", các máy khác "Tìm & tham gia". Không phụ thuộc tính năng cô lập client (AP isolation) mà nhiều hãng (Samsung, Xiaomi...) bật mặc định trên Hotspot chia sẻ mạng thường.
- 💬 **Chat nhóm** (tối đa 5 người) + **chat riêng 1-1**, lưu lịch sử bằng Room (SQLite).
- 📎 **Gửi/nhận file** (ảnh, video, tài liệu...) qua kênh TCP riêng, lưu vào `Downloads/LocalConnect`.
- 📞 **Gọi thoại** và 🎥 **gọi video 1-1** bằng WebRTC — chạy trực tiếp trong nhóm Wi-Fi Direct, không cần STUN/TURN/Internet.
- 🔔 Chạy nền ổn định nhờ Foreground Service.

## Vì sao chuyển sang Wi-Fi Direct thay vì Hotspot thường?
Điểm phát Wi-Fi (tethering) trên nhiều máy Android (đặc biệt Samsung) **mặc định bật "cô lập client"** — 2 máy cùng nối vào hotspot đó **không thể nói chuyện trực tiếp với nhau**, chỉ máy chủ hotspot mới ra được Internet. Đây là giới hạn phần cứng/driver Wi-Fi, không sửa được từ phía app. Wi-Fi Direct là một API khác hẳn, được thiết kế riêng cho việc các thiết bị nói chuyện trực tiếp (dùng cho Miracast, share file...) nên không bị áp isolation.

## Cách hoạt động của mạng (kỹ thuật)
1. **1 người bấm "Tạo nhóm"** → máy đó trở thành *Group Owner* (GO) của Wi-Fi Direct.
2. **Người khác bấm "Tìm nhóm gần đây"** → thấy máy GO trong danh sách quét được → chạm vào để tham gia.
3. Theo đúng bản chất Wi-Fi Direct, **GO luôn kết nối trực tiếp được tới mọi client** — đây là điều được đảm bảo, không phụ thuộc cài đặt nào.
4. GO đóng vai trò "bảng tin": mỗi khi có client mới, GO gửi broadcast danh sách (id/tên/IP) của mọi người trong nhóm (message `PEER_LIST`) cho cả nhóm. Ai nhận được sẽ tự động thử nối TCP trực tiếp tới từng người chưa có kết nối → theo thời gian mạng tự hình thành **full-mesh** (ai cũng nối thẳng ai).
5. Nếu 2 client nào đó (hiếm) không nối thẳng được nhau, **GO tự động relay (chuyển tiếp) hộ** toàn bộ tin nhắn/tín hiệu — nên chat/gọi/gửi file vẫn hoạt động dù không có đường nối trực tiếp.
6. Vẫn còn nút **"Kết nối bằng IP"** để nối thủ công trong trường hợp cần debug hoặc dự phòng.

## Giới hạn có chủ đích (thực tế, tránh nổ app)
- Gọi video/thoại là **1-1**, không phải mesh video nhóm 5 người cùng lúc (mesh WebRTC 5 chiều rất nặng CPU/băng thông trên điện thoại tầm trung). Chat nhóm và gửi file thì đúng cho **cả 5 người cùng lúc**.
- File được đọc vào RAM khi gửi (phù hợp file vài chục–vài trăm MB); nếu cần gửi file rất lớn (nhiều GB) nên tách stream, có thể nâng cấp sau.
- Nếu người "Tạo nhóm" (Group Owner) thoát app/rời nhóm, cả nhóm sẽ mất kết nối và cần tạo lại nhóm (đúng bản chất kiến trúc star topology qua GO).

## Cấu trúc dự án
```
app/src/main/java/com/localconnect/app/
  net/     -> WifiDirectManager (hình thành nhóm), ConnectionManager (TCP + roster + relay),
              ConnectionService (foreground), FileTransferManager
  call/    -> CallManager (WebRTC), CallActivity (UI gọi thoại/video)
  data/    -> Room database (lịch sử chat)
  model/   -> Peer, WireMessage (giao thức JSON qua TCP), MessageType
  ui/      -> Màn hình Compose: WifiDirectSetupScreen (tạo/tham gia nhóm), danh sách người dùng, chat
```

**Giao thức mạng**: TCP cổng `8988`, mỗi gói tin là JSON đóng khung bằng 4 byte độ dài (length-prefixed). File và tín hiệu cuộc gọi WebRTC (SDP/ICE) cũng đi qua kênh này, riêng dữ liệu file thực tế đi qua 1 cổng TCP tạm mở riêng để không lẫn với JSON.

## Cách dùng trên điện thoại (5 người)
1. **1 người** mở app → bấm **"Tạo nhóm (làm chủ nhóm)"**.
2. **4 người còn lại** mở app → bấm **"Tìm nhóm gần đây"** → đợi vài giây → chạm vào tên máy của người vừa tạo nhóm ở bước 1 để tham gia.
3. Sau khi tham gia, danh sách người trong nhóm sẽ tự xuất hiện — chọn để chat riêng, hoặc vào "Chat nhóm", hoặc bấm nút gọi thoại/gọi video.
4. Cần đồng ý các quyền được hỏi: Vị trí (Android ≤12) hoặc Wi-Fi lân cận (Android 13+, **chỉ dùng để quét Wi-Fi Direct, KHÔNG dùng để định vị**), Camera, Micro, Thông báo.

## Build & chạy thử bằng Android Studio
1. Mở thư mục này bằng Android Studio (Hedgehog trở lên).
2. Đợi Gradle sync xong (lần đầu cần Internet để tải dependency).
3. Cài & mở app trên 2+ điện thoại (bật Wi-Fi trên cả 2, không cần nối chung mạng nào).
4. Làm theo phần "Cách dùng trên điện thoại" ở trên.

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
- Cài trên cả 5 máy. **Không cần bật Điểm phát Wi-Fi/Hotspot gì cả** — chỉ cần bật Wi-Fi, app sẽ tự tạo mạng Wi-Fi Direct riêng.
