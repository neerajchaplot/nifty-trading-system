package com.the3Cgrp.zupptrade.agentUser.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_profile_audit", schema = "zupptrade_dev")
public class UserProfileAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_profile_id", nullable = false)
    private UUID userProfileId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_values", columnDefinition = "jsonb", nullable = false)
    private String oldValues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_values", columnDefinition = "jsonb", nullable = false)
    private String newValues;

    @PrePersist
    void onCreate() {
        changedAt = LocalDateTime.now();
    }

    public UUID getId()                       { return id; }
    public UUID getUserProfileId()            { return userProfileId; }
    public void setUserProfileId(UUID v)      { this.userProfileId = v; }
    public LocalDateTime getChangedAt()       { return changedAt; }
    public String getOldValues()              { return oldValues; }
    public void setOldValues(String v)        { this.oldValues = v; }
    public String getNewValues()              { return newValues; }
    public void setNewValues(String v)        { this.newValues = v; }
}
