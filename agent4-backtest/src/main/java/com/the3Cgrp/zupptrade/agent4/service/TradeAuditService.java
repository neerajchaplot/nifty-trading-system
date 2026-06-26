package com.the3Cgrp.zupptrade.agent4.service;

import com.the3Cgrp.zupptrade.agent4.domain.dto.response.TradeAuditResponse;
import com.the3Cgrp.zupptrade.agent4.exception.TradeNotFoundException;
import com.the3Cgrp.zupptrade.agent4.mapper.TradeAuditMapper;
import com.the3Cgrp.zupptrade.agent4.repository.AnalyticsTradeRepository;
import com.the3Cgrp.zupptrade.agent4.repository.MonitoringEvaluationRepository;
import com.the3Cgrp.zupptrade.agent4.repository.TradeExecutionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TradeAuditService {

    private final AnalyticsTradeRepository     tradeRepository;
    private final MonitoringEvaluationRepository evalRepository;
    private final TradeExecutionRepository     execRepository;
    private final TradeAuditMapper             mapper;

    public TradeAuditService(AnalyticsTradeRepository tradeRepository,
                             MonitoringEvaluationRepository evalRepository,
                             TradeExecutionRepository execRepository,
                             TradeAuditMapper mapper) {
        this.tradeRepository = tradeRepository;
        this.evalRepository  = evalRepository;
        this.execRepository  = execRepository;
        this.mapper          = mapper;
    }

    public TradeAuditResponse getAudit(UUID tradeId) {
        Map<String, Object> tradeRow = tradeRepository.findClosedTradeById(tradeId)
                .orElseThrow(() -> new TradeNotFoundException(tradeId));

        List<Map<String, Object>> evaluations = evalRepository.findByTradeId(tradeId);
        List<Map<String, Object>> executions  = execRepository.findByTradeId(tradeId);

        return mapper.toAuditResponse(tradeRow, evaluations, executions);
    }
}
