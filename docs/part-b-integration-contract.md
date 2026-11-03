# Hợp đồng tích hợp phần B (khóa học, combo, quà tặng kèm) với D, C, E

Tài liệu bàn giao cho D (giỏ hàng, đơn hàng) và các bên liên quan. Mô tả đúng code trên nhánh `feature/course`.
**Migration `V10` là version dự kiến, cần review nội dung và phụ thuộc trước khi chạy ở bất kỳ database nào.**

## 1. Quy ước chung

- **ID** của mọi bảng B (khóa học, module, bài học, quà, combo, vị trí trong combo) là `Integer` / `INT`. **Tiền VND** là `long` / `BIGINT`.
- Gọi trực tiếp bằng service (inject bean), không qua HTTP. Chỉ đọc dữ liệu cục bộ, không giữ khóa lâu.
- `product_id` trong quà tặng kèm là id sản phẩm của **E** (bảng `products` thuộc E).

## 2. Khóa học: `CourseSellableService`

```java
SellableCourse getSellableCourse(Integer courseId);   // NotFoundException nếu không tồn tại hoặc không PUBLISHED
SellableCourse toSellable(Course course);              // dùng nội bộ cho combo
```

```java
record SellableCourse(Integer courseId, String title, String thumbnailUrl,
                      long price, Long discountPrice, long effectivePrice,
                      List<SellableKit> kits,                 // quà còn bán
                      List<UnavailableKit> unavailableKits)   // quà đã cấu hình nhưng không còn bán
record SellableKit(Integer productId, String sku, String name, int quantity)
record UnavailableKit(Integer productId, int quantity, String reason)   // reason: "NOT_FOUND" | "INACTIVE"
```

- `effectivePrice` = giá giảm nếu có, không thì giá gốc. D tự lưu giá lúc mua vào Order.
- `SellableKit.mergeByProduct(Collection<SellableKit>)` cộng gộp `quantity` theo `productId` (giữ thứ tự xuất hiện đầu tiên); dùng khi một đơn có nhiều khóa/combo tặng cùng một sản phẩm.

## 3. Combo: `BundleSellableService`

```java
BundleSnapshot getSnapshot(Integer bundleId);                       // luôn trả snapshot kèm trạng thái; NotFoundException nếu không tồn tại
BundleSnapshot requireSellable(Integer bundleId, int expectedVersion);   // chỉ trả khi bán được VÀ đúng version
SellableBundle getSellableBundle(Integer bundleId);                 // bản cũ, giữ để tương thích
```

```java
record BundleSnapshot(Integer bundleId, String code, String title,
                      long priceVnd, int definitionVersion,
                      BundleSellableStatus status, String statusReason,   // statusReason null khi AVAILABLE
                      long originalTotalVnd,                              // tổng giá lẻ các khóa (để hiển thị tiết kiệm)
                      List<Component> courses)                            // đúng thứ tự position 1..n
record Component(Integer courseId, int position, String title, long effectivePriceVnd,
                 List<SellableKit> kits, List<UnavailableKit> unavailableKits)
enum BundleSellableStatus { AVAILABLE, INACTIVE, COURSE_NOT_PUBLISHED }
```

Tiện ích trên snapshot: `isSellable()`, `courseIds()`, `kitTotals()` (quà cả combo, đã cộng gộp), `unavailableKits()`.
Mọi danh sách và snapshot đều **bất biến** (`List.copyOf`).

### Lỗi (đều là `ApiException`, HTTP 409, nằm ở `com.artacademy.bundle`)

| Exception | Khi nào | Dữ liệu kèm |
|---|---|---|
| `BundleNotSellableException` | combo đã tắt hoặc có khóa không còn PUBLISHED | `getSellableStatus()` = `INACTIVE` hoặc `COURSE_NOT_PUBLISHED` |
| `BundleChangedException` | `definitionVersion` hiện tại khác `expectedVersion` | `getExpectedVersion()`, `getActualVersion()` |

`getStatus()` của lớp cha là mã HTTP. Nếu vừa không bán được vừa lệch version thì báo **không bán được** trước.

### Luồng khuyến nghị cho D

1. Giỏ hàng: `getSnapshot(id)`, hiển thị combo như **một dòng**, nhớ `definitionVersion`.
2. Checkout: gọi `requireSellable(id, expectedVersion)` **một lần** và dùng chính kết quả đó làm snapshot lưu vào Order (không đọc lần hai).
3. Order lưu: id, code, tên, `priceVnd`, `definitionVersion`, danh sách khóa (id, position, tên) và quà (`kits` từng khóa, `kitTotals()` cho cả combo).
4. C cấp quyền học cho **từng khóa** trong `courseIds()` khi thanh toán thành công.

