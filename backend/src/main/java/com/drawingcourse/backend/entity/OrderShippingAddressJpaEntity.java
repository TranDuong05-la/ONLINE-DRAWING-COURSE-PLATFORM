package com.drawingcourse.backend.entity;

import com.drawingcourse.backend.service.checkout.CheckoutRequest;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.Objects;

/** Immutable physical-shipping address snapshot owned by exactly one Order. */
@Entity
@Table(name="order_shipping_addresses")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class OrderShippingAddressJpaEntity {
    @Id @Column(name="order_id",nullable=false,updatable=false)
    private Integer orderId;
    @MapsId
    @OneToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="order_id",nullable=false,updatable=false,
            foreignKey=@ForeignKey(name="fk_order_shipping_address_order"))
    private OrderJpaEntity order;
    @Column(nullable=false,columnDefinition="TEXT",updatable=false) private String recipient;
    @Column(nullable=false,columnDefinition="TEXT",updatable=false) private String phone;
    @Column(name="address_line",nullable=false,columnDefinition="TEXT",updatable=false) private String addressLine;
    @Column(nullable=false,columnDefinition="TEXT",updatable=false) private String locality;
    @Column(name="postal_code",nullable=false,columnDefinition="TEXT",updatable=false) private String postalCode;
    @Column(name="country_code",nullable=false,columnDefinition="TEXT",updatable=false) private String countryCode;

    public OrderShippingAddressJpaEntity(OrderJpaEntity order,CheckoutRequest.Address address) {
        this.order=Objects.requireNonNull(order);
        Objects.requireNonNull(order.getId(),"Order must be persisted before its address snapshot");
        Objects.requireNonNull(address);
        recipient=address.recipient(); phone=address.phone(); addressLine=address.addressLine();
        locality=address.locality(); postalCode=address.postalCode(); countryCode=address.countryCode();
    }

    public CheckoutRequest.Address toSnapshot() {
        return new CheckoutRequest.Address(recipient,phone,addressLine,locality,postalCode,countryCode);
    }
}
