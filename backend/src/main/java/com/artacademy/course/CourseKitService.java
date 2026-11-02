package com.artacademy.course;

import com.artacademy.common.BadRequestException;
import com.artacademy.common.NotFoundException;
import com.artacademy.course.dto.KitDto;
import com.artacademy.course.dto.KitsRequest;
import com.artacademy.product.Product;
import com.artacademy.product.ProductRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cấu hình dụng cụ tặng kèm của khóa học. */
@Service
@Transactional
public class CourseKitService {

    private final CourseRepository courseRepository;
    private final CourseKitRepository kitRepository;
    private final ProductRepository productRepository;
    private final CourseMapper mapper;

    public CourseKitService(CourseRepository courseRepository, CourseKitRepository kitRepository,
                            ProductRepository productRepository, CourseMapper mapper) {
        this.courseRepository = courseRepository;
        this.kitRepository = kitRepository;
        this.productRepository = productRepository;
        this.mapper = mapper;
    }

    /**
     * Thay TOÀN BỘ danh sách quà của khóa bằng danh sách mới.
     * Quy tắc: sản phẩm phải tồn tại và đang active, số lượng 1-99, không trùng sản phẩm.
     */
    public List<KitDto> replaceKits(Long courseId, KitsRequest req) {
        if (!courseRepository.existsById(courseId)) {
            throw new NotFoundException("Không tìm thấy khóa học");
        }

        // Kiểm tra hết trước khi xóa quà cũ, để lỗi thì không mất dữ liệu cũ
        Set<Long> seen = new HashSet<>();
        List<Product> products = new ArrayList<>();
        for (KitsRequest.Item item : req.items()) {
            if (!seen.add(item.productId())) {
                throw new BadRequestException("Một sản phẩm chỉ được chọn một lần trong danh sách quà");
            }
            Product product = productRepository.findById(item.productId())
                    .orElseThrow(() -> new BadRequestException("Sản phẩm id " + item.productId() + " không tồn tại"));
            if (!product.isActive()) {
                throw new BadRequestException("Sản phẩm \"" + product.getName() + "\" đã ngừng bán, không thể tặng kèm");
            }
            products.add(product);
        }

        kitRepository.deleteByCourse(courseId);
        Course course = courseRepository.getReferenceById(courseId);
        for (int i = 0; i < products.size(); i++) {
            kitRepository.save(new CourseIncludedKit(course, products.get(i), req.items().get(i).quantity()));
        }
        return kitRepository.findByCourse(courseId).stream().map(mapper::toKit).toList();
    }
}
