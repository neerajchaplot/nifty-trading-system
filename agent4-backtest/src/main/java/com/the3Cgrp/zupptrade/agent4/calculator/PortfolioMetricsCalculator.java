package com.the3Cgrp.zupptrade.agent4.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure-Java calculator for portfolio-level metrics. No Spring, no DB.
 * All methods are stateless and operate on pre-fetched lists.
 */
public final class PortfolioMetricsCalculator {

    private PortfolioMetricsCalculator() {}

    public static BigDecimal winRate(int wins, int total) {
        if (total == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(wins)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    /**
     * Average actual RoC: average of (actual_pnl / realExpectedLossTotal) × 100.
     * Rows with null or zero real_expected_loss_total are skipped.
     */
    public static BigDecimal avgRocAchieved(List<Map<String, Object>> pnlRows) {
        List<BigDecimal> rocs = pnlRows.stream()
                .map(r -> toBd(r.get("roc_achieved_pct")))
                .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) != 0)
                .collect(Collectors.toList());

        if (rocs.isEmpty()) return BigDecimal.ZERO;

        BigDecimal sum = rocs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(rocs.size()), 4, RoundingMode.HALF_UP);
    }

    /**
     * RoC capture ratio: the share of theoretically achievable profit actually realised.
     *   capture% = Σ(actual_pnl) ÷ Σ(max_profit_total) × 100
     *
     * Aggregate (sum ÷ sum), NOT an average of per-trade ratios — a single tiny-premium
     * trade cannot skew it (e.g. a ₹1-premium leg blowing out to -199% RoC). Numerator and
     * denominator share one base (rupees of profit), so the ratio is meaningful: ~100% when
     * trades run to full max profit, and negative on a net loss.
     *
     * Returns ZERO when total max profit is null or zero.
     */
    public static BigDecimal rocCaptureRatio(BigDecimal totalPnl, BigDecimal totalMaxProfit) {
        if (totalPnl == null || totalMaxProfit == null
                || totalMaxProfit.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return totalPnl
                .multiply(BigDecimal.valueOf(100))
                .divide(totalMaxProfit, 2, RoundingMode.HALF_UP);
    }

    /**
     * Strategy distribution: strategy name → count of closed trades.
     */
    public static Map<String, Long> strategyMix(List<Map<String, Object>> rows) {
        Map<String, Long> mix = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String strategy = String.valueOf(row.getOrDefault("strategy", "UNKNOWN"));
            mix.merge(strategy, 1L, Long::sum);
        }
        return mix;
    }

    /**
     * Win rate grouped by a single string column (e.g., entry_vix_regime or signal_confidence_label).
     * Uses the pre-fetched groupMetrics rows from AnalyticsTradeRepository.findClosedTradeGroupMetrics.
     */
    public static Map<String, BigDecimal> winRateByGroup(
            List<Map<String, Object>> rows, String groupColumn) {

        // group: { total, wins }
        Map<String, long[]> accumulator = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            // A present-but-null column (e.g. entry_vix_regime not captured at entry) must
            // bucket as "UNKNOWN", not the literal string "null" — getOrDefault only
            // substitutes when the key is absent, not when its value is null.
            Object rawGroup = row.get(groupColumn);
            String group    = rawGroup == null ? "UNKNOWN" : rawGroup.toString();
            long tradeCount = toLong(row.get("trade_count"));
            long winCount   = toLong(row.get("win_count"));
            accumulator.computeIfAbsent(group, k -> new long[]{0L, 0L});
            accumulator.get(group)[0] += tradeCount;
            accumulator.get(group)[1] += winCount;
        }

        Map<String, BigDecimal> result = new LinkedHashMap<>();
        accumulator.forEach((group, counts) ->
                result.put(group, winRate((int) counts[1], (int) counts[0])));
        return result;
    }

    /**
     * Adjustment recovery rate: of trades that had at least one READJUST,
     * what percentage still closed with positive PnL?
     */
    public static BigDecimal adjustmentRecoveryRate(List<Map<String, Object>> rows) {
        List<Map<String, Object>> adjusted = rows.stream()
                .filter(r -> toLong(r.get("adjustment_count")) > 0)
                .collect(Collectors.toList());

        if (adjusted.isEmpty()) return BigDecimal.ZERO;

        long recovered = adjusted.stream()
                .filter(r -> {
                    BigDecimal pnl = toBd(r.get("actual_pnl"));
                    return pnl != null && pnl.compareTo(BigDecimal.ZERO) > 0;
                })
                .count();

        return winRate((int) recovered, adjusted.size());
    }

    // ── Helpers ───────────────────────────────────────────────

    public static BigDecimal toBd(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        try { return new BigDecimal(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { return 0L; }
    }
}
