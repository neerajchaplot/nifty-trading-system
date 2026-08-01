package com.the3Cgrp.zupptrade.agent4.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.the3Cgrp.zupptrade.agent4.domain.dto.response.CriticalAlertDto;
import com.the3Cgrp.zupptrade.agent4.exception.AlertNotFoundException;
import com.the3Cgrp.zupptrade.agent4.repository.CriticalAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serves the LIVE critical-alert list to the UI and handles user acknowledgement.
 *
 * <p>The transparent {@code trade_details} JSONB blob is parsed into a Map (vanilla Jackson 2,
 * matching {@link com.the3Cgrp.zupptrade.agent4.mapper.TradeAuditMapper}) so it serialises back
 * to the client as a nested object rather than an escaped string.
 */
@Service
public class CriticalAlertService {

    private static final Logger log = LoggerFactory.getLogger(CriticalAlertService.class);

    private final CriticalAlertRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CriticalAlertService(CriticalAlertRepository repository) {
        this.repository = repository;
    }

    public List<CriticalAlertDto> getLiveAlerts() {
        return repository.findLive().stream().map(this::toDto).toList();
    }

    /**
     * Acknowledges a LIVE alert. Throws {@link AlertNotFoundException} when nothing was updated
     * (alert absent or already acknowledged) so the controller returns a 404 Problem Detail.
     */
    public void acknowledge(UUID alertId) {
        int updated = repository.acknowledge(alertId);
        if (updated == 0) {
            throw new AlertNotFoundException(alertId);
        }
        log.info("critical_alert.acknowledged alertId={}", alertId);
    }

    // ── Row → DTO mapping ─────────────────────────────────────

    private CriticalAlertDto toDto(Map<String, Object> row) {
        return new CriticalAlertDto(
                toUuid(row.get("alert_id")),
                toUuid(row.get("trade_id")),
                str(row.get("alert_reason")),
                parseDetails(row.get("trade_details")),
                str(row.get("status")),
                toLocalDateTime(row.get("created_at")),
                toLocalDateTime(row.get("acknowledged_at"))
        );
    }

    private Map<String, Object> parseDetails(Object raw) {
        if (raw == null) return Map.of();
        String json = raw.toString();
        if (json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // Never fail the whole list because one snapshot is malformed — expose the raw text.
            log.warn("Failed to parse critical_alert trade_details JSON: {}", e.getMessage());
            return Map.of("raw", json);
        }
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }

    private static UUID toUuid(Object v) {
        if (v == null) return null;
        if (v instanceof UUID u) return u;
        return UUID.fromString(v.toString());
    }

    private static LocalDateTime toLocalDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof LocalDateTime ldt) return ldt;
        return null;
    }
}
