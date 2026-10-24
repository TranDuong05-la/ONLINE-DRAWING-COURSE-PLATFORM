# ArtNest API Mapping & Endpoint Architecture Document

Tài liệu rà soát toàn bộ source code backend Spring Boot (`com.artacademy`), phân loại endpoints, chi tiết Request/Response DTO, quy tắc validation, xử lý lỗi và mapping trực tiếp sang màn hình Flutter Frontend.

---

## 1. Tổng quan hệ thống & Cấu hình môi trường

- **Backend Framework**: Spring Boot 3.x, Spring Security 6 (Stateless JWT), Spring Data JPA, Hibernate, MySQL.
- **Port mặc định**: `8080` (qua cấu hình `server.port: ${SERVER_PORT:8080}`).
- **Base URL kết nối từ Flutter**:
  - Android Emulator: `http://10.0.2.2:8080`
  - Thiết bị thật qua mạng LAN: `http://<IP_MAY_TINH>:8080` (ví dụ `http://192.168.1.x:8080`)
  - Web / Desktop: `http://localhost:8080`
- **Thời hạn Token**:
  - Access Token: `900,000 ms` (15 phút), định dạng `Bearer <token>`.
  - Refresh Token: `604,800,000 ms` (7 ngày), cơ chế xoay vòng token (Token Rotation).
- **Cấu trúc Response chuẩn (`ApiResponse<T>`)**:
  ```json
  {
    "success": true,
    "message": "Thông báo từ server",
    "data": { ... },
    "errors": [
      {
        "field": "tên_trường",
        "message": "thông báo lỗi"
      }
    ],
    "timestamp": "2026-10-24T00:00:00"
  }
  ```

---

## 2. Bảng tổng hợp Endpoint Mapping sang Màn hình Flutter

| STT | HTTP Method | Endpoint | Xác thực (Auth) | Phân quyền | Màn hình Flutter tương ứng | Repository & Service | Ghi chú nghiệp vụ |
|:---:|:-----------:|:---------|:---------------:|:----------:|:---------------------------|:---------------------|:-------------------|
| 1 | `POST` | `/api/auth/register` | Không (Public) | Mọi người | `RegisterScreen` (`/register`) | `AuthRepository.register()` | Role mặc định luôn là `USER`. Tự động đăng nhập và lưu token sau khi đăng ký. |
| 2 | `POST` | `/api/auth/login` | Không (Public) | Mọi người | `LoginScreen` (`/login`) | `AuthRepository.login()` | Nhận `accessToken`, `refreshToken`, `user`. Lưu vào `flutter_secure_storage`. |
| 3 | `POST` | `/api/auth/refresh` | Không (Public) | Mọi người | Background Interceptor | `AuthRepository.refreshToken()` | Tự động kích hoạt khi nhận mã HTTP 401. Refresh Token hoạt động theo cơ chế Stateless JWT (`TokenType.REFRESH`), không lưu bảng entity trong DB. |
| 4 | `POST` | `/api/auth/logout` | Có (`Bearer`) | Đã đăng nhập | `ProfileScreen` / Settings | `AuthRepository.logout()` | Client hủy lưu trữ token cục bộ (Stateless token). |
| 5 | `GET` | `/api/auth/me` | Có (`Bearer`) | Đã đăng nhập | Khởi động App / `SplashScreen` | `AuthRepository.getCurrentUser()` | Xác thực phiên đăng nhập hiện tại và đồng bộ profile người dùng. |
| 6 | `GET` | `/api/users/me` | Có (`Bearer`) | Đã đăng nhập | `ProfileScreen` (`/profile`) | `UserRepository.getProfile()` | Lấy thông tin cá nhân đầy đủ: họ tên, email, sđt, avatar, role, ngày tạo. |
| 7 | `PUT` | `/api/users/me` | Có (`Bearer`) | Đã đăng nhập | `EditProfileScreen` (`/profile/edit`) | `UserRepository.updateProfile()` | Cho phép cập nhật `fullName`, `phone`, `avatarUrl`. Không thể sửa email hay role. |
| 8 | `PUT` | `/api/users/me/password` | Có (`Bearer`) | Đã đăng nhập | `ChangePasswordScreen` (`/profile/change-password`) | `UserRepository.changePassword()` | Xác minh mật khẩu cũ, kiểm tra khớp mật khẩu mới, thu hồi mọi refresh token cũ. |
| 9 | `GET` | `/api/users/me/addresses` | Có (`Bearer`) | Đã đăng nhập | `AddressListScreen` (`/profile/addresses`) | `AddressRepository.getAddresses()` | Danh sách địa chỉ nhận hàng, sắp xếp theo mặc định và thời gian tạo mới nhất. |
| 10 | `POST` | `/api/users/me/addresses` | Có (`Bearer`) | Đã đăng nhập | `AddEditAddressScreen` (`/profile/addresses/add`) | `AddressRepository.createAddress()` | Thêm địa chỉ mới. Địa chỉ đầu tiên tự động gán làm mặc định. |
| 11 | `PUT` | `/api/users/me/addresses/{id}` | Có (`Bearer`) | Đã đăng nhập | `AddEditAddressScreen` (`/profile/addresses/edit`) | `AddressRepository.updateAddress()` | Cập nhật thông tin địa chỉ cụ thể theo `id`. |
| 12 | `DELETE` | `/api/users/me/addresses/{id}` | Có (`Bearer`) | Đã đăng nhập | `AddressListScreen` | `AddressRepository.deleteAddress()` | Xóa địa chỉ theo `id`. Có hộp thoại xác nhận. |
| 13 | `PATCH` | `/api/users/me/addresses/{id}/default` | Có (`Bearer`) | Đã đăng nhập | `AddressListScreen` | `AddressRepository.setDefaultAddress()` | Đặt địa chỉ làm mặc định, hủy cờ mặc định của các địa chỉ khác. |
| 14 | `GET` | `/api/admin/users` | Có (`Bearer`) | Chỉ `ADMIN` | `AdminUserListScreen` (`/admin/users`) | `AdminRepository.getAllUsers()` | Phân trang người dùng, tìm kiếm theo tên/email/sđt, lọc theo vai trò và trạng thái. |
| 15 | `GET` | `/api/admin/users/{id}` | Có (`Bearer`) | Chỉ `ADMIN` | `AdminUserDetailScreen` (`/admin/users/:id`) | `AdminRepository.getUserById()` | Xem thông tin chi tiết tài khoản của bất kỳ user nào trong hệ thống. |
| 16 | `PATCH` | `/api/admin/users/{id}/status` | Có (`Bearer`) | Chỉ `ADMIN` | `AdminUserDetailScreen` | `AdminRepository.updateUserStatus()` | Kích hoạt hoặc vô hiệu hóa tài khoản (chặn không cho vô hiệu hóa admin cuối cùng). |

