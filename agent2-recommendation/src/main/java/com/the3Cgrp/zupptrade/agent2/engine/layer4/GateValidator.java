package com.the3Cgrp.zupptrade.agent2.engine.layer4;

import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.shared.dto.GateResultDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Layer 4 — Gate Validation.
 *
 * Thresholds: G1/G3/G4 use the user's profile value when set (minPop/maxPopPoppGap/
 * minRocPct), falling back to system config. G1D/G2D/G4D use system config only —
 * there is no per-user field for debit R:R or breakeven distance.
 *
 * Credit path (G1, G2, G3, G4):
 *   G1  (HARD):        PoP ≥ profile.minPop (default 80%; seller's PoP = 1 − buyer's PoP on short leg)
 *   G2  (INDICATIVE):  Max loss per lot — informational, used for sizing only
 *   G3  (HARD):        PoP − PoPP gap ≤ profile.maxPopPoppGap (default 15%)
 *   G4  (HARD):        RoC ≥ profile.minRocPct × (DTE/7) — evaluated after lot sizing
 *
 * Debit path (G1D, G2, G2D, G3D, G4D):
 *   G1D (HARD):        R:R ≥ config.minDebitRr (default 1.4:1, max profit / net debit per unit)
 *   G2  (INDICATIVE):  Net debit and max profit — informational
 *   G2D (HARD):        Breakeven ≤ config.maxDebitBreakevenDistancePts (default 100 pts) from spot
 *   G3D (HARD):        Confidence ≥ MEDIUM (debit needs directional conviction)
 *   G4D (HARD):        Total debit cost ≤ 0.5% of capital — evaluated after lot sizing
 */
@Component
public class GateValidator {

    private static final Logger log = LoggerFactory.getLogger(GateValidator.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final TradingConfig config;

    public GateValidator(TradingConfig config) {
        this.config = config;
    }

    public void execute(RecommendationContext ctx) {
        List<GateResultDto> results;
        if (ctx.getStrategy().isDebit()) {
            results = executeDebitGates(ctx);
        } else {
            results = executeCreditGates(ctx);
        }
        // G4 / G4D are appended by PositionSizer after lot sizing
        ctx.setGateResults(results);

        // G2 is always informational — exclude from hard-gate check
        boolean allPassed = results.stream()
                .filter(g -> !g.gate().equals("G2"))
                .allMatch(GateResultDto::passed);
        ctx.setAllHardGatesPassed(allPassed);

        log.info("layer4.gate.validation",
                kv("strategy", ctx.getStrategy()),
                kv("allHardGatesPassed", allPassed),
                kv("gates", results.stream().map(g -> g.gate() + "=" + g.passed()).toList()));
    }

    // ─── Credit path ────────────────────────────────────────────────────────────

    private List<GateResultDto> executeCreditGates(RecommendationContext ctx) {
        List<GateResultDto> results = new ArrayList<>();
        results.add(validateG1Credit(ctx));
        results.add(validateG2Credit(ctx));
        results.add(validateG3Credit(ctx));
        return results;
    }

    /** G4 (credit) — DTE-adjusted minimum RoC. Called by PositionSizer after sizing. */
    public GateResultDto validateG4(RecommendationContext ctx) {
        BigDecimal roc = ctx.getRoc();
        // Base min RoC comes from the user's profile when set, else the system default.
        BigDecimal minRocBase = (ctx.getUserProfile() != null && ctx.getUserProfile().getMinRocPct() != null)
                ? ctx.getUserProfile().getMinRocPct()
                : config.getMinRocBasePct();
        // minRoc = base% × (DTE / 7) — DTE is calendar days; 7 = calendar days in a week.
        // A full weekly trade (DTE=7) must yield ≥ base%. Shorter entries scale proportionally.
        BigDecimal minRoc = minRocBase
                .multiply(BigDecimal.valueOf(ctx.getDte()))
                .divide(BigDecimal.valueOf(7), 4, RoundingMode.HALF_UP);

        boolean passed = roc != null && roc.compareTo(minRoc) >= 0;

        GateResultDto g4 = new GateResultDto("G4", passed,
                "RoC ≥ " + minRoc.setScale(2, RoundingMode.HALF_UP) + "% (DTE-adjusted minimum)",
                roc, minRoc);

        log.info("layer4.gate.g4",
                kv("passed", passed),
                kv("roc", roc),
                kv("minRoc", minRoc),
                kv("dte", ctx.getDte()));

        return g4;
    }

    private GateResultDto validateG1Credit(RecommendationContext ctx) {
        // Upstox option_greeks.pop is from the BUYER's perspective: P(option expires ITM).
        // Seller's PoP = 1 − buyer's pop. Use SHORT leg — that is the defining risk leg.
        BigDecimal pop = BigDecimal.ONE.subtract(ctx.getShortLeg().pop()).multiply(HUNDRED);

        // Readjustment re-entry uses a relaxed PoP threshold (65% normal VIX, 70% stressed VIX).
        // Otherwise honour the user's profile min PoP (stored as a 0–1 fraction) when set,
        // falling back to the system config default. Profile is the user's control surface.
        BigDecimal threshold;
        if (ctx.getRelaxedGate1PopPct() != null) {
            threshold = ctx.getRelaxedGate1PopPct();
        } else if (ctx.getUserProfile() != null && ctx.getUserProfile().getMinPop() != null) {
            threshold = ctx.getUserProfile().getMinPop().multiply(HUNDRED);
        } else {
            threshold = config.getMinPopSellSpread();
        }

        boolean passed = pop.compareTo(threshold) >= 0;
        String description = (ctx.getRelaxedGate1PopPct() != null)
                ? "PoP ≥ " + threshold + "% (READJUST relaxed gate)"
                : "PoP ≥ " + threshold + "% (sell spread minimum)";

        return new GateResultDto("G1", passed, description, pop.setScale(2, RoundingMode.HALF_UP), threshold);
    }

    private GateResultDto validateG2Credit(RecommendationContext ctx) {
        TradeLegDto shortLeg = ctx.getShortLeg();
        TradeLegDto longLeg = ctx.getLongLeg();
        int spreadWidth = Math.abs(shortLeg.strike() - longLeg.strike());
        BigDecimal netPremium = shortLeg.ltp().subtract(longLeg.ltp());
        BigDecimal maxLossPerLot = BigDecimal.valueOf(spreadWidth)
                .multiply(BigDecimal.valueOf(ctx.getLotSize()))
                .subtract(netPremium.multiply(BigDecimal.valueOf(ctx.getLotSize())));

        BigDecimal maxCapitalAtRisk = ctx.getUserProfile().getCapital()
                .multiply(config.getMinRocBasePct().add(BigDecimal.ONE))
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);

        // G2 is always informational — not a rejection gate
        return new GateResultDto("G2", true,
                "Max loss per lot (indicative, used for sizing only)",
                maxLossPerLot.setScale(2, RoundingMode.HALF_UP),
                maxCapitalAtRisk);
    }

