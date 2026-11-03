package com.artacademy.supply.review;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.artacademy.dto.ApiResponse;
import com.artacademy.supply.common.PageResult;
import com.artacademy.supply.common.SupplyCurrentUser;
import com.artacademy.supply.review.ReviewDtos.ReviewEligibility;
import com.artacademy.supply.review.ReviewDtos.ReviewRequest;
import com.artacademy.supply.review.ReviewDtos.ReviewResponse;
import com.artacademy.supply.review.ReviewDtos.ReviewSummary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@Tag(name = "Supplies - Reviews", description = "Đánh giá học cụ sau khi nhận hàng (E)")
public class ReviewController {

    private final ReviewService reviewService;
    private final SupplyCurrentUser currentUser;

    public ReviewController(ReviewService reviewService, SupplyCurrentUser currentUser) {
        this.reviewService = reviewService;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/supplies/products/{productId}/reviews")
    @Operation(summary = "Danh sách đánh giá")
    public ApiResponse<PageResult<ReviewResponse>> list(@PathVariable Long productId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success("OK", reviewService.list(productId, currentUser.require(), page, size));
    }

    @GetMapping("/api/supplies/products/{productId}/reviews/summary")
    @Operation(summary = "Điểm trung bình và số đánh giá theo sao")
    public ApiResponse<ReviewSummary> summary(@PathVariable Long productId) {
        return ApiResponse.success("OK", reviewService.summary(productId));
    }

    @GetMapping("/api/supplies/products/{productId}/reviews/eligibility")
    @Operation(summary = "Tôi có được đánh giá học cụ này không")
    public ApiResponse<ReviewEligibility> eligibility(@PathVariable Long productId) {
        return ApiResponse.success("OK", reviewService.eligibility(productId, currentUser.require()));
    }

    @PostMapping("/api/supplies/products/{productId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Viết đánh giá (chỉ sau khi giao thành công)")
    public ApiResponse<ReviewResponse> create(@PathVariable Long productId, @Valid @RequestBody ReviewRequest req) {
        return ApiResponse.success("Cảm ơn bạn đã đánh giá",
                reviewService.create(productId, req, currentUser.require()));
    }

    @PutMapping("/api/supplies/reviews/{reviewId}")
    @Operation(summary = "Sửa đánh giá của mình")
    public ApiResponse<ReviewResponse> update(@PathVariable Long reviewId, @Valid @RequestBody ReviewRequest req) {
        return ApiResponse.success("Đã cập nhật đánh giá", reviewService.update(reviewId, req, currentUser.require()));
    }

    @DeleteMapping("/api/admin/supplies/reviews/{reviewId}")
    @Operation(summary = "Admin gỡ đánh giá vi phạm")
    public ApiResponse<Void> delete(@PathVariable Long reviewId) {
        reviewService.delete(reviewId);
        return ApiResponse.success("Đã gỡ đánh giá");
    }
}
