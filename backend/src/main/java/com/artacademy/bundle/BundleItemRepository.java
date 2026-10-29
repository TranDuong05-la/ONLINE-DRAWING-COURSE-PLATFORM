package com.artacademy.bundle;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BundleItemRepository extends JpaRepository<BundleItem, Long> {

    /** Khóa của nhiều combo trong 1 câu truy vấn, giữ thứ tự admin đã chọn (theo id thêm vào). */
    @Query("select i from BundleItem i join fetch i.course where i.bundle.id in :bundleIds order by i.id")
    List<BundleItem> findByBundles(@Param("bundleIds") Collection<Long> bundleIds);

    /** Xóa hết khóa của combo bằng DELETE chạy ngay (tránh lỗi unique khi thêm lại cùng khóa, xem CourseKitRepository). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from BundleItem i where i.bundle.id = :bundleId")
    void deleteByBundle(@Param("bundleId") Long bundleId);
}
