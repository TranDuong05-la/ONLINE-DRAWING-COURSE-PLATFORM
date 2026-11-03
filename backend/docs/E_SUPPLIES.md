# Phần E – Học cụ, kho, quà tặng, giao hàng, đánh giá

Nhánh: `feature/member-e-supplies` (tạo từ `main`).

## Đã làm

| Mảng | Học viên | Quản lý | Backend |
|---|---|---|---|
| Học cụ | Tab "Họa cụ": tìm theo tên/SKU, lọc danh mục, còn hàng, sắp xếp; chi tiết, giá, ảnh, còn/hết hàng; nút thêm vào giỏ | ADMIN: tạo/sửa/ẩn sản phẩm, danh mục | `/api/supplies/**`, `/api/admin/supplies/**` |
| Kho | – | STAFF/ADMIN: xem tồn (trong kho / đang giữ / còn bán), nhập kho, lịch sử kho | Giữ hàng nguyên tử, trả giữ đúng 1 lần, xuất kho đúng 1 lần, hàng về kho |
| Quà tặng | Nhãn "Quà kèm khóa" trong đơn giao | Thấy quà trong danh sách đóng gói | Quà giá 0 vẫn giữ/xuất kho như hàng mua |
| Giao hàng | Danh sách + chi tiết, dòng thời gian | STAFF/ADMIN: lọc theo trạng thái, đánh dấu đã soạn, chuyển trạng thái, mã vận đơn | Chuẩn bị → Đang giao → Thành công / Thất bại → Giao lại / Về kho |
| Đánh giá | Viết/sửa sau khi nhận hàng, xem điểm trung bình + phân bố sao | ADMIN gỡ đánh giá (API) | Chỉ người có chuyến giao thành công; 1 người 1 đánh giá / sản phẩm |

Vào màn quản lý: Admin dashboard → "Học cụ, Kho & Giao hàng"; Staff dashboard → "Đơn cần giao" / "Kho học cụ".

## Bảng dữ liệu

- `products`: **dùng chung với B**. Giữ nguyên cột của B (`sku`, `name`, `price`, `thumbnail_url`, `active`),
  E thêm `category_id`, `description`, `stock_on_hand`, `stock_reserved`, `low_stock_threshold`,
  `rating_avg`, `rating_count`, `created_at`, `updated_at`.
- Mới: `product_categories`, `stock_reservations`, `inventory_transactions`, `shipments`, `shipment_items`, `reviews`.

## Quy tắc kho

```
còn bán = stock_on_hand - stock_reserved

Tạo đơn       reserved += q          (UPDATE ... WHERE on_hand - reserved >= q → không âm kho)
Hủy/thất bại  reserved -= q          (dòng giữ RESERVED → RELEASED, chỉ thành công 1 lần)
Đang giao     on_hand -= q, reserved -= q   (RESERVED → EXPORTED, chỉ 1 lần)
Giao thất bại không đổi kho (hàng có thể chưa về)
Hàng về kho   on_hand += q           (FAILED → RETURNED, chỉ 1 lần)
```

Mọi thay đổi ghi vào `inventory_transactions` kèm số tồn sau thay đổi.

## Hợp đồng với các thành viên khác

### D – giỏ, đơn, thanh toán

D inject `com.artacademy.supply.api.SupplyFulfillmentApi` (Spring bean), không đọc/ghi thẳng bảng của E.

```java
// Giỏ / checkout (CART_API_CONTRACT: ProductCartCatalogProvider + ProductStockAvailabilityProvider)
Optional<CartProductView> p = supplyApi.findProductForCart(productId);
// p.priceVnd() (long VND), p.purchasable() (= active), p.available() (= on_hand - reserved)

// Tạo đơn: giữ học cụ mua + quà kèm khóa (lấy từ course_included_kits của B)
supplyApi.reserveForOrder(orderId, userId, List.of(
        ReserveLine.purchase(productId, qty),
        ReserveLine.gift(kitProductId, kitQty)));      // hết hàng → SupplyException OUT_OF_STOCK (409)

// Thanh toán thành công → tạo chuyến giao. Đơn thuần khóa trả Optional.empty, không cần địa chỉ
supplyApi.onOrderPaid(orderId, userId, new ShippingInfo(name, phone, "số nhà, phường, quận, tỉnh"));

// Thanh toán thất bại / hủy / hết hạn (gọi lặp không sao). Đã xuất kho → ALREADY_SHIPPED (409)
supplyApi.releaseForOrder(orderId, "EXPIRED");

// Thanh toán báo về muộn sau khi đã release: reserveForOrder(orderId, userId, List.of()) giữ lại;
// nếu OUT_OF_STOCK thì D hoàn tiền / báo người mua.
```

- Kiểu id: `orderId`, `userId` là `Integer` (như D và bảng `users`); `productId` là `Long` (bảng `products` của B).
- Các hàm có `@Transactional` mặc định (REQUIRED) nên chạy chung transaction checkout của D.
- Nhánh D có `InventoryShippingContracts.InventoryReservations` (reserve/confirm/release). Khi ghép,
  viết một adapter mỏng: `reserve` → `reserveForOrder`, `confirm` → `onOrderPaid`, `release` → `releaseForOrder`.
