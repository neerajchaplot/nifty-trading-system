package com.the3Cgrp.zupptrade.core.explain;

import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.IvRegime;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared English phrasing for market concepts, used by both agent1 and agent2 explanation
 * builders so the same enum/number is always worded the same way. Pure static helpers —
 * deterministic, null-safe, no Spring.
 */
public final class MarketVocab {

    private MarketVocab() {}

    /** e.g. BULLISH+MILD → "mildly bullish"; NEUTRAL → "neutral". */
    public static String bias(Bias bias, Strength strength) {
        if (bias == null || bias == Bias.NEUTRAL) return "neutral";
        String direction = bias == Bias.BULLISH ? "bullish" : "bearish";
        if (strength == null) return direction;
        return switch (strength) {
            case EXTREME -> "strongly " + direction;
            case MILD    -> "mildly " + direction;
            case WEAK    -> "marginally " + direction;
        };
    }

    /** Sign of a tier/score → "bullish" / "bearish" / "flat". */
    public static String lean(BigDecimal score) {
        if (score == null || score.signum() == 0) return "flat";
        return score.signum() > 0 ? "bullish" : "bearish";
    }

    /** e.g. HIGH + 18.61 → "VIX 18.6 (High)". */
    public static String vix(VixRegime regime, BigDecimal level) {
        String reg = regime == null ? "" : titleCase(regime.name());
        if (level == null) {
            return reg.isEmpty() ? "VIX" : "VIX (" + reg + ")";
        }
        String lvl = level.setScale(1, RoundingMode.HALF_UP).toPlainString();
        return reg.isEmpty() ? "VIX " + lvl : "VIX " + lvl + " (" + reg + ")";
    }

    /** e.g. RICH → "rich IV". */
    public static String iv(IvRegime regime) {
        if (regime == null) return "IV";
        return switch (regime) {
            case RICH  -> "rich IV";
            case FAIR  -> "fair IV";
            case CHEAP -> "cheap IV";
        };
    }

    /** e.g. 82.60, 1 → "82.6%". */
    public static String pct(BigDecimal value, int decimals) {
        if (value == null) return "—"; // em dash
        return value.setScale(decimals, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** Composite score with an explicit sign, e.g. +0.34 / -0.29. */
    public static String signedScore(BigDecimal score, int decimals) {
        if (score == null) return "—";
        BigDecimal s = score.setScale(decimals, RoundingMode.HALF_UP);
        return (s.signum() >= 0 ? "+" : "") + s.toPlainString();
    }

    /** HIGH → "High". */
    private static String titleCase(String enumName) {
        if (enumName.isEmpty()) return enumName;
        return enumName.charAt(0) + enumName.substring(1).toLowerCase();
    }
}
