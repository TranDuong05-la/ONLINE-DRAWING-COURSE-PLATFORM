package com.artacademy.supply.review;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.entity.User;
import com.artacademy.repository.UserRepository;
import com.artacademy.supply.catalog.SupplyProductRepository;
import com.artacademy.supply.common.PageResult;
import com.artacademy.supply.common.SupplyException;
import com.artacademy.supply.common.SupplyUser;
import com.artacademy.supply.review.ReviewDtos.ReviewEligibility;
import com.artacademy.supply.review.ReviewDtos.ReviewRequest;
import com.artacademy.supply.review.ReviewDtos.ReviewResponse;
import com.artacademy.supply.review.ReviewDtos.ReviewSummary;
import com.artacademy.supply.shipment.ShipmentRepository;
import com.artacademy.supply.shipment.ShipmentStatus;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ShipmentRepository shipmentRepository;
    private final SupplyProductRepository productRepository;
    private final UserRepository userRepository;

    public ReviewService(ReviewRepository reviewRepository, ShipmentRepository shipmentRepository,
            SupplyProductRepository productRepository, UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.shipmentRepository = shipmentRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public PageResult<ReviewResponse> list(Long productId, SupplyUser viewer, int page, int size) {
        var result = reviewRepository.findByProductIdOrderByCreatedAtDescIdDesc(productId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)));
        Map<Integer, User> users = users(result.getContent().stream().map(Review::getUserId).toList());
        return PageResult.of(result, r -> toResponse(r, users.get(r.getUserId()), viewer.id()));
    }

    @Transactional(readOnly = true)
    public ReviewSummary summary(Long productId) {
        Object[] row = reviewRepository.stats(productId).get(0);
        Map<Integer, Long> histogram = new LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) {
            histogram.put(star, 0L);
        }
        for (Object[] h : reviewRepository.histogram(productId)) {
            histogram.put(((Number) h[0]).intValue(), ((Number) h[1]).longValue());
        }
        double avg = ((Number) row[0]).doubleValue();
        return new ReviewSummary(Math.round(avg * 10) / 10.0, ((Number) row[1]).longValue(), histogram);
    }

    @Transactional(readOnly = true)
    public ReviewEligibility eligibility(Long productId, SupplyUser user) {
        var mine = reviewRepository.findByProductIdAndUserId(productId, user.id());
        if (mine.isPresent()) {
            return new ReviewEligibility(true, "ALREADY_REVIEWED", "Bạn đã đánh giá, có thể sửa lại",
                    toResponse(mine.get(), userRepository.findById(user.id()).orElse(null), user.id()));
        }
        if (!deliveredShipments(productId, user.id()).isEmpty()) {
            return new ReviewEligibility(true, "DELIVERED_OK", null, null);
        }
        if (shipmentRepository.existsForUserAndProduct(user.id(), productId)) {
            return new ReviewEligibility(false, "NOT_DELIVERED",
                    "Bạn có thể đánh giá sau khi nhận hàng thành công", null);
        }
        return new ReviewEligibility(false, "NOT_PURCHASED",
                "Chỉ người đã mua và nhận học cụ này mới được đánh giá", null);
    }

    @Transactional
    public ReviewResponse create(Long productId, ReviewRequest req, SupplyUser user) {
        productRepository.findById(productId).orElseThrow(() -> SupplyException.notFound("Học cụ"));
        if (reviewRepository.findByProductIdAndUserId(productId, user.id()).isPresent()) {
            throw SupplyException.conflict("ALREADY_REVIEWED", "Bạn đã đánh giá học cụ này, hãy sửa đánh giá cũ");
        }
        List<Long> delivered = deliveredShipments(productId, user.id());
        if (delivered.isEmpty()) {
            throw SupplyException.forbidden("Chỉ được đánh giá học cụ đã mua và giao thành công");
        }
        Review r = new Review();
        r.setProductId(productId);
        r.setUserId(user.id());
        r.setShipmentId(delivered.get(0));
        r.setRating(req.rating());
        r.setComment(blankToNull(req.comment()));
        r = reviewRepository.saveAndFlush(r);
        ReviewResponse res = toResponse(r, userRepository.findById(user.id()).orElse(null), user.id());
        refreshRating(productId);
        return res;
    }

    @Transactional
    public ReviewResponse update(Long reviewId, ReviewRequest req, SupplyUser user) {
        Review r = reviewRepository.findById(reviewId).orElseThrow(() -> SupplyException.notFound("Đánh giá"));
        if (!r.getUserId().equals(user.id())) {
            throw SupplyException.forbidden("Bạn chỉ sửa được đánh giá của mình");
        }
        r.setRating(req.rating());
        r.setComment(blankToNull(req.comment()));
        reviewRepository.flush();
        ReviewResponse res = toResponse(r, userRepository.findById(user.id()).orElse(null), user.id());
        refreshRating(r.getProductId());
        return res;
    }

    /** Admin gỡ đánh giá vi phạm. */
    @Transactional
    public void delete(Long reviewId) {
        Review r = reviewRepository.findById(reviewId).orElseThrow(() -> SupplyException.notFound("Đánh giá"));
        Long productId = r.getProductId();
        reviewRepository.delete(r);
        reviewRepository.flush();
        refreshRating(productId);
    }

    private List<Long> deliveredShipments(Long productId, Integer userId) {
        return shipmentRepository.findShipmentIds(userId, productId, ShipmentStatus.DELIVERED);
    }

    private void refreshRating(Long productId) {
        Object[] row = reviewRepository.stats(productId).get(0);
        productRepository.updateRating(productId, ((Number) row[0]).doubleValue(), ((Number) row[1]).intValue());
    }

    private Map<Integer, User> users(Collection<Integer> ids) {
        return userRepository.findAllById(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static ReviewResponse toResponse(Review r, User author, Integer viewerId) {
        String name = author != null ? author.getFullName() : "Học viên";
        String avatar = author != null ? author.getAvatarUrl() : null;
        return new ReviewResponse(r.getId(), r.getProductId(), r.getUserId(), name, avatar, r.getRating(),
                r.getComment(), r.getUserId().equals(viewerId), r.getCreatedAt(), r.getUpdatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
