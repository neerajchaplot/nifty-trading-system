package com.the3Cgrp.zupptrade.agent4.mapper;

import com.the3Cgrp.zupptrade.agent4.calculator.PortfolioMetricsCalculator;
import com.the3Cgrp.zupptrade.agent4.domain.dto.response.TradeListItemDto;
import com.the3Cgrp.zupptrade.agent4.domain.enums.TradeOutcome;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public final class TradeListItemMapper {

    private TradeListItemMapper() {}

    public static TradeListItemDto fromRow(Map<String, Object> row) {
        return new TradeListItemDto(
                toUuid(row.get("trade_id")),
                str(row.get("trade_code")),
                toLocalDate(row.get("expiry_date")),
                str(row.get("strategy")),
                str(row.get("spread_direction")),

                str(row.get("signal_bias")),
                str(row.get("signal_strength")),
                PortfolioMetricsCalculator.toBd(row.get("signal_score")),
                str(row.get("signal_confidence_label")),

                toLocalDateTime(row.get("entry_date")),
                toLocalDateTime(row.get("exit_date")),
                toInt(row.get("holding_days")),

                toIntPrimitive(row.get("lots")),
                PortfolioMetricsCalculator.toBd(row.get("entry_net_premium")),
                PortfolioMetricsCalculator.toBd(row.get("entry_spot")),
                str(row.get("entry_vix_regime")),

                PortfolioMetricsCalculator.toBd(row.get("actual_pnl")),
                PortfolioMetricsCalculator.toBd(row.get("roc_achieved_pct")),
                toIntPrimitive(row.get("adjustment_count")),
                str(row.get("close_reason")),
                toOutcome(str(row.get("outcome")))
        );
    }

    // ── Conversion helpers ────────────────────────────────────

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

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

    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static int toIntPrimitive(Object v) {
        Integer i = toInt(v);
        return i == null ? 0 : i;
    }

    private static TradeOutcome toOutcome(String v) {
        if (v == null) return TradeOutcome.OPEN;
        return switch (v) {
            case "WIN"  -> TradeOutcome.WIN;
            case "LOSS" -> TradeOutcome.LOSS;
            default     -> TradeOutcome.OPEN;
        };
    }
}