- Màn chi tiết đơn của D hiện trạng thái giao: `GET /api/shipments/by-order/{orderId}`.
- Nút "Thêm vào giỏ" gọi `POST /api/cart/items {itemType: "PRODUCT", itemId, quantity}` đúng hợp đồng của D,
  nằm ở [cart_bridge.dart](../../mobile/lib/features/supplies/data/cart_bridge.dart).
  Nút giỏ hàng trên tab Họa cụ: D thêm `IconButton(onPressed: () => context.push('/cart'))` vào AppBar của
  `SupplyShopScreen` (tab này thay `StoreTab` cũ trong `main_screen.dart`).

### B – khóa học, quà kèm

- `course_included_kits.product_id` trỏ vào `products.id` như B đã làm. `Product.java` của E là bản mở rộng
  của file B: giữ nguyên constructor, getter/setter cũ.
- **Khi merge với nhánh B, `Product.java` sẽ conflict (cả hai cùng thêm file): chọn bản của E.**
  Các file khác của B (`ProductRepository`, `AdminProductController`) không đụng tới.

### A – tài khoản

- Lấy người dùng hiện tại qua email trong JWT (`SupplyCurrentUser`), không sửa `SecurityConfig`.
- Phân quyền dùng sẵn quy tắc của A: `/api/admin/**` = ADMIN, `/api/staff/**` = STAFF + ADMIN, còn lại cần đăng nhập.
- Tên/ảnh người đánh giá lấy từ bảng `users`.

## API

| Phương thức | Đường dẫn | Ai |
|---|---|---|
| GET | `/api/supplies/categories` | Đăng nhập |
| GET | `/api/supplies/products?q&categoryId&inStock&minPrice&maxPrice&sort&page&size` | Đăng nhập |
| GET | `/api/supplies/products/{id}` | Đăng nhập |
| GET | `/api/supplies/products/{id}/reviews`, `/reviews/summary`, `/reviews/eligibility` | Đăng nhập |
| POST | `/api/supplies/products/{id}/reviews` | Người đã nhận hàng |
| PUT | `/api/supplies/reviews/{id}` | Chủ đánh giá |
| GET | `/api/shipments/me`, `/api/shipments/{id}`, `/api/shipments/by-order/{orderId}` | Chủ đơn / STAFF / ADMIN |
| GET/POST/PUT | `/api/admin/supplies/categories[/{id}]` | ADMIN |
| POST/PUT | `/api/admin/supplies/products[/{id}]` | ADMIN |
| PATCH | `/api/admin/supplies/products/{id}/status` `{active}` | ADMIN |
| DELETE | `/api/admin/supplies/reviews/{id}` | ADMIN |
| GET | `/api/staff/supplies/products`, `/products/{id}`, `/products/{id}/inventory-transactions` | STAFF, ADMIN |
| POST | `/api/staff/supplies/products/{id}/stock-imports` | STAFF, ADMIN |
| GET | `/api/staff/shipments?status=PREPARING`, `/summary`, `/{id}` | STAFF, ADMIN |
| PATCH | `/api/staff/shipments/{id}/status` | STAFF, ADMIN |
| POST | `/api/dev/supply/orders`, `/api/dev/supply/orders/{id}/release` | **Không có ở profile prod** – giả lập đơn để test trước khi ghép D |

Mọi response dùng `ApiResponse` chung; trang dùng các trường `content, totalElements, totalPages, number, last` như `PageResponse` bên Flutter.

## Kiểm thử

```bash
cd backend && mvn test     # 27 test phần E + test sẵn có của A
cd mobile && flutter test
```

Tình huống trong bảng phân công đã có test (`SupplyFulfillmentTest`, `SupplyApiSecurityTest`):
8 người cùng mua món cuối chỉ 1 người giữ được; thiếu 1 món thì không giữ món nào; quà giá 0 vẫn trừ kho;
hủy đơn trả giữ đúng 1 lần, hủy sau khi xuất kho bị từ chối; giao thất bại không tự nhập kho, "về kho" chỉ cộng 1 lần,
giao lại không trừ kho lần 2; bấm "đang giao" 2 lần chỉ xuất 1 lần; chưa nhận hàng / không mua thì không đánh giá được;
đơn thuần khóa không tạo giao hàng; sửa sản phẩm không ghi đè số tồn; học viên không gọi được API quản lý,
không xem được đơn giao của người khác; STAFF không tạo được sản phẩm.

## Demo (trước khi ghép D)

Dữ liệu mẫu tạo khi chạy (tắt bằng `SEED_DATA_ENABLED=false`): 4 danh mục, 6 học cụ; `BR-SET-6` chỉ còn 1, `BR-RD-8` hết hàng.

1. Đăng nhập `user.test@artnest.com` / `ArtNest@123` → tab Họa cụ → chọn học cụ → "Thêm vào giỏ".
   Chưa có giỏ của D nên bản debug mở "Đặt thử (dev)".
2. Đăng nhập `staff.test@artnest.com` → Staff dashboard → "Đơn cần giao" → đánh dấu đã soạn →
   "Chuyển sang đang giao" → "Đã giao thành công".
3. Đăng nhập lại học viên → Họa cụ → biểu tượng xe tải → đơn → "Đánh giá".
