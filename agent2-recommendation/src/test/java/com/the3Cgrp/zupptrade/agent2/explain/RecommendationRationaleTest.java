package com.the3Cgrp.zupptrade.agent2.explain;

import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.shared.dto.GateResultDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.IvRegime;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationRationaleTest {

    private static TradeLegDto leg(OptionType type, int strike, LegAction action,
                                   String delta, String pop) {
        return new TradeLegDto(type, strike, new BigDecimal("50.00"), action,
                delta == null ? null : new BigDecimal(delta),
                pop == null ? null : new BigDecimal(pop), null);
    }

    private static GateResultDto gate(String code, boolean passed, String value, String threshold, String desc) {
        return new GateResultDto(code, passed, desc,
                value == null ? null : new BigDecimal(value),
                threshold == null ? null : new BigDecimal(threshold));
    }

    private static RecommendationContext creditBullPut() {
        RecommendationContext ctx = new RecommendationContext();
        ctx.setStrategy(Strategy.BULL_PUT_SPREAD);
        ctx.setSpreadDirection(SpreadDirection.CREDIT);
        ctx.setEffectiveBias(Bias.BULLISH);
        ctx.setEffectiveStrength(Strength.MILD);
        ctx.setIvRegime(IvRegime.RICH);
        ctx.setVix(new BigDecimal("15.0"));
        ctx.setShortLeg(leg(OptionType.PE, 23750, LegAction.SELL, "-0.17", "0.17"));
        ctx.setLongLeg(leg(OptionType.PE, 23650, LegAction.BUY, "-0.14", "0.14"));
        ctx.setLots(54);
        ctx.setAllHardGatesPassed(true);
        ctx.setGateResults(List.of(
                gate("G1", true, "82.6", "80", "PoP >= 80%"),
                gate("G3", true, "3.2", "15", "PoP-PoPP gap <= 15%"),
                gate("G4", true, "1.63", "0.5", "RoC >= minimum")));
        return ctx;
    }

    @Test
    void creditSpread_allGatesPassed_readsNaturally() {
        String out = RecommendationRationale.build(creditBullPut());

        assertThat(out).contains("Chosen strategy: Bull Put Spread — the signal is mildly bullish "
                + "with rich IV and VIX 15.0, so the plan collects premium rather than paying for direction");
        assertThat(out).contains("It sells the 23750 PE (~83% chance of expiring worthless, delta -0.17)"
                + " and buys the 23650 PE as a hedge, sized at 54 lots");
        assertThat(out).contains("All checks passed: PoP 82.6%, PoP–PoPP gap 3.2pp, RoC 1.63%");
    }

    @Test
    void vixExtreme_skip_returnsPauseMessageOnly() {
        RecommendationContext ctx = new RecommendationContext();
        ctx.setSkipDecision(true);
        ctx.setSkipReason("VIX_EXTREME");
        ctx.setStrategy(Strategy.SKIP);
        ctx.setVix(new BigDecimal("26.0"));

        String out = RecommendationRationale.build(ctx);

        assertThat(out).isEqualTo("No trade — with VIX 26.0 the system pauses auto-trading "
                + "and flags it for manual review.");
        assertThat(out).doesNotContain("Chosen strategy");
    }

    @Test
    void rejected_namesFirstFailingGate() {
        RecommendationContext ctx = creditBullPut();
        ctx.setAllHardGatesPassed(false);
        ctx.setGateResults(List.of(
                gate("G1", false, "78", "80", "PoP >= 80%"),
                gate("G3", true, "3.2", "15", "PoP-PoPP gap <= 15%")));

        String out = RecommendationRationale.build(ctx);

        assertThat(out).contains("Rejected at G1 — PoP >= 80% (78 vs 80 required)");
    }

    @Test
    void ironCondor_describesBothShorts() {
        RecommendationContext ctx = creditBullPut();
        ctx.setStrategy(Strategy.IRON_CONDOR);
        ctx.setShortLeg2(leg(OptionType.CE, 24050, LegAction.SELL, "0.16", "0.16"));
        ctx.setLots(30);

        String out = RecommendationRationale.build(ctx);

        assertThat(out).contains("Chosen strategy: Iron Condor");
        assertThat(out).contains("It sells the 23750 PE and 24050 CE with protective wings, sized at 30 lots");
    }

    @Test
    void debitSpread_leadsWithBoughtLeg() {
        RecommendationContext ctx = new RecommendationContext();
        ctx.setStrategy(Strategy.BULL_CALL_SPREAD);
        ctx.setSpreadDirection(SpreadDirection.DEBIT);
        ctx.setEffectiveBias(Bias.BULLISH);
        ctx.setEffectiveStrength(Strength.EXTREME);
        ctx.setIvRegime(IvRegime.CHEAP);
        ctx.setVix(new BigDecimal("12.0"));
        ctx.setLongLeg(leg(OptionType.CE, 23500, LegAction.BUY, "0.55", "0.55"));
        ctx.setShortLeg(leg(OptionType.CE, 23700, LegAction.SELL, "0.35", "0.35"));
        ctx.setLots(10);
        ctx.setAllHardGatesPassed(true);
        ctx.setGateResults(List.of(gate("G4", true, "2.1", "0.5", "RoC >= minimum")));

        String out = RecommendationRationale.build(ctx);

        assertThat(out).contains("so the plan pays a fixed debit for a directional move");
        assertThat(out).contains("It buys the 23500 CE and sells the 23700 CE to cap the cost, sized at 10 lots");
    }
}
