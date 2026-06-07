package com.the3Cgrp.zupptrade.agent2.engine.layer4;

import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.shared.dto.GateResultDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
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
 * G1 (HARD): PoP ≥ 80% for sell spreads, ≥ 35% breakeven PoP for debit spreads.
 * G2 (INDICATIVE): Max loss ≤ 1.5% of capital. Used for lot sizing, not rejection.
 * G3 (HARD): PoP − PoPP ≤ 15% for sell spreads (gap rule).
 * G4 (HARD): RoC ≥ 0.5% × (DTE/5), DTE-adjusted minimum return.
 *
 * G2 is always marked passed=true — it is informational, not a rejection gate.
 * G4 is evaluated after lot sizing — it runs on the final expected RoC.
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
        List<GateResultDto> results = new ArrayList<>();
        boolean isSellSpread = ctx.getSpreadDirection() == SpreadDirection.CREDIT;

        GateResultDto g1 = validateG1(ctx, isSellSpread);
        GateResultDto g2 = validateG2(ctx);
        GateResultDto g3 = validateG3(ctx, isSellSpread);

        results.add(g1);
        results.add(g2);
        results.add(g3);
        // G4 added after PositionSizer runs — RoC only known after lot sizing
        ctx.setGateResults(results);

        boolean passed = g1.passed() && g3.passed();
        ctx.setAllHardGatesPassed(passed);

        log.info("layer4.gate.validation",
                kv("g1_passed", g1.passed()),
                kv("g1_pop", g1.value()),
                kv("g2_indicative", true),
                kv("g3_passed", g3.passed()),
                kv("g3_gap", g3.value()),
                kv("allHardGatesPassed", passed));
    }

    public GateResultDto validateG4(RecommendationContext ctx) {
        BigDecimal roc = ctx.getRoc();
        // minRoc = 0.5% × (DTE / 7) — DTE is calendar days; 7 = calendar days in a week.
        // A full weekly trade (DTE=7) must yield ≥ 0.5%. Shorter entries scale proportionally.
        BigDecimal minRoc = config.getMinRocBasePct()
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

    private GateResultDto validateG1(RecommendationContext ctx, boolean isSellSpread) {
        // Upstox option_greeks.pop is from the BUYER's perspective: P(option expires ITM).
        //
        // Credit spreads (sell spread): short leg is the leg we sold. Seller profits when
        //   option expires OTM, so seller's PoP = 1 - buyer's pop.
        //   We use the SHORT leg because that is the defining risk leg.
        //
        // Debit spreads (buy spread): we want to know P(trade is profitable) = P(long leg expires ITM).
        //   The long leg (ATM call for BULL_CALL_SPREAD) has PoP ≈ 50% from the buyer's perspective.
        //   We use the LONG leg — not the short OTM leg — because the long leg determines
        //   whether the trade expires in-the-money at all.
        BigDecimal pop = isSellSpread
                ? BigDecimal.ONE.subtract(ctx.getShortLeg().pop()).multiply(HUNDRED)
                : ctx.getLongLeg().pop().multiply(HUNDRED);
        BigDecimal threshold = isSellSpread
                ? config.getMinPopSellSpread()
                : config.getMinPopDebitSpread();

        boolean passed = pop.compareTo(threshold) >= 0;
        String description = isSellSpread
                ? "PoP ≥ " + threshold + "% (sell spread minimum)"
                : "Breakeven PoP ≥ " + threshold + "% (debit spread minimum)";

        return new GateResultDto("G1", passed, description, pop.setScale(2, RoundingMode.HALF_UP), threshold);
    }

    private GateResultDto validateG2(RecommendationContext ctx) {
        TradeLegDto shortLeg = ctx.getShortLeg();
        TradeLegDto longLeg = ctx.getLongLeg();
        int spreadWidth = Math.abs(shortLeg.strike() - longLeg.strike());
        BigDecimal netPremium = shortLeg.ltp().subtract(longLeg.ltp());
        BigDecimal maxLossPerLot = BigDecimal.valueOf(spreadWidth)
                .multiply(BigDecimal.valueOf(ctx.getLotSize()))
                .subtract(netPremium.multiply(BigDecimal.valueOf(ctx.getLotSize())));

        BigDecimal maxCapitalAtRisk = ctx.getUserProfile().getCapital()
                .multiply(config.getMinRocBasePct().add(BigDecimal.ONE)) // indicative 1 lot check
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);

        // G2 is always informational — not a rejection gate
        return new GateResultDto("G2", true,
                "Max loss per lot (indicative, used for sizing only)",
                maxLossPerLot.setScale(2, RoundingMode.HALF_UP),
                maxCapitalAtRisk);
    }

    private GateResultDto validateG3(RecommendationContext ctx, boolean isSellSpread) {
        if (!isSellSpread) {
            return new GateResultDto("G3", true, "G3 not applicable for debit spreads",
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal pop = ctx.getShortLeg().pop().multiply(HUNDRED);
        BigDecimal popp = ctx.getLongLeg().pop().multiply(HUNDRED);
        BigDecimal gap = pop.subtract(popp).abs().setScale(2, RoundingMode.HALF_UP);
        BigDecimal threshold = config.getMaxPopPoppGap();

        boolean passed = gap.compareTo(threshold) <= 0;

        return new GateResultDto("G3", passed,
                "PoP − PoPP ≤ " + threshold + "% (spread width risk check)",
                gap, threshold);
    }
}