---

## 3. Rà soát chuyên sâu các chức năng đặc biệt

### 3.1. Quên mật khẩu & Gửi OTP / Email (Password Reset Flow)
- **Tình trạng rà soát Backend**:
  - Lớp `com.artacademy.service.EmailService` đã có sẵn các phương thức `sendPasswordResetEmail()`, `sendWelcomeEmail()`, `sendCertificateEmail()`, `sendNotificationEmail()` với Thymeleaf template.
  - **TUY NHIÊN**, trong `AuthController.java` và toàn bộ mã nguồn backend **chưa hề khai báo endpoint** công khai như `/api/auth/forgot-password` hay `/api/auth/reset-password`.
- **Giải pháp Frontend tuân thủ quy tắc**:
  - Tuyệt đối **không tạo endpoint giả** (như tự gán URL `/api/auth/forgot-password` mà server không hỗ trợ).
  - Trên màn hình `LoginScreen`, khi bấm "Quên mật khẩu?", ứng dụng mở `ForgotPasswordDialog` hoặc điều hướng sang trang thông tin hỗ trợ, giải thích rõ cơ chế: tài khoản cần liên hệ ban quản trị hoặc chức năng gửi mã reset tự động đang ở giai đoạn tích hợp backend, đồng thời hướng dẫn học viên đổi mật khẩu trong mục Quản lý tài khoản nếu đã đăng nhập.

### 3.2. Cơ chế Phân quyền (RBAC - Role Based Access Control)
- Backend định nghĩa 2 quyền trong `Role`: `USER` và `ADMIN`.
- Đăng ký tài khoản mặc định gán `USER`.
- Các endpoint `/api/admin/**` được bảo vệ nghiêm ngặt bằng Spring Security `.hasRole("ADMIN")`.
- Ứng dụng Flutter:
  - Nếu người dùng đăng nhập có `role == Role.ADMIN`, menu "Quản trị người dùng (Admin)" sẽ hiển thị trong trang Cá nhân / Cài đặt.
  - Nếu người dùng là `USER` thông thường, mục này hoàn toàn bị ẩn và các route `/admin/**` bị chặn bởi Router Guard.
