package com.artacademy.bundle;

import com.artacademy.course.integration.SellableCourse.SellableKit;
import com.artacademy.course.integration.SellableCourse.UnavailableKit;
import java.util.ArrayList;
import java.util.List;

/**
 * Ảnh chụp bất biến của combo tại một thời điểm, để D (giỏ hàng/đơn hàng) lưu vào Order.
 * Tiền VND dùng long. Combo là MỘT dòng mua hàng, giá = priceVnd (cố định, không đổi theo giá từng khóa).
 * Giá từng khóa (effectivePriceVnd) chỉ để tham khảo, không dùng để chia tiền.
 *
 * definitionVersion: bắt đầu từ 1, tăng khi admin đổi mã, tên, giá, danh sách hoặc thứ tự khóa của combo.
 * Không tăng khi chỉ bật/tắt combo, sửa mô tả hay khi tên/giá/quà của khóa bên trong đổi.
 * courses: theo đúng thứ tự (position 1..n). Quà tặng kèm luôn được đọc lại tại thời điểm lấy snapshot.
 */
public record BundleSnapshot(
        Integer bundleId,
        String code,
        String title,
        long priceVnd,
        int definitionVersion,
        BundleSellableStatus status,
        String statusReason,
        long originalTotalVnd,
        List<Component> courses) {

    public BundleSnapshot {
        courses = List.copyOf(courses);
    }

    /** Một khóa trong combo. */
    public record Component(
            Integer courseId,
            int position,
            String title,
            long effectivePriceVnd,
            List<SellableKit> kits,
            List<UnavailableKit> unavailableKits) {

        public Component {
            kits = List.copyOf(kits);
            unavailableKits = List.copyOf(unavailableKits);
        }
    }

    public boolean isSellable() {
        return status == BundleSellableStatus.AVAILABLE;
    }

    /** Id các khóa trong combo (để D/C kiểm tra trùng khóa với khóa lẻ, combo khác, khóa đã sở hữu). */
    public List<Integer> courseIds() {
        return courses.stream().map(Component::courseId).toList();
    }

    /** Quà của cả combo, đã cộng gộp số lượng nếu nhiều khóa cùng tặng một sản phẩm. */
    public List<SellableKit> kitTotals() {
        List<SellableKit> all = new ArrayList<>();
        courses.forEach(c -> all.addAll(c.kits()));
        return SellableKit.mergeByProduct(all);
    }

    /** Quà đã cấu hình nhưng không còn bán (ngừng bán hoặc E không còn sản phẩm), của mọi khóa trong combo. */
    public List<UnavailableKit> unavailableKits() {
        return courses.stream().flatMap(c -> c.unavailableKits().stream()).toList();
    }
}
