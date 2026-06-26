package com.the3Cgrp.zupptrade.agent4.service;

import com.the3Cgrp.zupptrade.agent4.calculator.Agent1AccuracyCalculator;
import com.the3Cgrp.zupptrade.agent4.calculator.DrawdownCalculator;
import com.the3Cgrp.zupptrade.agent4.calculator.PortfolioMetricsCalculator;
import com.the3Cgrp.zupptrade.agent4.domain.dto.response.PortfolioSummaryResponse;
import com.the3Cgrp.zupptrade.agent4.repository.AnalyticsTradeRepository;
import com.the3Cgrp.zupptrade.agent4.repository.SignalQualityRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PortfolioSummaryService {

    private final AnalyticsTradeRepository tradeRepository;
    private final SignalQualityRepository  signalRepository;

    public PortfolioSummaryService(AnalyticsTradeRepository tradeRepository,
                                   SignalQualityRepository signalRepository) {
        this.tradeRepository  = tradeRepository;
        this.signalRepository = signalRepository;
    }

    public PortfolioSummaryResponse getSummary(LocalDate from, LocalDate to) {

        // Single aggregate round-trip for counts and sums
        Map<String, Object> agg       = tradeRepository.getAggregateMetrics(from, to);
        int totalTrades               = toInt(agg.get("total_trades"));
        int winCount                  = toInt(agg.get("win_count"));
        int lossCount                 = toInt(agg.get("loss_count"));
        BigDecimal totalPnl           = toBd(agg.get("total_pnl"));
        BigDecimal maxLoss            = toBd(agg.get("max_loss"));
        BigDecimal avgRocTheoretical  = toBd(agg.get("avg_roc_theoretical"));
        int totalAdjustments          = toInt(agg.get("total_adjustments"));

        // Ordered PnL list for drawdown and actual RoC
        List<Map<String, Object>> pnlRows = tradeRepository.findOrderedPnlList(from, to);
        BigDecimal avgRocAchieved = PortfolioMetricsCalculator.avgRocAchieved(pnlRows);
        BigDecimal rocCapture     = PortfolioMetricsCalculator.rocCaptureRatio(
                avgRocAchieved, avgRocTheoretical);

        List<BigDecimal> pnlList  = pnlRows.stream()
                .map(r -> PortfolioMetricsCalculator.toBd(r.get("actual_pnl")))
                .collect(Collectors.toList());
        DrawdownCalculator.DrawdownResult drawdown = DrawdownCalculator.compute(pnlList);

        // Group metrics for breakdowns
        List<Map<String, Object>> groupRows = tradeRepository.findClosedTradeGroupMetrics(from, to);
        Map<String, BigDecimal> winRateByVix        = PortfolioMetricsCalculator
                .winRateByGroup(groupRows, "entry_vix_regime");
        Map<String, BigDecimal> winRateByConfidence = PortfolioMetricsCalculator
                .winRateByGroup(groupRows, "signal_confidence_label");
        Map<String, Long> strategyMix = PortfolioMetricsCalculator.strategyMix(
                tradeRepository.findClosedTrades(from, to, 0, Integer.MAX_VALUE));
        BigDecimal adjustmentRecovery = PortfolioMetricsCalculator
                .adjustmentRecoveryRate(tradeRepository.findClosedTrades(from, to, 0, Integer.MAX_VALUE));

        // Agent 1 accuracy from signal quality view
        List<Map<String, Object>> signalRows = signalRepository.findSignals(from, to);
        List<String> verdicts = signalRows.stream()
                .map(r -> String.valueOf(r.getOrDefault("accuracy_verdict", "NO_TRADE")))
                .collect(Collectors.toList());
        BigDecimal agent1Accuracy = Agent1AccuracyCalculator.accuracyRate(verdicts);

        return new PortfolioSummaryResponse(
                from,
                to,
                totalTrades,
                winCount,
                lossCount,
                PortfolioMetricsCalculator.winRate(winCount, totalTrades),
                totalPnl,
                maxLoss,
                drawdown.maxDrawdown(),
                avgRocAchieved,
                avgRocTheoretical,
                rocCapture,
                totalAdjustments,
                adjustmentRecovery,
                agent1Accuracy,
                strategyMix,
                winRateByVix,
                winRateByConfidence
        );
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static BigDecimal toBd(Object v) {
        return PortfolioMetricsCalculator.toBd(v);
    }
}
