package com.the3Cgrp.zupptrade.agent2.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "reference_data")
public class ReferenceDataEntity {

    @Id
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String value;

    @Column
    private String source;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @Column(name = "ttl_hours", nullable = false)
    private int ttlHours = 24;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(LocalDateTime fetchedAt) { this.fetchedAt = fetchedAt; }
    public int getTtlHours() { return ttlHours; }
    public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }

    public boolean isExpired() {
        return fetchedAt.plusHours(ttlHours).isBefore(LocalDateTime.now());
    }
}
