package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.Objects;

@Entity
@org.hibernate.annotations.Check(name = "ck_cart_items_values", constraints =
        "quantity > 0 AND item_type IN ('COURSE', 'PRODUCT') AND (item_type <> 'COURSE' OR quantity = 1)")
@Table(name = "cart_items", uniqueConstraints = @UniqueConstraint(
        name = "uk_cart_items_reference", columnNames = {"cart_id", "item_type", "item_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItemJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @NotNull @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_cart_items_cart"))
    private CartJpaEntity cart;
    @NotNull @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(name = "item_type", nullable = false, length = 16, updatable = false)
    private CartItemType itemType;
    @NotNull @Column(name = "item_id", nullable = false, updatable = false)
    private Integer itemId;
    @Positive @Column(nullable = false)
    private int quantity;

    CartItemJpaEntity(CartJpaEntity cart, CartItemType type, Integer itemId, int quantity) {
        this.cart = Objects.requireNonNull(cart);
        this.itemType = Objects.requireNonNull(type);
        this.itemId = Objects.requireNonNull(itemId);
        setQuantity(quantity);
    }
    public void setQuantity(int quantity) {
        new CartItem<>(itemType, itemId, quantity);
        this.quantity = quantity;
    }
    @AssertTrue(message = "Courses require quantity 1")
    public boolean isCourseQuantityValid() { return itemType != CartItemType.COURSE || quantity == 1; }
}
