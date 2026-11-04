# Hợp đồng tích hợp phần B (khóa học, combo, quà tặng kèm) với D, C, E

Tài liệu bàn giao cho D (giỏ hàng, đơn hàng) và các bên liên quan. Mô tả đúng code trên nhánh `feature/course`.
**Migration `V10` là version dự kiến, cần review nội dung và phụ thuộc trước khi chạy ở bất kỳ database nào.**

## 1. Quy ước chung

- **ID** của mọi bảng B (khóa học, module, bài học, quà, combo, vị trí trong combo) là `Integer` / `INT`. **Tiền VND** là `long` / `BIGINT`.
- Gọi trực tiếp bằng service (inject bean), không qua HTTP. Chỉ đọc dữ liệu cục bộ, không giữ khóa lâu.
- `product_id` trong quà tặng kèm là id sản phẩm của **E** (bảng `products` thuộc E).

## 2. Khóa học: `CourseSellableService`

```java
SellableCourse getSellableCourse(Integer courseId);   // NotFoundException nếu không tồn tại hoặc không PUBLISHED; kèm status()
SellableCourse requireSellable(Integer courseId);      // như trên + CourseNotSellableException nếu thiếu quà (dùng khi thêm giỏ và checkout)
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
- **Mọi quà đã cấu hình đều bắt buộc** (nhóm đã chốt, áp dụng cho khóa lẻ và combo). `status()` = `AVAILABLE` hoặc `KIT_UNAVAILABLE` (có quà ngừng bán hoặc E không còn sản phẩm); `isSellable()` = `status() == AVAILABLE`. Khóa `KIT_UNAVAILABLE` vẫn hiển thị với nhãn "Tạm thiếu quà" nhưng không được thêm vào giỏ hay checkout. B **không tự bỏ quà** để tiếp tục bán; admin tự gỡ hoặc thay quà nếu muốn bán lại.
- Hết hàng tạm thời (tồn kho) **không thuộc B**: E quản lý Inventory, D kiểm tra lại khi checkout.
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
enum BundleSellableStatus { AVAILABLE, INACTIVE, COURSE_NOT_PUBLISHED, KIT_UNAVAILABLE }
```

Thứ tự ưu tiên khi có nhiều lý do: `INACTIVE` > `COURSE_NOT_PUBLISHED` > `KIT_UNAVAILABLE`. `KIT_UNAVAILABLE` nghĩa là có ít nhất một quà của một khóa trong combo đã ngừng bán hoặc không còn trong danh mục của E.

Tiện ích trên snapshot: `isSellable()`, `courseIds()`, `kitTotals()` (quà cả combo, đã cộng gộp), `unavailableKits()`.
Mọi danh sách và snapshot đều **bất biến** (`List.copyOf`).

### Lỗi (typed exception, HTTP 409) và mã lỗi ổn định

B chỉ ném **typed exception** ở service và cung cấp **mã lỗi ổn định**; việc đổi sang response `ApiResponse` có trường `errorCode` do phía integration làm (B không sửa `ApiResponse` của A).

| Exception | Mã lỗi (`getErrorCode()`) | HTTP | Khi nào | Dữ liệu kèm |
|---|---|---|---|---|
| `BundleChangedException` | `COMBO_DEFINITION_CHANGED` | 409 | `definitionVersion` hiện tại khác `expectedVersion` | `getExpectedVersion()`, `getCurrentVersion()` (Flutter dùng để tải lại combo) |
| `BundleNotSellableException` | `COMBO_NOT_SELLABLE` | 409 | combo đã tắt, có khóa không còn PUBLISHED, hoặc thiếu quà | `getSellableStatus()` = `INACTIVE` / `COURSE_NOT_PUBLISHED` / `KIT_UNAVAILABLE` |
| `CourseNotSellableException` (`com.artacademy.course.integration`) | `COURSE_NOT_SELLABLE` | 409 | khóa lẻ thiếu quà | `getSellableStatus()` = `KIT_UNAVAILABLE` |

