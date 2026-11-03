package com.artacademy.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.bundle.dto.BundleRequest;
import com.artacademy.common.NotFoundException;
import com.artacademy.course.Course;
import com.artacademy.course.CourseIncludedKit;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseStatus;
import com.artacademy.course.integration.SellableCourse.SellableKit;
import com.artacademy.course.integration.SellableCourse.UnavailableKit;
import com.artacademy.product.ProductCatalog.KitProduct;
import com.artacademy.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Test hợp đồng bàn giao cho D: giới hạn 2-10 khóa, vị trí, definitionVersion, snapshot bất biến,
 * requireSellable(id, expectedVersion), combo không còn bán được và quà tặng kèm.
 */
class BundleSnapshotTest extends IntegrationTestBase {

    @Autowired BundleSellableService service;

    // ------------------------------------------------------------------ hỗ trợ

    private List<Course> courses(int n) {
        List<Course> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(publishedCourse("Khóa " + i, 100_000, null, CourseLevel.BASIC));
        }
        return list;
    }

    private static List<Integer> ids(List<Course> courses) {
        return courses.stream().map(Course::getId).toList();
    }

    /** Giá combo luôn nhỏ hơn tổng giá các khóa (mỗi khóa 100.000, tối thiểu 2 khóa = 200.000). */
    private BundleRequest request(String code, String title, String description, long price, List<Integer> courseIds) {
        return new BundleRequest(code, title, description, price, courseIds, null);
    }

    private int create(List<Course> courses) throws Exception {
        MvcResult r = postJson("/api/admin/bundles", request(null, "Combo", "Mô tả", 150_000, ids(courses)))
                .andExpect(status().isCreated()).andReturn();
        return readInt(r, "/id");
    }

    private void update(int id, BundleRequest req) throws Exception {
        putJson("/api/admin/bundles/" + id, req).andExpect(status().isOk());
    }

    private int version(int id) {
        return service.getSnapshot(id).definitionVersion();
    }

    // ------------------------------------------------------------------ 2 đến 10 khóa

    @Test
    void courseCount_acceptsTwoToTen_rejectsOneAndEleven() throws Exception {
        List<Course> eleven = courses(11);
        String message = "Combo cần từ 2 đến 10 khóa học";

        postJson("/api/admin/bundles", request(null, "Một khóa", null, 50_000, ids(eleven.subList(0, 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(message));
        postJson("/api/admin/bundles", request(null, "Mười một khóa", null, 500_000, ids(eleven)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(message));

        postJson("/api/admin/bundles", request(null, "Hai khóa", null, 150_000, ids(eleven.subList(0, 2))))
                .andExpect(status().isCreated());
        postJson("/api/admin/bundles", request(null, "Mười khóa", null, 900_000, ids(eleven.subList(0, 10))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.courses.length()").value(10));
    }

    @Test
    void courseCount_isAlsoCheckedOnUpdate_andServiceLevel() throws Exception {
        List<Course> eleven = courses(11);
        int id = create(eleven.subList(0, 3));

        putJson("/api/admin/bundles/" + id, request(null, "Combo", null, 150_000, ids(eleven)))
                .andExpect(status().isBadRequest());
        putJson("/api/admin/bundles/" + id, request(null, "Combo", null, 150_000, ids(eleven.subList(0, 1))))
                .andExpect(status().isBadRequest());
        assertEquals(1, version(id), "yêu cầu bị từ chối thì không đổi version");
        assertEquals(3, service.getSnapshot(id).courses().size());
    }

    @Test
    void duplicateCourses_areRejected() throws Exception {
        List<Course> three = courses(3);
        Integer first = three.get(0).getId();

        postJson("/api/admin/bundles", request(null, "Trùng", null, 150_000, List.of(first, three.get(1).getId(), first)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Một khóa học chỉ được chọn một lần trong combo"));
    }

    // ------------------------------------------------------------------ vị trí

    @Test
    void positions_followChosenOrder_andReorderingChangesThem() throws Exception {
        List<Course> c = courses(3);
        int id = create(c);

        BundleSnapshot first = service.getSnapshot(id);
        assertEquals(ids(c), first.courseIds());
        assertEquals(List.of(1, 2, 3), first.courses().stream().map(BundleSnapshot.Component::position).toList());

        List<Integer> reordered = List.of(c.get(2).getId(), c.get(0).getId(), c.get(1).getId());
        update(id, request(null, "Combo", "Mô tả", 150_000, reordered));

        BundleSnapshot second = service.getSnapshot(id);
        assertEquals(reordered, second.courseIds());
        assertEquals(List.of(1, 2, 3), second.courses().stream().map(BundleSnapshot.Component::position).toList());
    }

    // ------------------------------------------------------------------ definitionVersion

    @Test
    void definitionVersion_startsAtOne_andIncreasesOnlyWhenDefinitionChanges() throws Exception {
        List<Course> c = courses(4);
        List<Integer> two = ids(c.subList(0, 2));
        int id = create(c.subList(0, 2));
        assertEquals(1, version(id));

        // Không đổi gì, hoặc chỉ đổi mô tả: không tăng
        update(id, request(null, "Combo", "Mô tả", 150_000, two));
        assertEquals(1, version(id));
        update(id, request(null, "Combo", "Mô tả khác hoàn toàn", 150_000, two));
        assertEquals(1, version(id));

        // Đổi tên: tăng
        update(id, request(null, "Combo đổi tên", "Mô tả", 150_000, two));
        assertEquals(2, version(id));

        // Đổi giá: tăng
        update(id, request(null, "Combo đổi tên", "Mô tả", 160_000, two));
        assertEquals(3, version(id));

        // Thêm khóa: tăng
        update(id, request(null, "Combo đổi tên", "Mô tả", 160_000, ids(c.subList(0, 3))));
        assertEquals(4, version(id));

        // Đổi thứ tự khóa: tăng
        update(id, request(null, "Combo đổi tên", "Mô tả", 160_000,
                List.of(c.get(2).getId(), c.get(0).getId(), c.get(1).getId())));
        assertEquals(5, version(id));

        // Đổi mã: tăng
        update(id, request("CB-MOI", "Combo đổi tên", "Mô tả", 160_000,
                List.of(c.get(2).getId(), c.get(0).getId(), c.get(1).getId())));
        assertEquals(6, version(id));
        assertEquals("CB-MOI", service.getSnapshot(id).code());

        // Chỉ bật/tắt combo: KHÔNG tăng
        patchJson("/api/admin/bundles/" + id + "/active", Map.of("active", false)).andExpect(status().isOk());
        assertEquals(6, version(id));
        patchJson("/api/admin/bundles/" + id + "/active", Map.of("active", true)).andExpect(status().isOk());
        assertEquals(6, version(id));

        getJson("/api/admin/bundles/" + id).andExpect(jsonPath("$.data.definitionVersion").value(6));
    }

    @Test
    void changingAnIncludedCourse_doesNotChangeTheBundleVersion() throws Exception {
        List<Course> c = courses(2);
        int id = create(c);

        Course first = courseRepository.findById(c.get(0).getId()).orElseThrow();
        first.setTitle("Tên mới của khóa");
        courseRepository.save(first);

        assertEquals(1, version(id));
        assertEquals("Tên mới của khóa", service.getSnapshot(id).courses().get(0).title());
    }

    // ------------------------------------------------------------------ snapshot

    @Test
    void snapshot_containsAllFields_andIsImmutable() throws Exception {
        Course a = publishedCourse("Khóa A", 500_000, null, CourseLevel.BASIC);
        Course b = publishedCourse("Khóa B", 600_000, 400_000L, CourseLevel.ADVANCED);
        KitProduct brush = newProduct("BRUSH", true);
        newKit(a, brush, 2);
        MvcResult r = postJson("/api/admin/bundles", request("CB-X", "Combo X", "Mô tả", 800_000, ids(List.of(a, b))))
                .andExpect(status().isCreated()).andReturn();
        int id = readInt(r, "/id");

        BundleSnapshot s = service.getSnapshot(id);

        assertEquals(id, s.bundleId());
        assertEquals("CB-X", s.code());
        assertEquals("Combo X", s.title());
        assertEquals(800_000, s.priceVnd());
        assertEquals(1, s.definitionVersion());
        assertEquals(BundleSellableStatus.AVAILABLE, s.status());
        assertNull(s.statusReason());
        assertTrue(s.isSellable());
        assertEquals(900_000, s.originalTotalVnd());
        assertEquals(2, s.courses().size());

        BundleSnapshot.Component first = s.courses().get(0);
        assertEquals(a.getId(), first.courseId());
        assertEquals(1, first.position());
        assertEquals("Khóa A", first.title());
        assertEquals(500_000, first.effectivePriceVnd());
        assertEquals(1, first.kits().size());
        assertEquals("BRUSH", first.kits().get(0).sku());
        assertEquals(400_000, s.courses().get(1).effectivePriceVnd(), "giá thực bán = giá giảm nếu có");

        // Sửa combo sau đó: snapshot đã lấy không bị đổi, và các danh sách không sửa được
        update(id, request("CB-X", "Combo đã đổi tên", "Mô tả", 750_000, ids(List.of(a, b))));
        assertEquals("Combo X", s.title());
        assertEquals(1, s.definitionVersion());
        assertThrows(UnsupportedOperationException.class, () -> s.courses().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.kits().clear());
        assertEquals(2, service.getSnapshot(id).definitionVersion());
    }

    @Test
    void snapshot_ofMissingBundle_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> service.getSnapshot(99999));
        assertThrows(NotFoundException.class, () -> service.requireSellable(99999, 1));
    }

    // ------------------------------------------------------------------ requireSellable / expectedVersion

    @Test
    void requireSellable_returnsSnapshotWhenVersionMatches() throws Exception {
        int id = create(courses(2));

        BundleSnapshot s = service.requireSellable(id, 1);

        assertEquals(1, s.definitionVersion());
        assertTrue(s.isSellable());
    }

    @Test
    void requireSellable_throwsChangedWithBothVersionsWhenBundleWasEdited() throws Exception {
        List<Course> c = courses(2);
        int id = create(c);
        update(id, request(null, "Combo mới", "Mô tả", 160_000, ids(c)));

        BundleChangedException e = assertThrows(BundleChangedException.class, () -> service.requireSellable(id, 1));

        assertEquals(1, e.getExpectedVersion());
        assertEquals(2, e.getActualVersion());
        assertEquals(409, e.getStatus().value());
        // Với version mới thì mua được
        assertEquals(2, service.requireSellable(id, 2).definitionVersion());
    }

    @Test
    void requireSellable_throwsNotSellableWhenBundleIsInactive_evenIfVersionMatches() throws Exception {
        int id = create(courses(2));
        patchJson("/api/admin/bundles/" + id + "/active", Map.of("active", false)).andExpect(status().isOk());

        BundleNotSellableException e = assertThrows(BundleNotSellableException.class,
                () -> service.requireSellable(id, 1));

        assertEquals(BundleSellableStatus.INACTIVE, e.getSellableStatus());
        assertEquals(409, e.getStatus().value());
        BundleSnapshot s = service.getSnapshot(id);
        assertEquals(BundleSellableStatus.INACTIVE, s.status());
        assertFalse(s.isSellable());
        assertEquals(2, s.courses().size(), "snapshot vẫn đầy đủ để biết lý do");
    }

    @Test
    void requireSellable_throwsNotSellableWhenACourseIsNoLongerPublished() throws Exception {
        List<Course> c = courses(3);
        int id = create(c);
        Course second = courseRepository.findById(c.get(1).getId()).orElseThrow();
        second.setStatus(CourseStatus.HIDDEN);
        courseRepository.save(second);

        BundleNotSellableException e = assertThrows(BundleNotSellableException.class,
                () -> service.requireSellable(id, 1));

        assertEquals(BundleSellableStatus.COURSE_NOT_PUBLISHED, e.getSellableStatus());
        assertEquals(BundleSellableStatus.COURSE_NOT_PUBLISHED, service.getSnapshot(id).status());
        // Và vẫn không bán được ở API công khai
        getJson("/api/bundles/" + id).andExpect(status().isNotFound());
        assertThrows(NotFoundException.class, () -> service.getSellableBundle(id));
    }

    @Test
    void requireSellable_reportsNotSellableBeforeVersionMismatch() throws Exception {
        List<Course> c = courses(2);
        int id = create(c);
        update(id, request(null, "Combo mới", "Mô tả", 160_000, ids(c)));
        patchJson("/api/admin/bundles/" + id + "/active", Map.of("active", false)).andExpect(status().isOk());

        assertThrows(BundleNotSellableException.class, () -> service.requireSellable(id, 1));
    }

    @Test
    void oldContract_getSellableBundle_nowAlsoReturnsTheVersion() throws Exception {
        int id = create(courses(2));

        BundleSellableService.SellableBundle s = service.getSellableBundle(id);

        assertEquals(1, s.definitionVersion());
        assertEquals(150_000, s.price());
        assertEquals(2, s.courses().size());
    }

    // ------------------------------------------------------------------ quà tặng kèm

    @Test
    void kitTotals_sumSameProductAcrossCourses_andKeepPerCourseLists() throws Exception {
        List<Course> c = courses(3);
        KitProduct brush = newProduct("BRUSH", true);
        KitProduct paper = newProduct("PAPER", true);
        newKit(c.get(0), brush, 2);
        newKit(c.get(1), brush, 3);
        newKit(c.get(1), paper, 1);
        int id = create(c);

        BundleSnapshot s = service.getSnapshot(id);

        List<SellableKit> totals = s.kitTotals();
        assertEquals(2, totals.size());
        assertEquals("BRUSH", totals.get(0).sku());
        assertEquals(5, totals.get(0).quantity());
        assertEquals("PAPER", totals.get(1).sku());
        assertEquals(1, totals.get(1).quantity());
        // Danh sách theo từng khóa vẫn tách riêng để D snapshot
        assertEquals(1, s.courses().get(0).kits().size());
        assertEquals(2, s.courses().get(1).kits().size());
        assertEquals(0, s.courses().get(2).kits().size());
    }

    @Test
    void unavailableKits_areReportedInsteadOfSilentlyDropped() throws Exception {
        List<Course> c = courses(2);
        KitProduct active = newProduct("OK", true);
        KitProduct off = newProduct("OFF", true);
        newKit(c.get(0), active, 1);
        newKit(c.get(0), off, 2);
        kitRepository.save(new CourseIncludedKit(c.get(1), 424242, 4)); // sản phẩm E không còn
        int id = create(c);
        productCatalog.setActive(off.id(), false);

        BundleSnapshot s = service.getSnapshot(id);

        assertEquals(List.of("OK"), s.kitTotals().stream().map(SellableKit::sku).toList());
        List<UnavailableKit> unavailable = s.unavailableKits();
        assertEquals(2, unavailable.size());
        assertEquals(off.id(), unavailable.get(0).productId());
        assertEquals(UnavailableKit.INACTIVE, unavailable.get(0).reason());
        assertEquals(2, unavailable.get(0).quantity());
        assertEquals(424242, unavailable.get(1).productId());
        assertEquals(UnavailableKit.NOT_FOUND, unavailable.get(1).reason());
        // Quà không bán được không làm combo mất khả năng bán (chính sách chờ D và E chốt)
        assertTrue(s.isSellable());
    }

    @Test
    void mergeByProduct_sumsQuantities_keepsFirstSeenOrder_andIsUnmodifiable() {
        SellableKit a1 = new SellableKit(7, "A", "Sản phẩm A", 2);
        SellableKit b = new SellableKit(8, "B", "Sản phẩm B", 1);
        SellableKit a2 = new SellableKit(7, "A", "Sản phẩm A", 5);

        List<SellableKit> merged = SellableKit.mergeByProduct(List.of(a1, b, a2));

        assertEquals(List.of(7, 8), merged.stream().map(SellableKit::productId).toList());
        assertEquals(7, merged.get(0).quantity());
        assertEquals(1, merged.get(1).quantity());
        assertThrows(UnsupportedOperationException.class, () -> merged.add(a1));
        assertTrue(SellableKit.mergeByProduct(List.of()).isEmpty());
    }
}
