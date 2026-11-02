package com.artacademy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminDashboardResponse {

    private long totalUsers;
    private long totalInstructors;
    private long totalStaff;
    private long totalAdmins;
    private long activeUsers;
    private long inactiveUsers;
    private long totalCourses;
    private long totalOrders;
    private BigDecimal totalRevenue;
}
