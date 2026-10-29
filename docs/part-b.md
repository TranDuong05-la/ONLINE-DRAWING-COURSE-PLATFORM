# Phần B trên nhánh `feature/course`

Khóa học, module, bài học, video học thử, quà tặng kèm, combo, quản trị khóa học và upload file.
Nhánh này = `main` + phần B + các chỉnh sửa nhỏ vào file dùng chung. Bảng endpoint ↔ màn hình: [part-b-api.md](part-b-api.md).

## Chạy thử không cần MySQL
```powershell
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=h2"      # nếu cổng 8080 bị chiếm: thêm "-Dspring-boot.run.arguments=--server.port=8081"
cd ..\mobile
flutter pub get
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8081      # bỏ --dart-define nếu backend ở 8080
```
Profile `h2` nạp khóa học mẫu và 2 tài khoản mẫu: `admin@artnest.vn` (ADMIN), `hocvien@artnest.vn` (USER); mật khẩu trong `application-h2.yaml` (chỉ dùng khi dev).
Ô đăng nhập của app chỉ nhận email có đuôi tên miền 2-4 ký tự, nên email mẫu dùng `.vn`.

## File dùng chung mà phần B có sửa
| File | Thêm gì |
|---|---|
| `backend/.../config/SecurityConfig.java` | `GET /api/courses/**`, `/api/bundles/**`, `/uploads/**` công khai (đặt trước rule `/api/admin/**` = ADMIN) |
| `backend/.../exception/GlobalExceptionHandler.java` | xử lý lỗi 400/404/405/413/415 và lỗi riêng của B |
| `backend/src/main/resources/application.yaml` | `spring.servlet.multipart` 200MB, `app.upload.dir`, `app.seed.*` |
| `backend/pom.xml` | H2 đổi scope `test` → `runtime` (cho profile `h2`) |
| `mobile/pubspec.yaml` (+ `pubspec.lock`) | `video_player`, `chewie`, `file_selector` |
| `mobile/android/...` | `kotlin.incremental=false`; `<queries>` http/https để mở PDF |
| `mobile/lib/core/network/api_client.dart`, `api_exceptions.dart` | `postMultipart` (upload kèm tiến độ); `displayMessage`, `fieldErrorMap` |
| `mobile/lib/core/constants/api_constants.dart` | đọc `--dart-define=API_BASE_URL` |
| `mobile/lib/core/router/app_router.dart`, `features/profile/profile_screen.dart` | route `/admin/courses` và mục "Quản lý khóa học & combo" cho ADMIN |
| `mobile/lib/features/home/{courses_tab,home_tab}.dart` | tab Khóa học và "Khóa học nổi bật" dùng dữ liệu thật |

## Khi ghép với phần D (Spring Boot 4.1.1, Flyway)
- `backend/src/main/resources/db/migration/V8__course_catalog.sql` là migration các bảng của B cho nhánh của D (nối tiếp V1-V7). Ở nhánh này chưa có Flyway nên file chưa được dùng. Không sửa file này sau khi đã áp dụng (Flyway kiểm tra checksum); muốn đổi thì thêm V9.
- Nền Spring Boot 4 chỉ khác import `AutoConfigureMockMvc` trong 2 file test (`IntegrationTestBase`, `DataSeederTest`):
  `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- D quét entity/repository theo danh sách package cố định; cần thêm `com.artacademy` (gồm `course`, `bundle`, `product`) vào `@EntityScan` và `@EnableJpaRepositories` ở `ArtAcademyApplication`.
- Điểm nối sẵn cho D/C/E: `CourseSellableService.getSellableCourse(id)`, `BundleSellableService.getSellableBundle(id)`, `CourseContentService` (C phải tự kiểm tra đã mua), bảng `products` + `course_included_kits`.
  Nút "Thêm vào giỏ" gọi qua `CartGateway` (`mobile/lib/features/cart/cart_gateway.dart`), hiện chỉ báo thông báo; D thay bằng giỏ thật.

## Giới hạn hiện tại
- `/uploads/**` công khai: ai biết link thì xem được video, kể cả bài chưa mua. File upload lưu trên ổ đĩa server (`uploads/`); muốn dùng Cloudinary thì viết thêm một class implements `FileStorageService`.
- Combo chưa vào giỏ hàng và đơn hàng (nhóm còn đang bàn quy tắc combo và giảm giá).
- Chưa thử upload file trên Android và trên điện thoại thật.
