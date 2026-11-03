package com.artacademy.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.artacademy.supply.common.SupplyException;
import com.artacademy.supply.common.SupplyUser;
import com.artacademy.supply.api.ReserveLine;
import com.artacademy.supply.api.ShippingInfo;
import com.artacademy.supply.api.SupplyFulfillmentApi;
import com.artacademy.supply.catalog.CatalogDtos.ProductResponse;
import com.artacademy.supply.catalog.CatalogDtos.ProductRequest;
import com.artacademy.supply.inventory.InventoryTransactionRepository;
import com.artacademy.supply.inventory.InventoryTransactionType;
import com.artacademy.supply.review.ReviewDtos.ReviewRequest;
import com.artacademy.supply.review.ReviewService;
import com.artacademy.supply.shipment.ShipmentDtos.UpdateShipmentStatusRequest;
import com.artacademy.supply.shipment.ShipmentRepository;
import com.artacademy.supply.shipment.ShipmentService;
import com.artacademy.supply.shipment.ShipmentStatus;

class SupplyFulfillmentTest extends SupplyTestSupport {

    private static final ShippingInfo ADDRESS = new ShippingInfo("Nguyễn Văn A", "0900000000", "1 Lê Lợi, Q1, HCM");

    @Autowired
    SupplyFulfillmentApi fulfillment;
    @Autowired
    ShipmentService shipmentService;
    @Autowired
    ShipmentRepository shipmentRepository;
    @Autowired
    ReviewService reviewService;
    @Autowired
    InventoryTransactionRepository transactionRepository;

    private Long paidOrder(SupplyUser user, ReserveLine... lines) {
        int orderId = nextId();
        fulfillment.reserveForOrder(orderId, user.id(), List.of(lines));
        return fulfillment.onOrderPaid(orderId, user.id(), ADDRESS).orElseThrow();
    }

    private void move(Long shipmentId, ShipmentStatus to) {
        shipmentService.updateStatus(shipmentId, new UpdateShipmentStatusRequest(to, null, null, null), STAFF);
    }

    private static String code(Throwable t) {
        return ((SupplyException) t).getCode();
    }

    // ---------- Giữ hàng ----------

