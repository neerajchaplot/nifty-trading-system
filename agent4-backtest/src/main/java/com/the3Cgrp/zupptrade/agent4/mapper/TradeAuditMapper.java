package com.the3Cgrp.zupptrade.agent4.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.the3Cgrp.zupptrade.agent4.calculator.PortfolioMetricsCalculator;
import com.the3Cgrp.zupptrade.agent4.domain.dto.response.*;
import com.the3Cgrp.zupptrade.agent4.domain.enums.TradeOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Maps raw DB row maps (from JdbcTemplate) and evaluation/execution lists
 * into the four audit chapters and the top-level TradeAuditResponse.
 *
 * Jackson is used only for parsing the raw JSONB text blobs
 * (legs_json, gate_results_json, signal_score_breakdown, signal_data_gaps).
 * All other fields are mapped directly from their typed column values.
 */
public final class TradeAuditMapper {

    private static final Logger log = LoggerFactory.getLogger(TradeAuditMapper.class);
    private static final Map<String, String> TIER_LABELS = Map.of(
            "tier1a", "Tier 1A – Price Structure",
            "tier1b", "Tier 1B – Technical Indicators",
            "tier2",  "Tier 2 – Institutional Flow",
            "tier3",  "Tier 3 – Volatility & Macro",
            "tier4",  "Tier 4 – Commentary & Sentiment"
    );

    private final ObjectMapper objectMapper;

    public TradeAuditMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── Top-level assembly ────────────────────────────────────

    public TradeAuditResponse toAuditResponse(
            Map<String, Object> tradeRow,
            List<Map<String, Object>> evaluations,
            List<Map<String, Object>> executions) {

        return new TradeAuditResponse(
                toUuid(tradeRow.get("trade_id")),
                str(tradeRow.get("trade_code")),
                str(tradeRow.get("status")),
                toLocalDate(tradeRow.get("expiry_date")),
                toOutcome(str(tradeRow.get("outcome"))),
                PortfolioMetricsCalculator.toBd(tradeRow.get("actual_pnl")),
                str(tradeRow.get("close_reason")),
                toSignalChapter(tradeRow),
                toRecommendationChapter(tradeRow),
                toExecutionChapter(executions),
                toMonitoringChapter(evaluations, tradeRow)
        );
    }

    // ── Chapter builders ──────────────────────────────────────

    private SignalChapter toSignalChapter(Map<String, Object> row) {
        return new SignalChapter(
                toUuid(row.get("signal_id")),
                toLocalDateTime(row.get("signal_scored_at")),
                str(row.get("signal_bias")),
                str(row.get("signal_strength")),
                PortfolioMetricsCalculator.toBd(row.get("signal_score")),
                PortfolioMetricsCalculator.toBd(row.get("signal_confidence_score")),
                str(row.get("signal_confidence_label")),
                PortfolioMetricsCalculator.toBd(row.get("signal_vix_level")),
                str(row.get("signal_vix_regime")),
                str(row.get("signal_vix_direction")),
                Boolean.TRUE.equals(row.get("commentary_divergence")),
                parseTierScores(str(row.get("signal_score_breakdown"))),
                parseDataGaps(str(row.get("signal_data_gaps")))
        );
    }

    private RecommendationChapter toRecommendationChapter(Map<String, Object> row) {
        return new RecommendationChapter(
                str(row.get("strategy")),
                str(row.get("spread_direction")),
                parseLegs(str(row.get("legs_json"))),
                PortfolioMetricsCalculator.toBd(row.get("pop")),
                PortfolioMetricsCalculator.toBd(row.get("popp")),
                null, // popGap not stored separately; derivable as popp - pop
                toIntPrimitive(row.get("lots")),
                toIntPrimitive(row.get("lot_size")),
                PortfolioMetricsCalculator.toBd(row.get("entry_net_premium")),
                PortfolioMetricsCalculator.toBd(row.get("max_profit_total")),
                PortfolioMetricsCalculator.toBd(row.get("real_expected_loss_total")),
                PortfolioMetricsCalculator.toBd(row.get("roc_theoretical_pct")),
                PortfolioMetricsCalculator.toBd(row.get("roc_annualised")),
                parseGateResults(str(row.get("gate_results_json"))),
                PortfolioMetricsCalculator.toBd(row.get("t1_watch_nifty")),
                PortfolioMetricsCalculator.toBd(row.get("t2_readjust_nifty")),
                PortfolioMetricsCalculator.toBd(row.get("t2_loss_threshold")),
                PortfolioMetricsCalculator.toBd(row.get("t3_exit_nifty")),
                PortfolioMetricsCalculator.toBd(row.get("t3_loss_threshold"))
        );
    }

