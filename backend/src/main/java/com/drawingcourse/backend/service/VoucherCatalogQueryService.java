package com.drawingcourse.backend.service;

import com.artacademy.exception.BusinessException;
import com.drawingcourse.backend.dto.PageResponse;
import com.drawingcourse.backend.dto.VoucherCatalogItem;
import com.drawingcourse.backend.entity.OrderVoucher;
import com.drawingcourse.backend.repository.VoucherCatalogProjection;
import com.drawingcourse.backend.repository.VoucherRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Authenticated read-only voucher catalog; availability is advisory, never a reservation. */
@Service
public final class VoucherCatalogQueryService {
    private final VoucherRepository vouchers;
    private final OrderCurrentUser currentUser;
    private final TransactionTemplate reads;
    private final Clock clock;
    private final VoucherCatalogAvailabilityEvaluator evaluator = new VoucherCatalogAvailabilityEvaluator();

    public VoucherCatalogQueryService(VoucherRepository vouchers, OrderCurrentUser currentUser,
                                      PlatformTransactionManager transactions) {
        this.vouchers = Objects.requireNonNull(vouchers);
        this.currentUser = Objects.requireNonNull(currentUser);
        this.clock = Clock.systemUTC();
        reads = new TransactionTemplate(Objects.requireNonNull(transactions));
        reads.setReadOnly(true);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public PageResponse<VoucherCatalogItem> list(int page, int size) {
        Integer userId = currentUser.requireUserId();
        if (userId == null) throw new AuthenticationCredentialsNotFoundException("Authentication required");
        if (page < 0) throw new BusinessException("page must be zero or greater");
        if (size < 1 || size > 50) throw new BusinessException("size must be between 1 and 50");
        Instant observedAt = clock.instant();
        return reads.execute(status -> {
            var result = vouchers.findCatalogForUser(userId, PageRequest.of(page, size));
            var content = result.getContent().stream().map(row -> toItem(row, observedAt)).toList();
            return new PageResponse<>(content, result.getTotalElements(), result.getTotalPages(),
                    result.getSize(), result.getNumber(), result.isFirst(), result.isLast(), result.isEmpty());
        });
    }

    private VoucherCatalogItem toItem(VoucherCatalogProjection row, Instant observedAt) {
        var evaluation = evaluator.evaluate(new VoucherCatalogAvailabilityEvaluator.Input(
                row.isActive(), row.getValidFrom(), row.getValidUntil(), optional(row.getTotalUsageLimit()),
                optional(row.getPerUserUsageLimit()), row.getReservedCount(), row.getRedeemedCount(),
                row.getUserReservedCount(), row.getUserRedeemedCount()), observedAt);
        Long maximum = row.getKind() == OrderVoucher.Kind.PERCENT_BASIS_POINTS
                ? row.getPercentageCapVnd() : null;
        return new VoucherCatalogItem(row.getCode(), row.getKind(), row.getValue(), maximum,
                row.getMinimumSubtotalVnd(), row.getValidFrom(), row.getValidUntil(),
                evaluation.availability(), evaluation.minimumSpendEvaluated());
    }

    private static OptionalLong optional(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }
}
