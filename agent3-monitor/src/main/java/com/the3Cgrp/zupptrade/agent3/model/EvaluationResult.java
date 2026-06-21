package com.the3Cgrp.zupptrade.agent3.model;

import com.the3Cgrp.zupptrade.shared.enums.MonitorAction;
import com.the3Cgrp.zupptrade.shared.enums.ThresholdHit;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Internal result from a MonitorStrategy evaluation.
 * Converted to EvaluationResponse + MonitoringEvaluationEntity by MonitorEvaluationService.
 */
public record EvaluationResult(
        MonitorAction action,
        ThresholdHit thresholdHit,
        String reason,
        BigDecimal currentNetPremium,
        BigDecimal markToMarketPnl,
        /** Nullable — not computable for debit spreads without live PoP, or when IV is unavailable. */
        BigDecimal livePop,
        /** Full calculation breakdown stored as JSONB in monitoring_evaluations. */
        Map<String, Object> detail
) {}