    @Test
    void haiNguoiMuaMonCuoiCungLuc_chiMotNguoiGiuDuoc_khongAmKho() throws Exception {
        Long productId = newProduct(100_000, 1);
        int buyers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            int orderId = nextId();
            int userId = nextId();
            Callable<Boolean> task = () -> {
                start.await();
                try {
                    fulfillment.reserveForOrder(orderId, userId, List.of(ReserveLine.purchase(productId, 1)));
                    return true;
                } catch (SupplyException e) {
                    assertThat(e.getCode()).isEqualTo("OUT_OF_STOCK");
                    return false;
                }
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int success = 0;
        for (Future<Boolean> f : results) {
            if (f.get(30, TimeUnit.SECONDS)) {
                success++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        ProductResponse p = stock(productId);
        assertThat(p.stockOnHand()).isEqualTo(1);
        assertThat(p.stockReserved()).isEqualTo(1);
        assertThat(p.available()).isZero();
    }

    @Test
    void thieuMotMon_khongGiuMonNao() {
        Long enough = newProduct(50_000, 10);
        Long scarce = newProduct(50_000, 1);
        int orderId = nextId();

        assertThatThrownBy(() -> fulfillment.reserveForOrder(orderId, nextId(),
                List.of(ReserveLine.purchase(enough, 2), ReserveLine.purchase(scarce, 2))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("OUT_OF_STOCK"));

        assertThat(stock(enough).stockReserved()).isZero();
        assertThat(stock(scarce).stockReserved()).isZero();
        assertThat(transactionRepository.findByOrderIdOrderByIdAsc(orderId)).isEmpty();
    }

    @Test
    void goiGiuHangLap_khongGiuHaiLan() {
        Long productId = newProduct(50_000, 10);
        int orderId = nextId();
        int userId = nextId();
        fulfillment.reserveForOrder(orderId, userId, List.of(ReserveLine.purchase(productId, 3)));
        fulfillment.reserveForOrder(orderId, userId, List.of(ReserveLine.purchase(productId, 3)));
        assertThat(stock(productId).stockReserved()).isEqualTo(3);
    }

    @Test
    void sanPhamAn_khongBanNhungVanGiuDuocLamQua() {
        Long kit = newProduct(0, 5, false);

        assertThatThrownBy(() -> fulfillment.reserveForOrder(nextId(), nextId(),
                List.of(ReserveLine.purchase(kit, 1))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("PRODUCT_UNAVAILABLE"));

        fulfillment.reserveForOrder(nextId(), nextId(), List.of(ReserveLine.gift(kit, 1)));
        assertThat(stock(kit).stockReserved()).isEqualTo(1);
    }

    // ---------- Quà tặng ----------

    @Test
    void quaGia0_vanGiuVaXuatKhoNhuHangMua() {
        Long kit = newProduct(0, 5, false);
        Long pencil = newProduct(120_000, 5);
        SupplyUser user = student();

        Long shipmentId = paidOrder(user, ReserveLine.gift(kit, 1), ReserveLine.purchase(pencil, 2));
        assertThat(stock(kit).stockReserved()).isEqualTo(1);

        move(shipmentId, ShipmentStatus.SHIPPING);
        ProductResponse kitAfter = stock(kit);
        assertThat(kitAfter.stockOnHand()).isEqualTo(4);
        assertThat(kitAfter.stockReserved()).isZero();
        assertThat(stock(pencil).stockOnHand()).isEqualTo(3);
    }

    @Test
    void donThuanKhoaKhongQua_khongTaoGiaoHang_khongCanDiaChi() {
        int orderId = nextId();
        int userId = nextId();
        assertThat(fulfillment.reserveForOrder(orderId, userId, List.of())).isEmpty();
        assertThat(fulfillment.onOrderPaid(orderId, userId, null)).isEmpty();
        assertThat(shipmentRepository.findByOrderId(orderId)).isEmpty();
    }

    @Test
    void donCoHang_batBuocDiaChi() {
        Long productId = newProduct(10_000, 5);
        int orderId = nextId();
        int userId = nextId();
        fulfillment.reserveForOrder(orderId, userId, List.of(ReserveLine.purchase(productId, 1)));
        assertThatThrownBy(() -> fulfillment.onOrderPaid(orderId, userId, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("SHIPPING_INFO_REQUIRED"));
    }

    // ---------- Hủy / trả giữ ----------

    @Test
    void huyDon_traGiuDungMotLan() {
        Long productId = newProduct(10_000, 5);
        int orderId = nextId();
        int userId = nextId();
        fulfillment.reserveForOrder(orderId, userId, List.of(ReserveLine.purchase(productId, 2)));
        fulfillment.onOrderPaid(orderId, userId, ADDRESS);

        assertThat(fulfillment.releaseForOrder(orderId, "Hủy")).isEqualTo(1);
        assertThat(fulfillment.releaseForOrder(orderId, "Hủy lần 2")).isZero();

        ProductResponse p = stock(productId);
        assertThat(p.stockReserved()).isZero();
        assertThat(p.stockOnHand()).isEqualTo(5);
        assertThat(transactionRepository.countByOrderIdAndType(orderId, InventoryTransactionType.RELEASE))
                .isEqualTo(1);
        assertThat(shipmentRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void huySauKhiXuatKho_biTuChoi() {
        Long productId = newProduct(10_000, 5);
        int orderId = nextId();
        int userId = nextId();
        fulfillment.reserveForOrder(orderId, userId, List.of(ReserveLine.purchase(productId, 1)));
        Long shipmentId = fulfillment.onOrderPaid(orderId, userId, ADDRESS).orElseThrow();
        move(shipmentId, ShipmentStatus.SHIPPING);

        assertThatThrownBy(() -> fulfillment.releaseForOrder(orderId, "Hủy muộn"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ALREADY_SHIPPED"));
        assertThat(stock(productId).stockOnHand()).isEqualTo(4);
    }

    @Test
    void thanhToanBaoVeMuon_giuLaiNeuConHang() {
        Long productId = newProduct(10_000, 2);
        int orderId = nextId();
        int userId = nextId();
        fulfillment.reserveForOrder(orderId, userId, List.of(ReserveLine.purchase(productId, 2)));
        fulfillment.releaseForOrder(orderId, "Hết hạn thanh toán");

        assertThatThrownBy(() -> fulfillment.onOrderPaid(orderId, userId, ADDRESS))
                .satisfies(e -> assertThat(code(e)).isEqualTo("RESERVATION_RELEASED"));

        fulfillment.reserveForOrder(orderId, userId, List.of());
        assertThat(stock(productId).stockReserved()).isEqualTo(2);
        assertThat(fulfillment.onOrderPaid(orderId, userId, ADDRESS)).isPresent();
    }

    @Test
    void thanhToanBaoVeMuon_hetHang_baoOutOfStock() {
        Long productId = newProduct(10_000, 1);
        int late = nextId();
        int userId = nextId();
        fulfillment.reserveForOrder(late, userId, List.of(ReserveLine.purchase(productId, 1)));
        fulfillment.releaseForOrder(late, "Hết hạn");
        fulfillment.reserveForOrder(nextId(), nextId(), List.of(ReserveLine.purchase(productId, 1)));

        assertThatThrownBy(() -> fulfillment.reserveForOrder(late, userId, List.of()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("OUT_OF_STOCK"));
    }

    // ---------- Giao hàng ----------

    @Test
    void bamDangGiaoHaiLan_chiXuatKhoMotLan() {
        Long productId = newProduct(10_000, 5);
        Long shipmentId = paidOrder(student(), ReserveLine.purchase(productId, 2));
        move(shipmentId, ShipmentStatus.SHIPPING);

        assertThatThrownBy(() -> move(shipmentId, ShipmentStatus.SHIPPING))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVALID_TRANSITION"));
        assertThat(stock(productId).stockOnHand()).isEqualTo(3);
    }

    @Test
    void giaoThatBai_khongTuNhapKho_chiNhapKhiHangVe() {
        Long productId = newProduct(10_000, 5);
        Long shipmentId = paidOrder(student(), ReserveLine.purchase(productId, 2));
        move(shipmentId, ShipmentStatus.SHIPPING);
        move(shipmentId, ShipmentStatus.FAILED);
        assertThat(stock(productId).stockOnHand()).isEqualTo(3);

        move(shipmentId, ShipmentStatus.RETURNED);
        assertThat(stock(productId).stockOnHand()).isEqualTo(5);
        assertThatThrownBy(() -> move(shipmentId, ShipmentStatus.RETURNED))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVALID_TRANSITION"));
        assertThat(stock(productId).stockOnHand()).isEqualTo(5);
    }

    @Test
    void giaoLaiSauThatBai_khongXuatKhoLanHai() {
        Long productId = newProduct(10_000, 5);
        Long shipmentId = paidOrder(student(), ReserveLine.purchase(productId, 1));
        move(shipmentId, ShipmentStatus.SHIPPING);
        move(shipmentId, ShipmentStatus.FAILED);
        move(shipmentId, ShipmentStatus.SHIPPING);
        move(shipmentId, ShipmentStatus.DELIVERED);
        assertThat(stock(productId).stockOnHand()).isEqualTo(4);
        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getAttempts()).isEqualTo(2);
    }

    @Test
    void adminSuaSanPham_khongGhiDeSoTonDangGiu() {
        Long productId = newProduct(10_000, 5);
        ProductResponse before = stock(productId);
        fulfillment.reserveForOrder(nextId(), nextId(), List.of(ReserveLine.purchase(productId, 2)));
        catalogService.updateProduct(productId, new ProductRequest(before.categoryId(), before.sku(), "Tên mới",
                null, before.price(), null, 2, null, true));
        ProductResponse after = stock(productId);
        assertThat(after.name()).isEqualTo("Tên mới");
        assertThat(after.stockReserved()).isEqualTo(2);
        assertThat(after.stockOnHand()).isEqualTo(5);
    }

    // ---------- Đánh giá ----------

    @Test
    void chuaGiaoThanhCong_khongDuocDanhGia() {
        Long productId = newProduct(10_000, 5);
        SupplyUser buyer = student();
        Long shipmentId = paidOrder(buyer, ReserveLine.purchase(productId, 1));
        ReviewRequest req = new ReviewRequest(5, "Tốt");

        assertThatThrownBy(() -> reviewService.create(productId, req, buyer))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORBIDDEN"));
        assertThat(reviewService.eligibility(productId, buyer).reason()).isEqualTo("NOT_DELIVERED");

        move(shipmentId, ShipmentStatus.SHIPPING);
        move(shipmentId, ShipmentStatus.FAILED);
        assertThatThrownBy(() -> reviewService.create(productId, req, buyer))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORBIDDEN"));
    }

    @Test
    void khongMua_khongDuocDanhGia() {
        Long productId = newProduct(10_000, 5);
        SupplyUser stranger = student();
        assertThat(reviewService.eligibility(productId, stranger).reason()).isEqualTo("NOT_PURCHASED");
        assertThatThrownBy(() -> reviewService.create(productId, new ReviewRequest(4, null), stranger))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORBIDDEN"));
    }

    @Test
    void daNhanHang_danhGiaMotLan_capNhatDiemTrungBinh() {
        Long productId = newProduct(10_000, 5);
        SupplyUser a = student();
        SupplyUser b = student();
        for (SupplyUser u : List.of(a, b)) {
            Long sid = paidOrder(u, ReserveLine.purchase(productId, 1));
            move(sid, ShipmentStatus.SHIPPING);
            move(sid, ShipmentStatus.DELIVERED);
        }
        var ra = reviewService.create(productId, new ReviewRequest(5, "Rất tốt"), a);
        reviewService.create(productId, new ReviewRequest(4, null), b);

        assertThatThrownBy(() -> reviewService.create(productId, new ReviewRequest(1, null), a))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ALREADY_REVIEWED"));
        assertThat(stock(productId).ratingAvg()).isEqualTo(4.5);
        assertThat(stock(productId).ratingCount()).isEqualTo(2);

        reviewService.update(ra.id(), new ReviewRequest(3, "Sửa lại"), a);
        assertThat(stock(productId).ratingAvg()).isEqualTo(3.5);

        assertThatThrownBy(() -> reviewService.update(ra.id(), new ReviewRequest(1, null), b))
                .satisfies(e -> assertThat(code(e)).isEqualTo("FORBIDDEN"));
    }

    @Test
    void quaTang_cungDuocDanhGiaSauKhiNhan() {
        Long kit = newProduct(0, 5, false);
        SupplyUser buyer = student();
        Long sid = paidOrder(buyer, ReserveLine.gift(kit, 1));
        move(sid, ShipmentStatus.SHIPPING);
        move(sid, ShipmentStatus.DELIVERED);
        assertThat(reviewService.eligibility(kit, buyer).canReview()).isTrue();
        assertThat(Optional.ofNullable(reviewService.create(kit, new ReviewRequest(5, null), buyer))).isPresent();
    }
}
