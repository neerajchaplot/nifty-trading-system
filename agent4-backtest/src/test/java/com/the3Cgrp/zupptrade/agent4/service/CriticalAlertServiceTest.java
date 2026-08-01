package com.the3Cgrp.zupptrade.agent4.service;

import com.the3Cgrp.zupptrade.agent4.domain.dto.response.CriticalAlertDto;
import com.the3Cgrp.zupptrade.agent4.exception.AlertNotFoundException;
import com.the3Cgrp.zupptrade.agent4.repository.CriticalAlertRepository;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CriticalAlertServiceTest {

    private final CriticalAlertRepository repo = mock(CriticalAlertRepository.class);
    private final CriticalAlertService service = new CriticalAlertService(repo);

    @Test
    void getLiveAlerts_mapsRowAndParsesTradeDetailsJsonToNestedObject() {
        UUID alertId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("alert_id", alertId);
        row.put("trade_id", tradeId);
        row.put("alert_reason", "ambiguous placement failure — check Upstox positions");
        row.put("trade_details", "{\"tag\":\"ZUPP_ABCD1234\",\"outcome\":\"RECONCILE_REQUIRED\"}");
        row.put("status", "LIVE");
        row.put("created_at", Timestamp.valueOf(LocalDateTime.of(2026, 7, 30, 10, 15)));
        row.put("acknowledged_at", null);
        when(repo.findLive()).thenReturn(List.of(row));

        List<CriticalAlertDto> alerts = service.getLiveAlerts();

        assertThat(alerts).hasSize(1);
        CriticalAlertDto dto = alerts.get(0);
        assertThat(dto.alertId()).isEqualTo(alertId);
        assertThat(dto.tradeId()).isEqualTo(tradeId);
        assertThat(dto.status()).isEqualTo("LIVE");
        assertThat(dto.acknowledgedAt()).isNull();
        assertThat(dto.tradeDetails())
                .containsEntry("tag", "ZUPP_ABCD1234")
                .containsEntry("outcome", "RECONCILE_REQUIRED");
    }

    @Test
    void getLiveAlerts_malformedTradeDetails_exposesRawWithoutFailing() {
        Map<String, Object> row = new HashMap<>();
        row.put("alert_id", UUID.randomUUID());
        row.put("trade_id", null);
        row.put("alert_reason", "reason");
        row.put("trade_details", "not-json");
        row.put("status", "LIVE");
        row.put("created_at", Timestamp.valueOf(LocalDateTime.now()));
        row.put("acknowledged_at", null);
        when(repo.findLive()).thenReturn(List.of(row));

        List<CriticalAlertDto> alerts = service.getLiveAlerts();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).tradeDetails()).containsEntry("raw", "not-json");
        assertThat(alerts.get(0).tradeId()).isNull();
    }

    @Test
    void acknowledge_rowUpdated_succeeds() {
        UUID alertId = UUID.randomUUID();
        when(repo.acknowledge(alertId)).thenReturn(1);

        service.acknowledge(alertId); // no throw
    }

    @Test
    void acknowledge_noRowUpdated_throwsAlertNotFound() {
        UUID alertId = UUID.randomUUID();
        when(repo.acknowledge(any(UUID.class))).thenReturn(0);

        assertThatThrownBy(() -> service.acknowledge(alertId))
                .isInstanceOf(AlertNotFoundException.class)
                .hasMessageContaining(alertId.toString());
    }
}