Mã `COURSE_NOT_SELLABLE` là **đề xuất của B** cho khóa lẻ (nhóm mới chốt hai mã của combo), cần D xác nhận. `getStatus()` của lớp cha là mã HTTP. Nếu vừa không bán được vừa lệch version thì báo **không bán được** trước.

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
- Sản phẩm bị tắt hoặc không còn: không hiện trong danh sách quà cho học viên, được trả ở `unavailableKits`, và **làm khóa/combo không bán được** (`KIT_UNAVAILABLE`). B không tự bỏ quà khỏi khóa hay combo.
- API công khai vẫn hiển thị khóa và combo đó, kèm cờ `kitsUnavailable` (trong `CourseSummaryDto` và `BundleDto`) để app hiện nhãn "Tạm thiếu quà" và khóa nút thêm vào giỏ. `KitDto.available` cho admin biết quà nào đã ngừng bán.

## 6b. Nội dung bài học cho người đã đăng ký (C) và link video có chữ ký

Video/PDF đã upload **không còn công khai**: `/uploads/**` chỉ phục vụ ảnh. Video/PDF chỉ xem được qua link `/media/{file}?exp=&sig=` do API cấp (HMAC-SHA256, sống 2 giờ).

- `CourseContentService.getLessonContent(lessonId)` trả `videoUrl` và `documentUrl` **đã ký sẵn** cho file trên server (link ngoài giữ nguyên). **C phải kiểm tra Enrollment `ACTIVE` trước khi gọi** (B không biết ai là người gọi); đây là nơi quyết định "chỉ người đăng ký mới xem được". `LessonAccess.requireLesson` của C đã làm đúng việc này, nên C không cần sửa code.
- Link chỉ có hiệu lực ngắn: app không được lưu lại để dùng lại sau; mỗi lần mở bài học thì gọi lại API của C để lấy link mới. Người học bị thu hồi quyền (REVOKED) thì không lấy được link mới; link đã cấp vẫn dùng được tới khi hết hạn (tối đa 2 giờ).
- Bài học thử (`isPreview`): ai cũng xem được, nên `GET /api/courses/{id}` trả `previewVideoUrl` đã ký.
- Cấu hình: `app.media.url-ttl-seconds` (mặc định 7200), `app.media.signing-secret` (mặc định lấy từ `app.jwt.secret`; production nên đặt riêng).
- Hạn chế: link ngoài (YouTube...) B không bảo vệ được; ai nhận được link còn hạn thì xem được tới khi hết hạn.

## 6c. Mua combo (giỏ → đặt hàng → thanh toán → cấp quyền học)

Phạm vi: chế độ **Course-only** (`app.cart.course-only.api-enabled`, `app.checkout.course-only.*`). Giỏ chung có Product và Product Checkout **không nhận combo** (từ chối rõ ràng, không bao giờ coi id combo là id sản phẩm).

