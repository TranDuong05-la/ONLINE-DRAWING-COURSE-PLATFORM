package com.artacademy.supply.review;

import java.time.LocalDateTime;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ReviewDtos {
    private ReviewDtos() {
    }

    public record ReviewRequest(
            @NotNull(message = "Chọn số sao") @Min(value = 1, message = "Tối thiểu 1 sao")
            @Max(value = 5, message = "Tối đa 5 sao") Integer rating,
            @Size(max = 1000, message = "Nhận xét tối đa 1000 ký tự") String comment) {
    }

    public record ReviewResponse(Long id, Long productId, Integer userId, String reviewerName,
            String reviewerAvatarUrl, int rating, String comment, boolean mine, LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * reason: DELIVERED_OK | NOT_PURCHASED | NOT_DELIVERED | ALREADY_REVIEWED
     * (ALREADY_REVIEWED vẫn cho sửa, myReview có dữ liệu.)
     */
    public record ReviewEligibility(boolean canReview, String reason, String message, ReviewResponse myReview) {
    }

    public record ReviewSummary(double average, long count, Map<Integer, Long> histogram) {
    }
}
