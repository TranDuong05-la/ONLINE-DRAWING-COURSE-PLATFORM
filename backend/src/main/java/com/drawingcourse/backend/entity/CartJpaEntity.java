package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Entity
@org.hibernate.annotations.Check(name = "ck_carts_revision_nonnegative", constraints = "revision >= 0")
@Table(name = "carts", uniqueConstraints = @UniqueConstraint(name = "uk_carts_user", columnNames = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @NotNull @Column(name = "user_id", nullable = false, updatable = false)
    private Integer userId;
    /** Server-owned content revision; advanced only while holding the Cart row lock. */
    @Column(name = "revision", nullable = false)
    private long revision;
    @Convert(converter = CartUtcInstantConverter.class)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Convert(converter = CartUtcInstantConverter.class)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItemJpaEntity> items = new ArrayList<>();

    public CartJpaEntity(Integer userId) { this.userId = Objects.requireNonNull(userId); }
    public List<CartItemJpaEntity> getItems() { return Collections.unmodifiableList(items); }
    public void incrementRevision() {
        revision = Math.addExact(revision, 1L);
        touch();
    }
    public void addItem(CartItemType type, Integer itemId, int quantity) {
        if (items.stream().anyMatch(i -> i.getItemType() == type && i.getItemId().equals(itemId))) {
            throw new IllegalArgumentException("Duplicate cart item");
        }
        items.add(new CartItemJpaEntity(this, type, itemId, quantity));
    }
    public void removeItem(CartItemJpaEntity item) { items.remove(item); }
    public void touch() { updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS); }
    @PrePersist
    private void initializeTimestamps() { createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); updatedAt = createdAt; }
    @PreUpdate
    private void updateTimestamp() { touch(); }
}
