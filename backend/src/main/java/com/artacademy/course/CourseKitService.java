package com.artacademy.course;

import com.artacademy.common.BadRequestException;
import com.artacademy.common.NotFoundException;
import com.artacademy.course.dto.KitDto;
import com.artacademy.course.dto.KitsRequest;
import com.artacademy.product.ProductCatalog;
import com.artacademy.product.ProductCatalog.KitProduct;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cấu hình dụng cụ tặng kèm của khóa học. Sản phẩm thuộc E, tra qua {@link ProductCatalog}. */
@Service
@Transactional
public class CourseKitService {

    private final CourseRepository courseRepository;
    private final CourseKitRepository kitRepository;
    private final ProductCatalog productCatalog;
    private final CourseKitLookup kitLookup;

    public CourseKitService(CourseRepository courseRepository, CourseKitRepository kitRepository,
                            ProductCatalog productCatalog, CourseKitLookup kitLookup) {
        this.courseRepository = courseRepository;
        this.kitRepository = kitRepository;
        this.productCatalog = productCatalog;
        this.kitLookup = kitLookup;
    }

    /**
     * Thay TOÀN BỘ danh sách quà của khóa bằng danh sách mới.
     * Quy tắc: sản phẩm phải tồn tại và đang active, số lượng 1-99, không trùng sản phẩm.
     */
    public List<KitDto> replaceKits(Integer courseId, KitsRequest req) {
        if (!courseRepository.existsById(courseId)) {
            throw new NotFoundException("Không tìm thấy khóa học");
        }

        // Kiểm tra hết trước khi xóa quà cũ, để lỗi thì không mất dữ liệu cũ
        Set<Integer> seen = new HashSet<>();
        for (KitsRequest.Item item : req.items()) {
            if (!seen.add(item.productId())) {
                throw new BadRequestException("Một sản phẩm chỉ được chọn một lần trong danh sách quà");
            }
        }
        Map<Integer, KitProduct> products = productCatalog.findByIds(seen);
        for (KitsRequest.Item item : req.items()) {
            KitProduct product = products.get(item.productId());
            if (product == null) {
                throw new BadRequestException("Sản phẩm id " + item.productId() + " không tồn tại");
            }
            if (!product.active()) {
                throw new BadRequestException("Sản phẩm \"" + product.name() + "\" đã ngừng bán, không thể tặng kèm");
            }
        }

        kitRepository.deleteByCourse(courseId);
        Course course = courseRepository.getReferenceById(courseId);
        for (KitsRequest.Item item : req.items()) {
            kitRepository.save(new CourseIncludedKit(course, item.productId(), item.quantity()));
        }
        return kitLookup.adminKits(courseId);
    }
}
