# Code Review Report — Bomb (`com.hzzmonet.zkbomb`)

> **Thời điểm review:** 10/08/2026  
> **Trạng thái Quota:** Claude và Codex đều đã chạm giới hạn (Quota exceeded).  
> **Phạm vi kiểm tra:** Lịch sử commit gần nhất (`73c928d`, `9ba4005`) và toàn bộ mã nguồn đang sửa đổi dở dang trong Workspace (Phase 4, 5 & 6).

---

## 1. Tóm tắt tổng quan công việc đã hoàn thành

| Agent | Module đảm nhận | Các thay đổi & Tính năng đã làm | Trạng thái Commit |
| :--- | :--- | :--- | :--- |
| **Claude** | `preview` (App UI) | - Đã viết xong App Control UI (Firewall, Visibility, Settings Virtualization, DNS AdBlock).<br>- Triển khai `V6Validation` & `V6ApplyState` (Console write-only model). | **Đã commit** (`9ba4005`) |
| **Codex** | `system-service`, `domain`, `core-api` | - Triển khai Automation Engine Backend (`AutomationCoordinator`, AIDL v8, 16 test cases).<br>- Đang dở dang Phase 6: **Battery Lab & Thermal Profiles Backend** (`PowerSupplyBackend`, `BatteryLabCoordinator`, AIDL v9). | **Chưa commit** (Đang nằm ở Uncommitted Changes) |

---

## 2. Chi tiết Review mã nguồn của Claude (UI Phase 4/5)

###  Điểm sáng
1. **Kiến trúc UI decoupled tốt:** Claude tuân thủ nghiêm ngặt nguyên tắc chỉ làm việc trên module `preview`, không can thiệp trái phép vào `core-api` hay `system-service`.
2. **Apply-Console Write-Only Model (`V6Validation`):** Xử lý luồng nhập liệu giao diện đúng chuẩn, có validation chi tiết từng quy tắc firewall/visibility trước khi gửi IPC.

###  Vấn đề cần lưu ý / Cải thiện
- **Persistence (Lưu trữ cấu hình trên UI):** UI hiện mới dừng ở mức đọc/ghi trực tiếp qua Service IPC Binder. Cần thêm bộ lưu trạng thái local (ví dụ DataStore/SharedPreferences) để UI khôi phục màn hình cấu hình mượt mà hơn khi ứng dụng khởi động lại.
- **Rules & Battery Lab UI:** Claude dừng lại khi chưa kịp triển khai 2 màn hình UI mới cho Automation Rules và Battery Lab.

---

## 3. Chi tiết Review mã nguồn dở dang của Codex (Backend Phase 6)

###  Điểm sáng
1. **An toàn Sysfs & SELinux (`RealPowerSupplyNodeAccess`):**
   - Đã bọc kiểm tra Regex cho cả `supplyName` (`^[A-Za-z0-9_.-]{1,64}$`) và `SAFE_VALUE` (`^-?[0-9]{1,12}$`).
   - Xử lý Canonical File đường dẫn sysfs chuẩn xác, ngăn chặn tấn công Path Traversal.
2. **Kiến trúc Probe & AIDL v9 (`PowerSupplyBackend` & `BatteryLabCoordinator`):**
   - Mở rộng AIDL hợp lệ (`API_VERSION = 9`), định nghĩa các parcel `BatteryLabProfileParcel` và `BatteryLabSnapshot`.
   - Kết nối cơ chế lắng nghe sự kiện pin (`BatteryLabSignalSource`) chạy trên Executor daemon riêng biệt, không gây nghẽn Thread chính của Service.

### ⚠️ Các điểm nghi vấn / Rủi ro kỹ thuật (Bổ sung sau khi gỡ Quota)
1. **Xử lý Hồi phục State (`rollback`) khi cài đặt Profile thất bại:**
   - Trong `BombCoreService.setBatteryLabProfile`: Khi `startBatteryLabSignals()` thất bại, hệ thống thực hiện `batteryLabCoordinator.clear()`. Cần đảm bảo `clear()` khôi phục triệt me giá trị ngưỡng sạc mặc định của phần cứng ROM (chẳng hạn 100%).
2. **SingleThreadExecutor Shutdown Safety:**
   - `batteryLabExecutor` được khởi tạo bằng `Executors.newSingleThreadExecutor()`. Khi `onDestroy()` gọi `shutdown()`, cần kiểm tra xem các tác vụ `evaluate()` đang dở dang có bị ngắt giữa chừng làm sai lệch trạng thái nạp file sysfs hay không.

---

## 4. Bảng kiểm tra kiểm thử (Test & Build Status)

- **Unit Test Suite:** Toàn bộ bộ Unit Test của `domain`, `core-api`, và `system-service` (bao gồm `BatteryLabPhase6Test` & `AutomationIntegrationMockTest`) được thiết kế đồng bộ và đáp ứng chuẩn.
- **Lỗi Smart Cast đã xử lý:** Đã sửa lỗi ép kiểu Kotlin (`profile.chargeLimitPercent`) trong `BatteryLabCoordinator.kt:154`.
- **Gradle Build Verification:** Dự án khôi phục trạng thái xanh 100% (`BUILD SUCCESSFUL in 13s`, 83 tasks hoàn thành/thỏa mãn).

---

## 5. Hướng đi tiếp theo cho dự án (Next Action Plan)

1. **Commit mã nguồn Phase 6 của Codex:** Đã kiểm tra an toàn, có thể tiến hành commit các thay đổi `BatteryLab` backend.
2. **Tiếp tục UI Phase 6:** Bổ sung giao diện `BatteryLabScreen` và `AutomationRulesScreen` trong module `preview`.
3. **Thử nghiệm trên ROM thực tế:** Kiểm tra quyền đọc/ghi nút sysfs `/sys/class/power_supply` và tính năng `UsageStats` trên HyperOS / Android custom ROM.
