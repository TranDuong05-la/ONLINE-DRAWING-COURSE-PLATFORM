package com.artacademy.course;

import com.artacademy.common.TextUtils;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Điều kiện lọc/sắp xếp danh sách khóa. Dùng Specification (API chuẩn của JPA) thay vì viết SQL riêng,
 * nên chạy giống nhau trên SQL Server, MySQL và H2.
 */
public final class CourseSpecs {

    private CourseSpecs() {
    }

    /** Lọc theo trạng thái, cấp độ và từ khóa (tìm trong tên, không phân biệt hoa thường). Tham số null = bỏ qua. */
    public static Specification<Course> filter(CourseStatus status, CourseLevel level, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (status != null) {
                conditions.add(cb.equal(root.get("status"), status));
            }
            if (level != null) {
                conditions.add(cb.equal(root.get("level"), level));
            }
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + TextUtils.escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
                conditions.add(cb.like(cb.lower(root.get("title")), pattern, '\\'));
            }
            return cb.and(conditions.toArray(new Predicate[0]));
        };
    }

    /**
     * Sắp xếp theo GIÁ THỰC BÁN = coalesce(giá giảm, giá gốc).
     * Điều kiện sắp xếp được gắn vào query ở đây vì Sort của Spring Data không sắp theo biểu thức được.
     * Câu đếm tổng (count) của phân trang có kiểu kết quả Long, không được có ORDER BY nên phải bỏ qua.
     */
    public static Specification<Course> orderByEffectivePrice(boolean ascending) {
        return (root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                Expression<Long> effective = cb.coalesce(root.<Long>get("discountPrice"), root.<Long>get("price"));
                query.orderBy(ascending ? cb.asc(effective) : cb.desc(effective), cb.desc(root.get("id")));
            }
            return cb.conjunction();
        };
    }
}
