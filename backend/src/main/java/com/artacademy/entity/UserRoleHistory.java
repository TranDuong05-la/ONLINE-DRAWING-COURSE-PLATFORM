package com.artacademy.entity;

import com.artacademy.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_role_histories", indexes = {
        @Index(name = "ix_user_role_histories_user_changed", columnList = "user_id,changed_at,id"),
        @Index(name = "ix_user_role_histories_actor", columnList = "changed_by_id")
})
@Getter
@Setter
@NoArgsConstructor
public class UserRoleHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_role_histories_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "changed_by_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_role_histories_actor"))
    private User changedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_role", nullable = false, length = 20)
    private Role oldRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_role", nullable = false, length = 20)
    private Role newRole;

    @Column(length = 255)
    private String reason;

    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    public static UserRoleHistory create(User user, User changedBy, Role oldRole, Role newRole, String reason) {
        UserRoleHistory history = new UserRoleHistory();
        history.user = user;
        history.changedBy = changedBy;
        history.oldRole = oldRole;
        history.newRole = newRole;
        history.reason = reason;
        return history;
    }
}