Chặn trùng (khóa lẻ trong combo, hai combo trùng khóa, khóa đã sở hữu hoặc đang chờ thanh toán) là việc của D và C: B chỉ cung cấp `courseIds()`.

## 4. `definitionVersion` và vị trí

- `definitionVersion` bắt đầu từ **1**. **Tăng** khi admin đổi mã, tên, giá, danh sách khóa hoặc thứ tự khóa.
- **Không tăng** khi chỉ bật/tắt combo, sửa mô tả, không đổi gì, hoặc khi khóa bên trong đổi tên/giá/quà.
- `bundle_items.position` bắt đầu từ 1, không trùng trong cùng combo, tối đa 10 (có ràng buộc ở API, service và DB).
- Vì vậy `expectedVersion` chỉ bảo vệ **định nghĩa combo**. Quà tặng kèm luôn được đọc lại lúc lấy snapshot và lưu vào Order (không coi là lỗi nếu quà đổi).

## 5. Giới hạn combo

- Từ **2 đến 10** khóa khác nhau, mọi khóa phải PUBLISHED lúc lưu, giá combo < tổng giá thực bán (kiểm tra ở `@Size` của API và ở service).
- Giá combo cố định, không đổi theo giá khóa. Nếu giá khóa giảm khiến combo không còn rẻ hơn mua lẻ: vẫn bán, màn admin cảnh báo.
- Combo **không áp voucher** (D chốt); B không lưu cờ voucher.
- Combo chỉ tắt, không xóa.

## 6. Quà tặng kèm (included kits) và hợp đồng với E

Quà là **sản phẩm vật lý tặng kèm**, giá bán trong Order bằng 0. Chưa chốt xử lý quà hết hàng, giao hàng và hoàn tiền (chờ D, E, C).

B **không còn bảng products**. B cần E cài interface:

```java
public interface ProductCatalog {
    Map<Integer, KitProduct> findByIds(Collection<Integer> ids);   // id không có trong kết quả = không tồn tại
    record KitProduct(Integer id, String sku, String name, String thumbnailUrl, boolean active) {}
}
```

- E viết một bean `ProductCatalog` thật rồi đặt `app.products.stand-in=false` để tắt bản tạm `InMemoryProductCatalog` và `StandInProductController` của B.
- `GET /api/admin/products` (màn chọn quà của admin) hiện do bản tạm phục vụ, trả danh sách sản phẩm đang bán `{ id, sku, name, price, thumbnailUrl }`. E cần cung cấp API cùng đường dẫn và dạng này, hoặc cho B biết dạng khác.
- `course_included_kits.product_id` là INT, **chưa có khóa ngoại** sang `products`. Đề nghị E chỉ tắt (`active = false`), **không xóa cứng** sản phẩm đang được tặng kèm.
- Sản phẩm bị tắt hoặc không còn: không hiện cho học viên, và được trả ở `unavailableKits` (B không ẩn đi để D/E quyết định).

## 7. Migration `V10__course_catalog.sql` (dự kiến, chưa chạy)

- Tạo: `courses`, `course_modules`, `course_lessons`, `course_included_kits`, `course_bundles` (có `definition_version`), `bundle_items` (có `position`).
- **Không tạo** bảng `products`. Không sửa V1 đến V9. Không có khóa ngoại sang bảng của A, D, E.
- Kiểu: id `INT`, tiền `BIGINT`, enum `VARCHAR(20)` (`level`, `status`), mô tả dài `TEXT`.
- Ràng buộc: giá không âm, giá giảm nhỏ hơn giá gốc, số lượng quà 1-99, `position` 1-10, `definition_version >= 1`.
- Đã kiểm tra: chạy trên H2 (chế độ MySQL) và đối chiếu từng cột với entity. **Chưa chạy trên MySQL thật** và chưa chạy `MySqlSchemaCompatibilityIT` của D.
- Đề nghị D review: số version, thứ tự phụ thuộc, tên ràng buộc, cách thêm khóa ngoại sang `products` sau.

## 8. Việc còn chờ chốt

- Xử lý quà hết hàng (chặn checkout hay bán không quà), giao hàng, hủy đơn: D, E.
- Hoàn tiền combo có quà (có phải trả lại quà không): D, C, E.
- Dạng exception hay kết quả trả về cho lỗi combo: D chọn (hiện B ném exception).
