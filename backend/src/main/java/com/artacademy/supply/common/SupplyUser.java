package com.artacademy.supply.common;

import com.artacademy.enums.Role;

/** Người dùng đang gọi API, lấy từ JWT của A. */
public record SupplyUser(Integer id, Role role) {

    /** ADMIN hoặc STAFF: được xem đơn giao của mọi người. */
    public boolean canManage() {
        return role == Role.ADMIN || role == Role.STAFF;
    }
}
