package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.domain.entity.MonitoringEvaluationEntity;
import com.the3Cgrp.zupptrade.agent3.dto.ActiveTradeDto;
import com.the3Cgrp.zupptrade.agent3.model.TradeMonitorData;
import com.the3Cgrp.zupptrade.agent3.repository.MonitoringEvaluationRepository;
import com.the3Cgrp.zupptrade.agent3.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles the ActiveTradeDto list consumed by the UI live-monitor panel.
 * Joins TradeMonitorData (static config) with the latest MonitoringEvaluationEntity (live snapshot).
 */
@Service
public class ActiveTradesService {

    private static final Logger log = LoggerFactory.getLogger(ActiveTradesService.class);

    private final TradeMonitorReader tradeMonitorReader;
    private final MonitoringEvaluationRepository evaluationRepository;
    private final JsonUtil jsonUtil;
    private final OwnershipGuard guard;

    public ActiveTradesService(TradeMonitorReader tradeMonitorReader,
                               MonitoringEvaluationRepository evaluationRepository,
                               JsonUtil jsonUtil,
                               OwnershipGuard guard) {
        this.tradeMonitorReader = tradeMonitorReader;
        this.evaluationRepository = evaluationRepository;
        this.jsonUtil = jsonUtil;
        this.guard = guard;
    }

    /**
     * Active trades visible to the current user for the UI monitor. Scoped: caller's own trades,
     * or all for an admin; 401 if anonymous. The scheduler's global sweep is unaffected.
     */
    public List<ActiveTradeDto> findActiveForCurrentUser() {
        return tradeMonitorReader.findActiveForUser(guard.scopeProfileId()).stream()
                .map(this::toActiveTradeDto)
                .toList();
    }

    private ActiveTradeDto toActiveTradeDto(TradeMonitorData data) {
        MonitorConfigDto monitorConfig = parseMonitorConfig(data);
        Optional<MonitoringEvaluationEntity> latest =
                evaluationRepository.findLatestByTradeId(data.tradeId());

        return new ActiveTradeDto(
                data.tradeId(),
                data.tradeCode(),
                data.status(),
                data.expiryDate(),
                monitorConfig,
                latest.map(MonitoringEvaluationEntity::getAction).orElse(null),
                latest.map(MonitoringEvaluationEntity::getThresholdHit).orElse(null),
                latest.map(MonitoringEvaluationEntity::getSpotPrice).orElse(null),
                latest.map(MonitoringEvaluationEntity::getVixLevel).orElse(null),
                latest.map(MonitoringEvaluationEntity::getCurrentPop).orElse(null),
                latest.map(MonitoringEvaluationEntity::getMarkToMarketPnl).orElse(null),
                latest.map(MonitoringEvaluationEntity::getShortLegLtp).orElse(null),
                latest.map(MonitoringEvaluationEntity::getLongLegLtp).orElse(null),
                latest.map(MonitoringEvaluationEntity::getEvaluatedAt).orElse(null),
                latest.map(e -> extractLiveThresholds(e.getEvaluationDetail())).orElse(null)
        );
    }

    /**
     * Pulls the live-recomputed ladder (level + target-PoP keys) out of the evaluation_detail JSON.
     * Returns null when absent (debit/legacy trades or an early-return cycle that didn't recompute).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractLiveThresholds(String evaluationDetailJson) {
        if (evaluationDetailJson == null || evaluationDetailJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> detail = jsonUtil.fromJson(evaluationDetailJson, Map.class);
            Map<String, Object> live = new java.util.LinkedHashMap<>();
            detail.forEach((k, v) -> {
                if (k != null && (k.startsWith("live") || k.endsWith("TargetPop"))) {
                    live.put(k, v);
                }
            });
            return live.isEmpty() ? null : live;
        } catch (Exception e) {
            log.warn("Failed to parse evaluation_detail for live thresholds: {}", e.getMessage());
            return null;
        }
    }

    private MonitorConfigDto parseMonitorConfig(TradeMonitorData data) {
        if (data.monitorConfigJson() == null) {
            return null;
        }
        try {
            return jsonUtil.fromJson(data.monitorConfigJson(), MonitorConfigDto.class);
        } catch (Exception e) {
            log.warn("Failed to parse monitor_config for trade {}: {}", data.tradeId(), e.getMessage());
            return null;
        }
    }
}