- **Loại hàng mới `COMBO`** (giỏ `cart_items` và đơn `order_items`; cùng từ vựng `COMBO` với refund của D). Một combo là **MỘT dòng**, số lượng luôn 1, giá = giá combo, **không áp voucher** (`voucher_eligible = false`).
- **Giỏ:** `POST /api/cart/items {"itemType":"COMBO","itemId":<id>,"quantity":1}`. Thêm được khi B báo combo bán được (`BundleSellableService.getSnapshot`), **không khóa nào kèm quà**, học viên chưa sở hữu và không có đơn chờ cho khóa nào trong combo. Thêm lại cùng combo là idempotent. Dòng combo trùng khóa với dòng khác trong giỏ (khóa lẻ hoặc combo khác) được báo `UNAVAILABLE`.
- **Đặt hàng:** `POST /api/checkout/course-only` đọc snapshot combo MỘT lần (`ComboCatalogPort`, B), kiểm tra trùng khóa/đã sở hữu/đang chờ bằng `ComboCheckoutValidator`, tạo một `OrderItem` COMBO rồi giữ chỗ **từng khóa** (`ComboPurchasePort`, C) trong cùng transaction: lỗi ở một khóa thì hủy cả đơn. Mã lỗi nghiệp vụ mới `COMBO_COURSE_OVERLAP` (HTTP 409 như các lỗi checkout khác).
- **Snapshot của combo chính là tập claim:** C lưu mỗi khóa một claim `COMBO_COMPONENT` (có `component_position`, gắn `order_item_id`). Settlement/hết hạn/hoàn tiền dùng tập claim này, **không đọc lại** định nghĩa combo hiện tại, nên sửa combo sau khi đặt không đổi những gì đã mua. Giá lấy lúc đặt hàng (như khóa lẻ), không ghim theo lúc thêm vào giỏ; `definitionVersion` chưa lưu vào đơn.
- **Thanh toán xong:** `SettlementCoordinator` gọi `confirmComboForOrder` (C) xác nhận toàn bộ claim của OrderItem và cấp Enrollment từng khóa (có `source_order_item_id`). **Hết hạn:** `OrderExpirationCoordinator` gọi `releaseComboForExpiredOrder` nhả mọi claim còn RESERVED. Hai hàm mới là `default` ném lỗi ở interface của D để participant không hỗ trợ combo thì từ chối chứ không cấp nửa chừng; C đã cài đủ.
- **DB:** migration `V17__combo_cart_and_order_items.sql` chỉ nới `CHECK` của `cart_items`/`order_items` thêm `COMBO` (combo và khóa số lượng 1, sản phẩm tùy ý). Đã chạy V1→V17 trên MySQL 8 thật (database tạm) và kiểm tra ràng buộc.
- **Chưa làm:** hoàn tiền combo (D chưa có API refund), combo kèm quà học cụ (cần giao hàng/kho), voucher cho combo (D chốt không áp).

## 7. Migration `V10__course_catalog.sql` (chỉ là tài liệu tham chiếu schema, chưa chạy)

Integration đã có Flyway V1 đến V13 (và V14 của A chưa áp dụng). Theo thống nhất: **không đưa file này vào database chung** và không đổi thành V14; phía integration tự review schema và nếu cần migration mới thì dùng V15 trở đi. File giữ nguyên tên `V10` để làm tài liệu tham chiếu.


- Tạo: `courses`, `course_modules`, `course_lessons`, `course_included_kits`, `course_bundles` (có `definition_version`), `bundle_items` (có `position`).
- **Không tạo** bảng `products`. Không sửa V1 đến V9. Không có khóa ngoại sang bảng của A, D, E.
- Kiểu: id `INT`, tiền `BIGINT`, enum `VARCHAR(20)` (`level`, `status`), mô tả dài `TEXT`.
- Ràng buộc: giá không âm, giá giảm nhỏ hơn giá gốc, số lượng quà 1-99, `position` 1-10, `definition_version >= 1`.
- Đã kiểm tra: chạy trên H2 (chế độ MySQL) và đối chiếu từng cột với entity. **Chưa chạy trên MySQL thật** và chưa chạy `MySqlSchemaCompatibilityIT` của D.
- Đề nghị D review: số version, thứ tự phụ thuộc, tên ràng buộc, cách thêm khóa ngoại sang `products` sau.

## 8. Đã chốt và việc còn chờ

**Đã chốt:** mọi quà là bắt buộc (khóa lẻ và combo); quà ngừng bán thì không bán được (`KIT_UNAVAILABLE`) nhưng vẫn hiển thị với nhãn "Tạm thiếu quà"; hai mã lỗi `COMBO_DEFINITION_CHANGED` và `COMBO_NOT_SELLABLE` (409); B dùng typed exception, phần đổi sang `ApiResponse` do integration; Order lưu snapshot bất biến của combo (Settlement và Refund dùng snapshot đó, không dùng định nghĩa combo hiện tại).

**Còn chờ:**
- Mã `COURSE_NOT_SELLABLE` cho khóa lẻ: D xác nhận.
- Hết hàng tạm thời, giao hàng, hủy đơn: D và E (B không theo dõi tồn kho).
- Hoàn tiền combo có quà đã giao hoặc đã nhận: D, C, E.
- Kiểu id sản phẩm của E (INT) và khóa ngoại quà sang `products`: integration kiểm tra schema thực tế.
