package com.artacademy.supply.api;

import java.util.List;
import java.util.Optional;

/**
 * Hợp đồng E cung cấp cho D (giỏ/đơn/thanh toán). D chỉ gọi qua interface này,
 * không đọc/ghi thẳng bảng products, stock_reservations, shipments.
 *
 * Luồng chuẩn:
 *   1. Giỏ / checkout: getProductForCart() để lấy giá, trạng thái bán và số còn bán
 *      (khớp ProductCartCatalogProvider + ProductStockAvailabilityProvider trong CART_API_CONTRACT của D).
 *   2. Tạo đơn: reserveForOrder(orderId, userId, lines). Học cụ mua và quà kèm khóa (gift = true, giá 0)
 *      đều phải giữ hàng. Hết hàng -> SupplyException code OUT_OF_STOCK (HTTP 409), không giữ món nào.
 *      Mọi hàm chạy trong transaction của D nếu D đang có transaction.
 *   3. Thanh toán thành công: onOrderPaid(...) -> tạo chuyến giao (nếu đơn có hàng vật lý).
 *   4. Thanh toán thất bại / hủy / hết hạn: releaseForOrder(orderId, lý do). Gọi lặp không sao.
 *   5. Thanh toán báo về muộn sau khi đã release: gọi lại reserveForOrder(orderId, userId, List.of())
 *      để giữ lại nếu còn hàng, rồi onOrderPaid. Nếu OUT_OF_STOCK thì D hoàn tiền / báo người mua.
 */
public interface SupplyFulfillmentApi {

    /** Thông tin bán của một học cụ, dùng để D tính tiền ở backend. Không có -> Optional.empty(). */
    Optional<CartProductView> findProductForCart(Long productId);

    /**
     * Giữ hàng cho đơn. Tất cả hoặc không: thiếu một món thì không giữ món nào.
     * Idempotent: gọi lại với cùng orderId trả về phần đã giữ.
     */
    List<ReservationView> reserveForOrder(Integer orderId, Integer userId, List<ReserveLine> lines);

    /**
     * Trả giữ toàn bộ hàng của đơn, đúng một lần. Hủy chuyến giao nếu còn đang chuẩn bị.
     * Ném ALREADY_SHIPPED nếu hàng đã xuất kho.
     *
     * @return số dòng vừa được trả giữ (0 nếu đã trả trước đó hoặc đơn không có hàng)
     */
    int releaseForOrder(Integer orderId, String reason);

    /**
     * Đơn đã thanh toán: tạo chuyến giao từ hàng đang giữ. Idempotent theo orderId.
     * Đơn thuần khóa (không có hàng giữ) -> Optional.empty(), không cần địa chỉ.
     * shippingInfo bắt buộc khi đơn có học cụ hoặc quà.
     *
     * @return id chuyến giao
     */
    Optional<Long> onOrderPaid(Integer orderId, Integer userId, ShippingInfo shippingInfo);
}
