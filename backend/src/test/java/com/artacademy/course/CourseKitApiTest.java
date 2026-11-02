package com.artacademy.course;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.course.dto.KitsRequest;
import com.artacademy.product.Product;
import com.artacademy.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Test quà tặng kèm (PUT /api/admin/courses/{id}/kits) và danh sách sản phẩm để chọn quà. */
class CourseKitApiTest extends IntegrationTestBase {

    private KitsRequest kits(Object... pairs) {
        java.util.ArrayList<KitsRequest.Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            items.add(new KitsRequest.Item((Long) pairs[i], (Integer) pairs[i + 1]));
        }
        return new KitsRequest(items);
    }

    @Test
    void productList_onlyContainsActiveProducts() throws Exception {
        newProduct("ACTIVE-1", true);
        newProduct("OFF-1", false);

        getJson("/api/admin/products")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sku").value("ACTIVE-1"));
    }

    @Test
    void replaceKits_savesAndReplacesWholeList() throws Exception {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        Product a = newProduct("A", true);
        Product b = newProduct("B", true);

        putJson("/api/admin/courses/" + c.getId() + "/kits", kits(a.getId(), 2, b.getId(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].sku").value("A"))
                .andExpect(jsonPath("$.data[0].quantity").value(2));

        // gửi lại danh sách khác (có cả sản phẩm cũ) phải thay thế hoàn toàn, không lỗi trùng unique
        putJson("/api/admin/courses/" + c.getId() + "/kits", kits(b.getId(), 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sku").value("B"))
                .andExpect(jsonPath("$.data[0].quantity").value(3));
        assertEquals(1, kitRepository.count());

        // danh sách rỗng = bỏ hết quà
        putJson("/api/admin/courses/" + c.getId() + "/kits", new KitsRequest(List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        assertEquals(0, kitRepository.count());
    }

    @Test
    void replaceKits_rejectsInvalidQuantity() throws Exception {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        Product a = newProduct("A", true);

        putJson("/api/admin/courses/" + c.getId() + "/kits", kits(a.getId(), 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].message").value("Số lượng quà phải từ 1 đến 99"));
        putJson("/api/admin/courses/" + c.getId() + "/kits", kits(a.getId(), 100)).andExpect(status().isBadRequest());
        putJson("/api/admin/courses/" + c.getId() + "/kits", kits(a.getId(), -1)).andExpect(status().isBadRequest());
        putJson("/api/admin/courses/" + c.getId() + "/kits", Map.of("items", List.of(Map.of("productId", a.getId()))))
                .andExpect(status().isBadRequest());
        assertEquals(0, kitRepository.count());
    }

    @Test
    void replaceKits_rejectsInactiveUnknownAndDuplicateProducts_andKeepsOldKits() throws Exception {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        Product active = newProduct("A", true);
        Product inactive = newProduct("OFF", false);
        newKit(c, active, 1);

        putJson("/api/admin/courses/" + c.getId() + "/kits", kits(inactive.getId(), 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("đã ngừng bán")));
        putJson("/api/admin/courses/" + c.getId() + "/kits", kits(99999L, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("không tồn tại")));
        putJson("/api/admin/courses/" + c.getId() + "/kits", kits(active.getId(), 1, active.getId(), 2))
                .andExpect(status().isBadRequest());
        putJson("/api/admin/courses/99999/kits", kits(active.getId(), 1)).andExpect(status().isNotFound());

        // quà cũ không bị mất khi yêu cầu mới bị từ chối
        assertEquals(1, kitRepository.count());
    }

    @Test
    void publicDetail_showsKits_butHidesProductsThatWereTurnedOff() throws Exception {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        Product a = newProduct("A", true);
        Product b = newProduct("B", true);
        newKit(c, a, 1);
        newKit(c, b, 2);

        getJson("/api/courses/" + c.getId())
                .andExpect(jsonPath("$.data.kits.length()").value(2))
                .andExpect(jsonPath("$.data.course.hasKits").value(true));

        // sản phẩm B ngừng bán -> ẩn khỏi học viên
        b.setActive(false);
        productRepository.save(b);
        getJson("/api/courses/" + c.getId())
                .andExpect(jsonPath("$.data.kits.length()").value(1))
                .andExpect(jsonPath("$.data.kits[0].sku").value("A"));

        // tắt nốt A -> không còn quà nào, nhãn "Tặng kèm dụng cụ" cũng biến mất
        a.setActive(false);
        productRepository.save(a);
        getJson("/api/courses/" + c.getId())
                .andExpect(jsonPath("$.data.kits.length()").value(0))
                .andExpect(jsonPath("$.data.course.hasKits").value(false));
    }
}
