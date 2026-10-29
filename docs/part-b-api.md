# Phần B: khóa học, combo, quà tặng kèm, upload (endpoint và màn hình)

## 4. Phần Khóa học, Combo, Quà tặng kèm (phần B, đã ghép vào backend này)

Tất cả phản hồi vẫn bọc trong `ApiResponse<T>` (`{ success, message, data, errors, timestamp }`). Tiền là VND (số nguyên).
Danh sách phân trang có dạng `data = { content, page, size, totalElements, totalPages }`.
Lỗi nghiệp vụ trả `success=false` và `message` tiếng Việt; lỗi kiểm tra dữ liệu trả `message = "Validation failed"` kèm `errors[]`.

### 4.1. Endpoint công khai (không cần đăng nhập, chỉ GET)

| Method | Endpoint | Màn hình Flutter | Ghi chú |
|:------:|:---------|:-----------------|:--------|
| `GET` | `/api/courses?keyword=&level=BASIC\|ADVANCED&sort=newest\|price_asc\|price_desc&page=0&size=10` | `CourseListPage` (tab Khóa học → Từng khóa), `HomeTab` (2 khóa mới nhất) | Chỉ khóa `PUBLISHED`. Sắp xếp giá theo giá thực bán (giá giảm nếu có). |
| `GET` | `/api/courses/{id}` | `CourseDetailPage` | Khóa nháp/ngừng bán trả 404. **Không trả `videoUrl` và link tài liệu** của bài không học thử; bài học thử có `previewVideoUrl`. |
| `GET` | `/api/bundles`, `/api/bundles/{id}` | `BundleListPage`, `BundleDetailPage` | Combo đang bật và mọi khóa đều `PUBLISHED`. |
| `GET` | `/uploads/**` | `Image.network`, `video_player` | File đã upload (ảnh bìa, video, PDF), hỗ trợ `Range` để tua video. |

### 4.2. Endpoint quản trị (bắt buộc `Bearer` + role `ADMIN`)

| Method | Endpoint | Màn hình Flutter | Ghi chú nghiệp vụ |
|:------:|:---------|:-----------------|:-------------------|
| `GET`, `POST` | `/api/admin/courses` | `AdminCourseListPage`, `AdminCourseFormPage` | `GET` lọc `status`, `keyword`, phân trang. `POST` tạo khóa ở trạng thái `DRAFT`. |
| `GET`, `PUT` | `/api/admin/courses/{id}` | `AdminCourseDetailPage` | `GET` kèm `publishProblems` (điều kiện xuất bản còn thiếu). |
| `PATCH` | `/api/admin/courses/{id}/status` | `AdminCourseDetailPage` | `PUBLISHED` / `HIDDEN`. Xuất bản kiểm tra lại mỗi lần; đã từng xuất bản thì không về `DRAFT`. |
| `PUT` | `/api/admin/courses/{id}/kits` | `AdminKitsPage` | Thay toàn bộ quà tặng kèm: sản phẩm đang bán, số lượng 1-99, không trùng. |
| `POST` | `/api/admin/courses/{courseId}/modules` | `AdminCourseDetailPage` | Module mới nằm cuối. |
| `PUT` | `/api/admin/courses/{courseId}/modules/reorder` | `AdminCourseDetailPage` | Body `{ "ids": [...] }`, phải gửi đủ mọi module. |
| `PUT` | `/api/admin/modules/{id}` | `AdminModulePage` | Đổi tên / ẩn module (không có xóa). |
| `POST` | `/api/admin/modules/{moduleId}/lessons` | `AdminLessonFormPage` | Thêm bài học. |
| `PUT` | `/api/admin/modules/{moduleId}/lessons/reorder` | `AdminModulePage` | Sắp xếp bài học. |
| `PUT` | `/api/admin/lessons/{id}` | `AdminLessonFormPage` | Sửa bài (video, thời lượng, PDF, cờ học thử / bắt buộc / ẩn). |
| `GET` | `/api/admin/products` | `AdminKitsPage` | Sản phẩm đang bán để chọn làm quà. |
| `POST` | `/api/admin/uploads` | `UploadField` | `multipart`, field `file`; jpg/png/webp ≤ 5MB, pdf ≤ 20MB, mp4 ≤ 200MB. Trả `data.url`. |
| `GET`, `POST` | `/api/admin/bundles` | `AdminBundleListPage` (tab Combo), `AdminBundleFormPage` | Combo cần ≥ 2 khóa `PUBLISHED`, giá combo < tổng giá thực bán. |
| `GET`, `PUT` | `/api/admin/bundles/{id}` | `AdminBundleFormPage` | |
| `PATCH` | `/api/admin/bundles/{id}/active` | `AdminBundleListPage` | Bật / tắt combo (không xóa). |

Khu vực này vào từ `ProfileScreen` → "Quản lý khóa học & combo" (route `/admin/courses`, chỉ hiện với role `ADMIN`).

### 4.3. Điểm nối cho các phần khác (service Java, gọi trực tiếp, không qua HTTP)

- **D (giỏ hàng/đơn hàng)**: `CourseSellableService.getSellableCourse(id)`, `BundleSellableService.getSellableBundle(id)`.
  Nút "Thêm vào giỏ" ở Flutter gọi qua `CartGateway` (`lib/features/cart/cart_gateway.dart`), hiện chỉ báo SnackBar.
- **C (học tập)**: `CourseContentService.getCourseStructure(courseId)` và `getLessonContent(lessonId)` (có link video/PDF).
  **C phải tự kiểm tra người dùng đã mua khóa** trước khi gọi.
- **E (shop)**: bảng `products` và `course_included_kits` (course_id, product_id, quantity).

### 4.4. Cách chạy thử nhanh (không cần cài MySQL)

Chạy backend với profile `h2` (dữ liệu trong bộ nhớ, tự nạp khóa học mẫu và 2 tài khoản mẫu: 1 `ADMIN`, 1 `USER`,
email/mật khẩu nằm trong `backend/src/main/resources/application-h2.yaml`):

```
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
```

Cổng mặc định 8080; nếu bị chiếm, thêm `-Dspring-boot.run.arguments=--server.port=8081` và chạy app với
`--dart-define=API_BASE_URL=http://10.0.2.2:8081`.