    private GateResultDto validateG3Credit(RecommendationContext ctx) {
        BigDecimal pop = ctx.getShortLeg().pop().multiply(HUNDRED);
        BigDecimal popp = ctx.getLongLeg().pop().multiply(HUNDRED);
        BigDecimal gap = pop.subtract(popp).abs().setScale(2, RoundingMode.HALF_UP);
        // Honour the user's profile PoP−PoPP gap tolerance when set; else system default.
        BigDecimal threshold = (ctx.getUserProfile() != null && ctx.getUserProfile().getMaxPopPoppGap() != null)
                ? ctx.getUserProfile().getMaxPopPoppGap()
                : config.getMaxPopPoppGap();

        boolean passed = gap.compareTo(threshold) <= 0;

        return new GateResultDto("G3", passed,
                "PoP − PoPP ≤ " + threshold + "% (spread width risk check)",
                gap, threshold);
    }

    // ─── Debit path ─────────────────────────────────────────────────────────────

    private List<GateResultDto> executeDebitGates(RecommendationContext ctx) {
        List<GateResultDto> results = new ArrayList<>();
        results.add(validateG1D(ctx));
        results.add(validateG2Debit(ctx));
        results.add(validateG2D(ctx));
        results.add(validateG3D(ctx));
        return results;
    }

    /** G4D (debit) — total max loss ≤ 0.5% of capital. Called by PositionSizer after sizing. */
    public GateResultDto validateG4D(RecommendationContext ctx) {
        BigDecimal netDebitPerUnit = ctx.getLongLeg().ltp().subtract(ctx.getShortLeg().ltp());
        BigDecimal maxLossTotal = netDebitPerUnit
                .multiply(BigDecimal.valueOf(ctx.getLots()))
                .multiply(BigDecimal.valueOf(ctx.getLotSize()));
        BigDecimal threshold = ctx.getUserProfile().getCapital()
                .multiply(config.getMaxLossDebitPct())
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);

