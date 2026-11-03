package com.drawingcourse.backend.service;

import com.artacademy.exception.BusinessException;
import com.artacademy.exception.DuplicateResourceException;
import com.artacademy.exception.ResourceNotFoundException;
import com.drawingcourse.backend.dto.AdminVoucherCreateRequest;
import com.drawingcourse.backend.dto.AdminVoucherResponse;
import com.drawingcourse.backend.dto.AdminVoucherStatusRequest;
import com.drawingcourse.backend.dto.AdminVoucherUpdateRequest;
import com.drawingcourse.backend.dto.PageResponse;
import com.drawingcourse.backend.entity.OrderVoucher;
import com.drawingcourse.backend.entity.Voucher;
import com.drawingcourse.backend.entity.VoucherJpaEntity;
import com.drawingcourse.backend.repository.VoucherClaimRepository;
import com.drawingcourse.backend.repository.VoucherRepository;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class AdminVoucherService {
    private final VoucherRepository vouchers;
    private final VoucherClaimRepository claims;
    private final VoucherPersistenceMapper mapper = new VoucherPersistenceMapper();
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public AdminVoucherService(VoucherRepository vouchers, VoucherClaimRepository claims,
                               PlatformTransactionManager transactionManager) {
        this.vouchers = Objects.requireNonNull(vouchers);
        this.claims = Objects.requireNonNull(claims);
        Objects.requireNonNull(transactionManager);
        reads = new TransactionTemplate(transactionManager);
        reads.setReadOnly(true);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writes = new TransactionTemplate(transactionManager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    public PageResponse<AdminVoucherResponse> list(int page, int size) {
        requirePage(page, size);
        return reads.execute(status -> {
            var result = vouchers.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, size));
            List<Integer> ids = result.getContent().stream().map(VoucherJpaEntity::getId).toList();
            Set<Integer> claimed = ids.isEmpty() ? Set.of() : claims.findVoucherIdsWithClaims(ids);
            var content = result.getContent().stream().map(row -> response(row, claimed.contains(row.getId()))).toList();
            return new PageResponse<>(content, result.getTotalElements(), result.getTotalPages(),
                    result.getSize(), result.getNumber(), result.isFirst(), result.isLast(), result.isEmpty());
        });
    }

    public AdminVoucherResponse get(Integer id) {
        requirePositiveId(id);
        return reads.execute(status -> {
            VoucherJpaEntity row = vouchers.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Voucher", "id", id));
            return response(row, claims.existsByVoucherId(id));
        });
    }

    public AdminVoucherResponse create(AdminVoucherCreateRequest request) {
        Objects.requireNonNull(request);
        Voucher definition = definition(request);
        try {
            return writes.execute(status -> {
                if (vouchers.findByCode(definition.code()).isPresent()) {
                    throw new DuplicateResourceException("Voucher", "code", definition.code());
                }
                VoucherJpaEntity row = vouchers.saveAndFlush(VoucherJpaEntity.fromDefinition(definition));
                return response(row, false);
            });
        } catch (DataIntegrityViolationException duplicate) {
            throw new DuplicateResourceException("Voucher", "code", definition.code());
        }
    }

    public AdminVoucherResponse update(Integer id, AdminVoucherUpdateRequest request) {
        requirePositiveId(id);
        Objects.requireNonNull(request);
        if (request.forbiddenCode() != null || request.forbiddenActive() != null) {
            throw new BusinessException("code and active cannot be changed through the financial terms endpoint");
        }
        return writes.execute(status -> {
            VoucherJpaEntity row = lock(id);
            requireVersion(row, request.expectedDefinitionVersion());
            Voucher current = mapper.toDomain(row);
            Voucher proposed = definition(current.code(), current.active(), request);
            boolean hasClaims = claims.existsByVoucherId(id);
            if (current.equals(proposed)) return response(row, hasClaims);
            if (hasClaims) {
                throw new DuplicateResourceException("Voucher financial terms are immutable after the first claim");
            }
            row.replaceDefinition(proposed);
            return response(vouchers.saveAndFlush(row), false);
        });
    }

    public AdminVoucherResponse updateStatus(Integer id, AdminVoucherStatusRequest request) {
        requirePositiveId(id);
        Objects.requireNonNull(request);
        return writes.execute(status -> {
            VoucherJpaEntity row = lock(id);
            requireVersion(row, request.expectedDefinitionVersion());
            boolean hasClaims = claims.existsByVoucherId(id);
            Voucher current = mapper.toDomain(row);
            if (current.active() == request.active()) return response(row, hasClaims);
            row.replaceDefinition(current.withActive(request.active()));
            return response(vouchers.saveAndFlush(row), hasClaims);
        });
    }

    private VoucherJpaEntity lock(Integer id) {
        return vouchers.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Voucher", "id", id));
    }

    private static void requireVersion(VoucherJpaEntity row, Long expected) {
        if (expected == null || row.getDefinitionVersion() != expected) {
            throw new DuplicateResourceException("Voucher definition version is stale");
        }
    }

    private Voucher definition(AdminVoucherCreateRequest request) {
        return validatedDefinition(request.code(), request.discountType(), request.discountValue(),
                request.maximumDiscountVnd(), request.active(), request.validFrom(), request.validUntil(),
                request.minimumEligibleMerchandiseSpendVnd(), request.totalUsageLimit(), request.perUserUsageLimit());
    }

    private Voucher definition(String code, boolean active, AdminVoucherUpdateRequest request) {
        return validatedDefinition(code, request.discountType(), request.discountValue(),
                request.maximumDiscountVnd(), active, request.validFrom(), request.validUntil(),
                request.minimumEligibleMerchandiseSpendVnd(), request.totalUsageLimit(), request.perUserUsageLimit());
    }

    private Voucher validatedDefinition(String code, OrderVoucher.Kind kind, Long value, Long cap,
                                        Boolean active, java.time.Instant validFrom, java.time.Instant validUntil,
                                        Long minimum, Long totalLimit, Long perUserLimit) {
        if (code == null || kind == null || value == null || active == null || validFrom == null
                || validUntil == null || minimum == null || value <= 0 || minimum < 0
                || (totalLimit != null && totalLimit < 0) || (perUserLimit != null && perUserLimit < 0)
                || (kind == OrderVoucher.Kind.FIXED_VND && cap != null)
                || (kind == OrderVoucher.Kind.PERCENT_BASIS_POINTS && cap != null && cap <= 0)) {
            throw new BusinessException("Invalid voucher definition");
        }
        try {
            return new Voucher(code, kind, value, optional(cap), active, validFrom, validUntil, minimum,
                    optional(totalLimit), optional(perUserLimit));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new BusinessException("Invalid voucher definition");
        }
    }

    private static AdminVoucherResponse response(VoucherJpaEntity row, boolean hasClaims) {
        Long maximum = row.getKind() == OrderVoucher.Kind.PERCENT_BASIS_POINTS
                ? row.getPercentageCapVnd() : null;
        return new AdminVoucherResponse(row.getId(), row.getCode(), row.getKind(), row.getValue(), maximum,
                row.isActive(), row.getValidFrom(), row.getValidUntil(), row.getMinimumSubtotalVnd(),
                row.getTotalUsageLimit(), row.getPerUserUsageLimit(), row.getReservedCount(),
                row.getRedeemedCount(), row.getDefinitionVersion(), row.getCreatedAt(), row.getUpdatedAt(),
                hasClaims, !hasClaims);
    }

    private static OptionalLong optional(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private static void requirePage(int page, int size) {
        if (page < 0) throw new BusinessException("page must be zero or greater");
        if (size < 1 || size > 50) throw new BusinessException("size must be between 1 and 50");
    }

    private static void requirePositiveId(Integer id) {
        if (id == null || id <= 0) throw new BusinessException("id must be positive");
    }
}
