# ArtNest Mobile App - Flutter Frontend

Frontend mobile cho ứng dụng **ArtNest** (Học vẽ & Dụng cụ mỹ thuật) được xây dựng bằng **Flutter & Dart**, tích hợp 100% các REST API thực tế từ backend Spring Boot (`com.artacademy`), đồng thời tái hiện trung thực thiết kế từ Figma.

---

## 1. Cấu trúc thư mục Feature-First

```
FE/
├── docs/
│   └── api_mapping.md             # Tài liệu rà soát toàn bộ endpoint backend & mapping
├── lib/
│   ├── core/
│   │   ├── constants/             # AppColors, AppTextStyles, ApiConstants
│   │   ├── network/               # ApiClient (Dio), ApiException, Interceptor, Token Refresh
│   │   ├── router/                # AppRouter (GoRouter với Auth Guard & Admin Guard)
│   │   ├── storage/               # TokenStorage (FlutterSecureStorage)
│   │   └── theme/                 # AppTheme Material 3 chuẩn ArtNest
│   ├── features/
│   │   ├── address/               # Quản lý địa chỉ giao hàng (CRUD & Default)
│   │   ├── admin/                 # Quản trị người dùng (Admin)
│   │   ├── auth/                  # Đăng nhập, Đăng ký, Quên mật khẩu dialog
│   │   ├── home/                  # HomeTab (Figma), CoursesTab, StoreTab, FavoritesTab, MainScreen
│   │   ├── profile/               # Hồ sơ cá nhân, Chỉnh sửa thông tin, Đổi mật khẩu
│   │   └── splash/                # Splash screen điều hướng tự động
│   ├── models/                    # ApiResponse, AuthResponse, UserModel, AddressModel, PageResponse
│   ├── providers/                 # AuthProvider, AddressProvider, AdminProvider (ChangeNotifier)
│   ├── repositories/              # AuthRepository, UserRepository, AddressRepository, AdminRepository
│   ├── widgets/                   # ArtNestLogo, CustomButton, CustomTextField, StatusBadge, ServerConfigDialog
│   └── main.dart                  # Khởi chạy MultiProvider & GoRouter
├── test/
│   └── widget_test.dart           # Unit test cho model, DTO và ApiResponse
└── pubspec.yaml
```

---

## 2. Danh mục chức năng & Kết nối API Backend thực tế

| Chức năng | Phương thức | Endpoint | Ghi chú & Logic Backend |
|---|:---:|---|---|
| **Đăng ký tài khoản** | `POST` | `/api/auth/register` | Tự động gán quyền `USER`. Validation mật khẩu 8-72 ký tự (chữ hoa, chữ thường, số). |
| **Đăng nhập** | `POST` | `/api/auth/login` | Nhận `accessToken` & `refreshToken`. Lưu an toàn vào `flutter_secure_storage`. |
| **Làm mới token** | `POST` | `/api/auth/refresh` | Cơ chế Stateless JWT Token Rotation (`TokenType.REFRESH`), không phụ thuộc bảng entity trong database. |
| **Đăng xuất** | `POST` | `/api/auth/logout` | Client xóa token cục bộ (Stateless authentication). |
| **Lấy tài khoản hiện tại**| `GET` | `/api/auth/me` & `/api/users/me` | Đồng bộ dữ liệu người dùng khi mở ứng dụng. |
| **Chỉnh sửa hồ sơ** | `PUT` | `/api/users/me` | Cập nhật họ tên, số điện thoại, avatar URL. |
| **Đổi mật khẩu** | `PUT` | `/api/users/me/password` | Kiểm tra mật khẩu cũ, mật khẩu mới khác mật khẩu cũ, thu hồi mọi token cũ. |
| **Danh sách địa chỉ** | `GET` | `/api/users/me/addresses` | Lấy danh sách địa chỉ nhận hàng của học viên. |
| **Thêm địa chỉ mới** | `POST` | `/api/users/me/addresses` | Địa chỉ đầu tiên được backend tự động gán làm mặc định. |
| **Sửa địa chỉ** | `PUT` | `/api/users/me/addresses/{id}` | Cập nhật thông tin người nhận, địa chỉ chi tiết. |
| **Xóa địa chỉ** | `DELETE` | `/api/users/me/addresses/{id}` | Xóa địa chỉ khỏi tài khoản. |
| **Đặt địa chỉ mặc định** | `PATCH` | `/api/users/me/addresses/{id}/default` | Thiết lập địa chỉ ưu tiên khi mua họa cụ. |
| **Admin: Danh sách user** | `GET` | `/api/admin/users` | Phân trang, tìm kiếm theo tên/email/sđt, lọc theo vai trò và trạng thái active. |
| **Admin: Chi tiết user** | `GET` | `/api/admin/users/{id}` | Xem chi tiết thông tin tài khoản người dùng bất kỳ. |
| **Admin: Khóa/Mở tài khoản**| `PATCH` | `/api/admin/users/{id}/status` | Khóa hoặc kích hoạt tài khoản (chặn vô hiệu hóa admin cuối cùng). |

> **Lưu ý về Quên mật khẩu**: Mã nguồn backend có lớp `EmailService.sendPasswordResetEmail()`, tuy nhiên `AuthController.java` chưa mở endpoint công khai `/api/auth/forgot-password` hay `/api/auth/reset-password`. Ứng dụng đã xử lý minh bạch qua `ForgotPasswordDialog` hướng dẫn học viên liên hệ email quản trị hoặc đổi mật khẩu trong mục Quản lý tài khoản, không tự tạo endpoint giả mạo.

---

## 3. Hướng dẫn chạy ứng dụng

### 3.1. Chạy Backend Spring Boot
1. Mở thư mục `drawingCourseAPI` trong IntelliJ IDEA hoặc Android Studio.
2. Cấu hình file `.env` hoặc `application.yaml` để kết nối MySQL.
3. Chạy lệnh:
   ```bash
   mvn spring-boot:run
   ```
   Server chạy tại cổng `8080`.

### 3.2. Cấu hình Base URL trên Frontend
- **Android Emulator**: Sử dụng `http://10.0.2.2:8080` (Mặc định).
- **Điện thoại thật**: Sử dụng IP mạng LAN máy tính, ví dụ: `http://192.168.1.15:8080`.
- Có thể đổi Base URL trực tiếp trong app bằng cách nhấn vào biểu tượng **Cài đặt / Cấu hình server** trên thanh AppBar của màn hình Đăng nhập hoặc Trang Cá nhân.

### 3.3. Chạy Frontend Flutter
1. Mở thư mục `FE` trong Android Studio hoặc VS Code.
2. Cài đặt dependencies:
   ```bash
   flutter pub get
   ```
3. Kiểm tra code:
   ```bash
   flutter analyze
   ```
4. Chạy unit tests:
   ```bash
   flutter test
   ```
5. Khởi chạy ứng dụng:
   ```bash
   flutter run
   ```
