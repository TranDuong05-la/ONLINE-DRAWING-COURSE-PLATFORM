package com.drawingcourse.backend.integration.supply;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Địa chỉ giao hàng không được thừa dấu phẩy khi thiếu mã bưu chính. */
class ShippingAddressFormatTest {

    @Test
    void joinsAllPartsWhenPresent() {
        assertEquals("2 Xuân Thủy, Hà Nội, 100000, VN",
                SupplyInventoryAdapter.formatAddress("2 Xuân Thủy", "Hà Nội", "100000", "VN"));
    }

    @Test
    void skipsBlankPostalCodeWithoutLeavingAnEmptyGap() {
        assertEquals("2 Xuân Thủy, Hà Nội, VN",
                SupplyInventoryAdapter.formatAddress("2 Xuân Thủy", "Hà Nội", "", "VN"));
        assertEquals("2 Xuân Thủy, Hà Nội, VN",
                SupplyInventoryAdapter.formatAddress("2 Xuân Thủy", "Hà Nội", "   ", "VN"));
        assertEquals("2 Xuân Thủy, Hà Nội, VN",
                SupplyInventoryAdapter.formatAddress("2 Xuân Thủy", "Hà Nội", null, "VN"));
    }

    @Test
    void trimsEachPart() {
        assertEquals("1 Đường A, Quận 1, VN",
                SupplyInventoryAdapter.formatAddress(" 1 Đường A ", " Quận 1", "", " VN "));
    }
}