    private ExecutionChapter toExecutionChapter(List<Map<String, Object>> executions) {
        if (executions == null || executions.isEmpty()) {
            return new ExecutionChapter(null, null, BigDecimal.ZERO);
        }

        Map<String, Object> entryRow = executions.stream()
                .filter(r -> "ENTRY".equals(str(r.get("execution_type"))))
                .findFirst().orElse(null);
        Map<String, Object> exitRow = executions.stream()
                .filter(r -> "EXIT".equals(str(r.get("execution_type"))))
                .findFirst().orElse(null);

        ExecutionChapter.EntryExecution entry = entryRow == null ? null :
                new ExecutionChapter.EntryExecution(
                        toLocalDateTime(entryRow.get("placed_at")),
                        toLocalDateTime(entryRow.get("executed_at")),
                        PortfolioMetricsCalculator.toBd(entryRow.get("requested_net_premium")),
                        PortfolioMetricsCalculator.toBd(entryRow.get("actual_net_premium")),
                        PortfolioMetricsCalculator.toBd(entryRow.get("slippage_amount")),
                        computeSlippagePct(entryRow),
                        toIntPrimitive(entryRow.get("requested_lots")),
                        toIntPrimitive(entryRow.get("filled_lots")),
                        str(entryRow.get("broker_status"))
                );

        ExecutionChapter.ExitExecution exit = exitRow == null ? null :
                new ExecutionChapter.ExitExecution(
                        toLocalDateTime(exitRow.get("placed_at")),
                        toLocalDateTime(exitRow.get("executed_at")),
                        PortfolioMetricsCalculator.toBd(exitRow.get("actual_net_premium")),
                        toIntPrimitive(exitRow.get("filled_lots")),
                        str(exitRow.get("broker_status")),
                        str(exitRow.get("failure_reason"))
                );

        BigDecimal totalSlippage = executions.stream()
                .map(r -> PortfolioMetricsCalculator.toBd(r.get("slippage_amount")))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ExecutionChapter(entry, exit, totalSlippage);
    }

    private MonitoringChapter toMonitoringChapter(List<Map<String, Object>> evaluations,
                                                    Map<String, Object> tradeRow) {
        List<MonitoringEventDto> events = evaluations == null
                ? List.of()
                : evaluations.stream().map(this::toEvent).toList();

        int hold      = events.stream().filter(e -> "HOLD".equals(e.action())).mapToInt(x -> 1).sum();
        int watch     = events.stream().filter(e -> "WATCH".equals(e.action())).mapToInt(x -> 1).sum();
        int readjust  = events.stream().filter(e -> "READJUST".equals(e.action())).mapToInt(x -> 1).sum();

        return new MonitoringChapter(
                events,
                hold,
                watch,
                readjust,
                str(tradeRow.get("exit_triggered_by")),
                PortfolioMetricsCalculator.toBd(tradeRow.get("actual_pnl"))
        );
    }

    private MonitoringEventDto toEvent(Map<String, Object> row) {
        return new MonitoringEventDto(
                toLocalDateTime(row.get("evaluated_at")),
                str(row.get("action")),
                PortfolioMetricsCalculator.toBd(row.get("spot_price")),
                PortfolioMetricsCalculator.toBd(row.get("vix_level")),
                PortfolioMetricsCalculator.toBd(row.get("mark_to_market_pnl")),
                PortfolioMetricsCalculator.toBd(row.get("current_pop")),
                str(row.get("threshold_hit")),
                str(row.get("reason"))
        );
    }

    // ── JSONB parsers ─────────────────────────────────────────

    private Map<String, BigDecimal> parseTierScores(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});
            Map<String, BigDecimal> labelled = new LinkedHashMap<>();
            raw.forEach((key, val) -> {
                String label = TIER_LABELS.getOrDefault(key, key);
                labelled.put(label, PortfolioMetricsCalculator.toBd(val));
            });
            return labelled;
        } catch (Exception e) {
            log.warn("Failed to parse score_breakdown JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseDataGaps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse data_gaps JSON: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseLegs(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse legs JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * gate_results is a JSON array of GateResultDto objects.
     * Converts to Map<gateLabel, statusString> for clean display,
     * e.g. {"G1 – PoP ≥ 80%": "PASS", "G2 – Max Loss": "INDICATIVE"}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseGateResults(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            List<Map<String, Object>> gates = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {});
            Map<String, String> result = new LinkedHashMap<>();
            for (Map<String, Object> gate : gates) {
                String label       = str(gate.get("gate")) + " – " + str(gate.get("description"));
                boolean passed     = Boolean.TRUE.equals(gate.get("passed"));
                result.put(label, passed ? "PASS" : "FAIL");
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse gate_results JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private BigDecimal computeSlippagePct(Map<String, Object> row) {
        BigDecimal slip     = PortfolioMetricsCalculator.toBd(row.get("slippage_amount"));
        BigDecimal requested = PortfolioMetricsCalculator.toBd(row.get("requested_net_premium"));
        if (slip == null || requested == null || requested.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return slip.abs().multiply(BigDecimal.valueOf(100))
                .divide(requested.abs(), 2, java.math.RoundingMode.HALF_UP);
    }

    // ── Type conversion helpers ───────────────────────────────

    private static String str(Object v)  { return v == null ? null : v.toString(); }

    private static UUID toUuid(Object v) {
        if (v == null) return null;
        if (v instanceof UUID u) return u;
        return UUID.fromString(v.toString());
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof LocalDate ld) return ld;
        return null;
    }

    private static LocalDateTime toLocalDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof LocalDateTime ldt) return ldt;
        return null;
    }

    private static int toIntPrimitive(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private static TradeOutcome toOutcome(String v) {
        if ("WIN".equals(v))  return TradeOutcome.WIN;
        if ("LOSS".equals(v)) return TradeOutcome.LOSS;
        return TradeOutcome.OPEN;
    }
}