        boolean passed = maxLossTotal.compareTo(threshold) <= 0;

        GateResultDto g4d = new GateResultDto("G4D", passed,
                "Total debit cost ≤ " + config.getMaxLossDebitPct() + "% of capital",
                maxLossTotal.setScale(2, RoundingMode.HALF_UP), threshold);

        log.info("layer4.gate.g4d",
                kv("passed", passed),
                kv("maxLossTotal", maxLossTotal),
                kv("threshold", threshold),
                kv("lots", ctx.getLots()));

        return g4d;
    }

    private GateResultDto validateG1D(RecommendationContext ctx) {
        BigDecimal netDebitPerUnit = ctx.getLongLeg().ltp().subtract(ctx.getShortLeg().ltp());
        int spreadWidth = Math.abs(ctx.getLongLeg().strike() - ctx.getShortLeg().strike());
        BigDecimal maxProfitPerUnit = BigDecimal.valueOf(spreadWidth).subtract(netDebitPerUnit);

        // Guard against zero/negative debit (data error)
        BigDecimal ratio = (netDebitPerUnit.compareTo(BigDecimal.ZERO) > 0)
                ? maxProfitPerUnit.divide(netDebitPerUnit, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal minRr = config.getMinDebitRr();
        boolean passed = ratio.compareTo(minRr) >= 0;

        log.info("layer4.gate.g1d",
                kv("passed", passed),
                kv("netDebit", netDebitPerUnit),
                kv("maxProfit", maxProfitPerUnit),
                kv("rrRatio", ratio),
                kv("minRr", minRr));

        return new GateResultDto("G1D", passed,
                "R:R ≥ " + minRr + ":1 (max profit / net debit per unit)",
                ratio.setScale(2, RoundingMode.HALF_UP), minRr);
    }

    private GateResultDto validateG2Debit(RecommendationContext ctx) {
        BigDecimal netDebitPerUnit = ctx.getLongLeg().ltp().subtract(ctx.getShortLeg().ltp());
        int spreadWidth = Math.abs(ctx.getLongLeg().strike() - ctx.getShortLeg().strike());
        BigDecimal maxProfitPerUnit = BigDecimal.valueOf(spreadWidth).subtract(netDebitPerUnit);

        // G2 is always informational — value = net debit, threshold = max profit (context)
        return new GateResultDto("G2", true,
                "Net debit per unit (informational — max profit = " + maxProfitPerUnit.setScale(2, RoundingMode.HALF_UP) + ")",
                netDebitPerUnit.setScale(2, RoundingMode.HALF_UP),
                maxProfitPerUnit.setScale(2, RoundingMode.HALF_UP));
    }

    private GateResultDto validateG2D(RecommendationContext ctx) {
        BigDecimal netDebitPerUnit = ctx.getLongLeg().ltp().subtract(ctx.getShortLeg().ltp());
        int longStrike = ctx.getLongLeg().strike();
        int shortStrike = ctx.getShortLeg().strike();

        // Call spread (longStrike < shortStrike): breakeven = longStrike + netDebit (spot must rise)
        // Put spread  (longStrike > shortStrike): breakeven = longStrike − netDebit (spot must fall)
        BigDecimal breakeven = (longStrike < shortStrike)
                ? BigDecimal.valueOf(longStrike).add(netDebitPerUnit)
                : BigDecimal.valueOf(longStrike).subtract(netDebitPerUnit);

        BigDecimal distance = ctx.getSpot().subtract(breakeven).abs().setScale(2, RoundingMode.HALF_UP);
        BigDecimal threshold = config.getMaxDebitBreakevenDistancePts();
        boolean passed = distance.compareTo(threshold) <= 0;

        log.info("layer4.gate.g2d",
                kv("passed", passed),
                kv("spot", ctx.getSpot()),
                kv("breakeven", breakeven),
                kv("distance", distance),
                kv("threshold", threshold));

        return new GateResultDto("G2D", passed,
                "Breakeven ≤ " + threshold + " pts from spot",
                distance, threshold);
    }

    private GateResultDto validateG3D(RecommendationContext ctx) {
        Confidence confidence = ctx.getSignal().getConfidence();
        boolean passed = confidence != Confidence.LOW;

        return new GateResultDto("G3D", passed,
                "Confidence ≥ MEDIUM (debit spreads require directional conviction)",
                passed ? BigDecimal.ONE : BigDecimal.ZERO,
                BigDecimal.ONE);
    }
}
