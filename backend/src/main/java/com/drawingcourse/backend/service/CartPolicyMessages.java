package com.drawingcourse.backend.service;

/**
 * Thông báo hiển thị cho người dùng khi giỏ hàng từ chối một món (HTTP 409). Mỗi lý do có một câu riêng để người dùng
 * biết vì sao mà không phải đoán: đã sở hữu, đang chờ thanh toán, có quà tặng kèm, hay không còn bán.
 */
public final class CartPolicyMessages {
    private CartPolicyMessages() { }

    public static final String COURSE_NOT_FOR_SALE = "Khóa học này hiện không bán được.";
    public static final String COURSE_OWNED = "Bạn đã sở hữu khóa học này rồi.";
    public static final String COURSE_PENDING = "Khóa học này đang nằm trong một đơn hàng chờ thanh toán của bạn.";
    public static final String COURSE_GIFT = "Khóa học này có quà tặng kèm (học cụ) nên chưa mua được qua giỏ hàng này.";

    public static final String COMBO_NOT_FOR_SALE = "Combo này hiện không bán được.";
    public static final String COMBO_OWNED = "Combo này có khóa học bạn đã sở hữu rồi.";
    public static final String COMBO_PENDING = "Combo này có khóa học đang nằm trong một đơn hàng chờ thanh toán của bạn.";
    public static final String COMBO_GIFT = "Combo này có khóa học kèm quà tặng (học cụ) nên chưa mua được qua giỏ hàng này.";

    public static final String PRODUCT_NOT_FOR_SALE = "Học cụ này hiện không bán được.";
    public static final String TYPE_NOT_SUPPORTED = "Giỏ hàng này không nhận loại sản phẩm này.";
}
