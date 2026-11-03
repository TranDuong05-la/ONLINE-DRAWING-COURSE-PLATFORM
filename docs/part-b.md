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
| `backend/src/main/resources/application.yaml` | `spring.servlet.multipart` 2GB (video), `app.upload.dir`, `app.seed.*` |
| `backend/pom.xml` | H2 đổi scope `test` → `runtime` (cho profile `h2`) |
| `mobile/pubspec.yaml` (+ `pubspec.lock`) | `video_player`, `chewie`, `file_selector` |
| `mobile/android/...` | `kotlin.incremental=false`; `<queries>` http/https để mở PDF |
| `mobile/lib/core/network/api_client.dart`, `api_exceptions.dart` | `postMultipart` (upload kèm tiến độ); `displayMessage`, `fieldErrorMap` |
| `mobile/lib/core/constants/api_constants.dart` | đọc `--dart-define=API_BASE_URL` |
| `mobile/lib/core/router/app_router.dart`, `features/profile/profile_screen.dart` | route `/admin/courses` và mục "Quản lý khóa học & combo" cho ADMIN |
| `mobile/lib/features/home/{courses_tab,home_tab}.dart` | tab Khóa học và "Khóa học nổi bật" dùng dữ liệu thật |

## Khi ghép với phần D (Spring Boot 4.1.1, Flyway)
- `backend/src/main/resources/db/migration/V10__course_catalog.sql` là migration **dự kiến** các bảng của B cho nhánh của D (D dành V10; không tạo bảng `products` vì E sở hữu). Ở nhánh này chưa có Flyway nên file chưa được dùng, và chưa chạy ở database nào: cần D và B review trước. Không sửa file này sau khi đã áp dụng (Flyway kiểm tra checksum).
- Nền Spring Boot 4 chỉ khác import `AutoConfigureMockMvc` trong 2 file test (`IntegrationTestBase`, `DataSeederTest`):
  `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- D quét entity/repository theo danh sách package cố định; cần thêm `com.artacademy` (gồm `course`, `bundle`, `product`) vào `@EntityScan` và `@EnableJpaRepositories` ở `ArtAcademyApplication`.
- Điểm nối cho D/C/E (ID là `Integer`, tiền `long`): `CourseSellableService`, `BundleSellableService.getSnapshot(id)` / `requireSellable(id, expectedVersion)`, `CourseContentService` (C phải tự kiểm tra đã mua), interface `ProductCatalog` (E cài). Xem [part-b-integration-contract.md](part-b-integration-contract.md).
  Nút "Thêm vào giỏ" gọi qua `CartGateway` (`mobile/lib/features/cart/cart_gateway.dart`), hiện chỉ báo thông báo; D thay bằng giỏ thật.

## Giới hạn hiện tại
- **Upload:** video tối đa **2GB** (`app.upload.max-video-mb`), ảnh 5MB, pdf 20MB. File lớn không nằm trong RAM: Spring ghi file tạm ra đĩa rồi chuyển vào `uploads/`. Đặt `UPLOAD_TMP_DIR` (thư mục tạm) và `UPLOAD_DIR` (thư mục lưu) trên ổ còn nhiều chỗ, cùng ổ với nhau thì chuyển file nhanh hơn. Đã thử thật: 1GB lên trong khoảng 43 giây, 2.040MB trong khoảng 2 phút, 2,2GB bị từ chối ngay bằng lỗi 413. Nhược điểm của cách này: đứt mạng phải tải lại từ đầu, file chỉ nằm trên một máy, chưa sao lưu.
- `/uploads/**` công khai: ai biết link thì xem được video, kể cả bài chưa mua. Muốn khóa lại hoặc lưu trên đám mây thì viết class khác implements `FileStorageService` (lưu ý Cloudinary gói miễn phí chỉ nhận video tối đa 100MB).
- **Quà tặng kèm:** sản phẩm thuộc E nên B chỉ lưu `product_id` và tra qua interface `ProductCatalog`. Trong lúc E chưa tích hợp, B dùng bản tạm trong bộ nhớ (`InMemoryProductCatalog`, dữ liệu mẫu chỉ có khi bật `app.seed.enabled`); E đặt `app.products.stand-in=false` để tắt nó.
- Combo: 2 đến 10 khóa, có `definitionVersion` và vị trí khóa; chưa vào giỏ hàng và đơn hàng (D triển khai sau khi hai bên review hợp đồng).
- Chưa thử upload file trên Android và trên điện thoại thật.
