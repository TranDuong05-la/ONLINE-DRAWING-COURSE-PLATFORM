package com.drawingcourse.backend.service;

import com.artacademy.exception.BusinessException;
import com.artacademy.exception.ResourceNotFoundException;
import com.drawingcourse.backend.dto.OrderDetail;
import com.drawingcourse.backend.dto.OrderSummary;
import com.drawingcourse.backend.dto.PageResponse;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.OrderShippingAddressRepository;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;

/** Authenticated, strictly read-only Order History queries. */
@Service
public final class OrderHistoryQueryService {
    private final OrderRepository orders;
    private final OrderShippingAddressRepository addresses;
    private final OrderCurrentUser currentUser;
    private final OrderHistoryResponseMapper mapper = new OrderHistoryResponseMapper();
    private final TransactionTemplate reads;

    public OrderHistoryQueryService(OrderRepository orders, OrderShippingAddressRepository addresses,
                                    OrderCurrentUser currentUser, PlatformTransactionManager transactions) {
        this.orders = Objects.requireNonNull(orders);
        this.addresses = Objects.requireNonNull(addresses);
        this.currentUser = Objects.requireNonNull(currentUser);
        reads = new TransactionTemplate(Objects.requireNonNull(transactions));
        reads.setReadOnly(true);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public PageResponse<OrderSummary> list(int page, int size) {
        Integer owner = owner();
        if (page < 0) throw new BusinessException("page must be zero or greater");
        if (size < 1 || size > 100) throw new BusinessException("size must be between 1 and 100");
        return reads.execute(status -> {
            var result = orders.findHistoryByUserId(owner, PageRequest.of(page, size));
            return new PageResponse<>(result.getContent().stream().map(mapper::toSummary).toList(),
                    result.getTotalElements(), result.getTotalPages(), result.getSize(), result.getNumber(),
                    result.isFirst(), result.isLast(), result.isEmpty());
        });
    }

    public OrderDetail detail(Integer orderId) {
        Integer owner = owner();
        if (orderId == null || orderId < 1) throw new BusinessException("orderId must be positive");
        return reads.execute(status -> {
            var row = orders.findByIdAndUserId(orderId, owner)
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
            if (!orderId.equals(row.getId()) || !owner.equals(row.getUserId())) {
                throw new ResourceNotFoundException("Order not found");
            }
            var stored = new OrderPersistenceMapper().toStored(row);
            var address = addresses.findByOrder_IdAndOrder_UserId(orderId, owner)
                    .map(value -> value.toSnapshot())
                    .map(value -> new OrderDetail.ShippingAddress(value.recipient(), value.phone(),
                            value.addressLine(), value.locality(), value.postalCode(), value.countryCode()))
                    .orElse(null);
            return mapper.toDetail(stored, address);
        });
    }

    private Integer owner() {
        Integer id = currentUser.requireUserId();
        if (id == null) throw new AuthenticationCredentialsNotFoundException("Authentication required");
        return id;
    }
}
