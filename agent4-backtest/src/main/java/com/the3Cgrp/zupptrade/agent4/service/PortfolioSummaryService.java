package com.the3Cgrp.zupptrade.agent4.service;

import com.the3Cgrp.zupptrade.agent4.calculator.Agent1AccuracyCalculator;
import com.the3Cgrp.zupptrade.agent4.calculator.Agent1AccuracyCalculator.Thresholds;
import com.the3Cgrp.zupptrade.agent4.calculator.DrawdownCalculator;
import com.the3Cgrp.zupptrade.agent4.calculator.PortfolioMetricsCalculator;
import com.the3Cgrp.zupptrade.agent4.domain.dto.response.PortfolioSummaryResponse;
import com.the3Cgrp.zupptrade.agent4.repository.AccuracyThresholdsRepository;
import com.the3Cgrp.zupptrade.agent4.repository.AnalyticsTradeRepository;
import com.the3Cgrp.zupptrade.agent4.repository.SignalQualityRepository;
import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PortfolioSummaryService {

    private final AnalyticsTradeRepository tradeRepository;
    private final SignalQualityRepository  signalRepository;
    private final AccuracyThresholdsRepository thresholdsRepository;
    private final OwnershipGuard guard;

    public PortfolioSummaryService(AnalyticsTradeRepository tradeRepository,
                                   SignalQualityRepository signalRepository,
                                   AccuracyThresholdsRepository thresholdsRepository,
                                   OwnershipGuard guard) {
        this.tradeRepository  = tradeRepository;
        this.signalRepository = signalRepository;
        this.thresholdsRepository = thresholdsRepository;
        this.guard = guard;
    }

    public PortfolioSummaryResponse getSummary(LocalDate from, LocalDate to) {

        // Phase 5 scope: caller's profile id, or null for admin (all users). 401 if anonymous.
        UUID scope = guard.scopeProfileId();

        // Single aggregate round-trip for counts and sums
        Map<String, Object> agg       = tradeRepository.getAggregateMetrics(from, to, scope);
        int totalTrades               = toInt(agg.get("total_trades"));
        int winCount                  = toInt(agg.get("win_count"));
        int lossCount                 = toInt(agg.get("loss_count"));
        BigDecimal totalPnl           = toBd(agg.get("total_pnl"));
        BigDecimal totalMaxProfit     = toBd(agg.get("total_max_profit"));
        BigDecimal maxLoss            = toBd(agg.get("max_loss"));
        BigDecimal avgRocTheoretical  = toBd(agg.get("avg_roc_theoretical"));
        int totalAdjustments          = toInt(agg.get("total_adjustments"));

        // Ordered PnL list for drawdown and actual RoC
        List<Map<String, Object>> pnlRows = tradeRepository.findOrderedPnlList(from, to, scope);
        BigDecimal avgRocAchieved = PortfolioMetricsCalculator.avgRocAchieved(pnlRows);
        // Capture = realised profit as a share of theoretical max profit (rupee ÷ rupee),
        // NOT achieved-RoC ÷ theoretical-RoC — those two RoCs use different denominators
        // (return-on-risk vs return-on-capital) and their ratio is meaningless.
        BigDecimal rocCapture     = PortfolioMetricsCalculator.rocCaptureRatio(
                totalPnl, totalMaxProfit);

        List<BigDecimal> pnlList  = pnlRows.stream()
                .map(r -> PortfolioMetricsCalculator.toBd(r.get("actual_pnl")))
                .collect(Collectors.toList());
        DrawdownCalculator.DrawdownResult drawdown = DrawdownCalculator.compute(pnlList);

        // Group metrics for breakdowns
        List<Map<String, Object>> groupRows = tradeRepository.findClosedTradeGroupMetrics(from, to, scope);
        Map<String, BigDecimal> winRateByVix        = PortfolioMetricsCalculator
                .winRateByGroup(groupRows, "entry_vix_regime");
        Map<String, BigDecimal> winRateByConfidence = PortfolioMetricsCalculator
                .winRateByGroup(groupRows, "signal_confidence_label");
        Map<String, Long> strategyMix = PortfolioMetricsCalculator.strategyMix(
                tradeRepository.findClosedTrades(from, to, scope, 0, Integer.MAX_VALUE));
        BigDecimal adjustmentRecovery = PortfolioMetricsCalculator
                .adjustmentRecoveryRate(tradeRepository.findClosedTrades(from, to, scope, 0, Integer.MAX_VALUE));

        // Agent 1 accuracy: price-based, graded per signal (bias+strength vs expiry-day move).
        // Null when nothing is measurable → surfaced as N/A, not a misleading 0%.
        List<Map<String, Object>> signalRows = signalRepository.findSignals(from, to, scope);
        Thresholds thresholds = thresholdsRepository.get();
        BigDecimal agent1Accuracy = Agent1AccuracyCalculator.accuracyRate(
                signalRows, LocalDate.now(), thresholds);

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
