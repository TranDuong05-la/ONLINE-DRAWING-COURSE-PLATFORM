package com.artacademy.supply;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.artacademy.supply.api.ReserveLine;
import com.artacademy.supply.api.ShippingInfo;
import com.artacademy.supply.api.SupplyFulfillmentApi;

@AutoConfigureMockMvc
class SupplyApiSecurityTest extends SupplyTestSupport {

    @Autowired
    MockMvc mvc;
    @Autowired
    SupplyFulfillmentApi fulfillment;
    @Autowired
    UserRepository userRepository;

    private User learner;
    private User other;

    @BeforeEach
    void users() {
        learner = ensureUser("supply.learner@test.com", Role.USER);
        other = ensureUser("supply.other@test.com", Role.USER);
        ensureUser("supply.staff@test.com", Role.STAFF);
        ensureUser("supply.admin@test.com", Role.ADMIN);
    }

    private User ensureUser(String email, Role role) {
        return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder()
                .fullName(email).email(email).passwordHash("x").role(role).build()));
    }

    private static RequestPostProcessor as(String email, Role role) {
        return user(email).roles(role.name());
    }

    private static final RequestPostProcessor LEARNER = as("supply.learner@test.com", Role.USER);
    private static final RequestPostProcessor OTHER = as("supply.other@test.com", Role.USER);
    private static final RequestPostProcessor STAFF_USER = as("supply.staff@test.com", Role.STAFF);
    private static final RequestPostProcessor ADMIN_USER = as("supply.admin@test.com", Role.ADMIN);

    private Long paidShipment(User buyer) {
        Long productId = newProduct(10_000, 3);
        int orderId = nextId();
        fulfillment.reserveForOrder(orderId, buyer.getId(), List.of(ReserveLine.purchase(productId, 1)));
        return fulfillment.onOrderPaid(orderId, buyer.getId(), new ShippingInfo("A", "090", "HCM")).orElseThrow();
    }

    @Test
    void hocVienXemDuocCuaHang_khongThayHangAn() throws Exception {
        Long visible = newProduct(10_000, 3);
        Long hidden = newProduct(10_000, 3, false);
        mvc.perform(get("/api/supplies/products/" + visible).with(LEARNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.inStock").value(true))
                .andExpect(jsonPath("$.data.stockOnHand").doesNotExist());
        mvc.perform(get("/api/supplies/products/" + hidden).with(LEARNER)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void chuaDangNhap_bi401() throws Exception {
        mvc.perform(get("/api/supplies/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/shipments/me").with(SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void hocVienKhongGoiDuocApiQuanLy() throws Exception {
        mvc.perform(get("/api/staff/supplies/products").with(LEARNER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/staff/shipments").with(LEARNER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/supplies/categories").with(LEARNER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\"}")).andExpect(status().isForbidden());
    }

    @Test
    void staffQuanLyKhoVaGiaoHang_nhungKhongTaoSanPham() throws Exception {
        mvc.perform(get("/api/staff/supplies/products").with(STAFF_USER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
        mvc.perform(get("/api/staff/shipments/summary").with(STAFF_USER)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/supplies/products").with(STAFF_USER).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/staff/supplies/products").with(ADMIN_USER)).andExpect(status().isOk());
    }

    @Test
    void khongXemDuocGiaoHangCuaNguoiKhac() throws Exception {
        Long shipmentId = paidShipment(learner);
        mvc.perform(get("/api/shipments/" + shipmentId).with(LEARNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].quantity").value(1))
                .andExpect(jsonPath("$.data.nextStatuses").isEmpty());
        mvc.perform(get("/api/shipments/" + shipmentId).with(OTHER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/shipments/" + shipmentId).with(STAFF_USER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextStatuses[0]").value("SHIPPING"));
    }

    @Test
    void doiTrangThaiSai_bao400() throws Exception {
        Long shipmentId = paidShipment(other);
        mvc.perform(patch("/api/staff/shipments/" + shipmentId + "/status").with(STAFF_USER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void taoSanPhamSaiDuLieu_baoLoiTungTruong() throws Exception {
        mvc.perform(post("/api/admin/supplies/products").with(ADMIN_USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"!\",\"price\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'price')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'sku')]").exists());
    }

    @Test
    void danhGiaChuaNhanHang_bi403() throws Exception {
        Long productId = newProduct(10_000, 3);
        mvc.perform(post("/api/supplies/products/" + productId + "/reviews").with(LEARNER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5}"))
                .andExpect(status().isForbidden());
    }
}
