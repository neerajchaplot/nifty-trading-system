package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.domain.entity.MonitoringEvaluationEntity;
import com.the3Cgrp.zupptrade.agent3.dto.ActiveTradeDto;
import com.the3Cgrp.zupptrade.agent3.model.TradeMonitorData;
import com.the3Cgrp.zupptrade.agent3.repository.MonitoringEvaluationRepository;
import com.the3Cgrp.zupptrade.agent3.util.JsonUtil;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
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

    public ActiveTradesService(TradeMonitorReader tradeMonitorReader,
                               MonitoringEvaluationRepository evaluationRepository,
                               JsonUtil jsonUtil) {
        this.tradeMonitorReader = tradeMonitorReader;
        this.evaluationRepository = evaluationRepository;
        this.jsonUtil = jsonUtil;
    }

    public List<ActiveTradeDto> findAllActive() {
        return tradeMonitorReader.findAllActive().stream()
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
                latest.map(MonitoringEvaluationEntity::getEvaluatedAt).orElse(null)
        );
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
