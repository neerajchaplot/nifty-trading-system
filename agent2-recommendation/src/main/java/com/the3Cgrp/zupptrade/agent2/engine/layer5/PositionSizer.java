package com.the3Cgrp.zupptrade.agent2.engine.layer5;

import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.agent2.engine.layer4.GateValidator;
import com.the3Cgrp.zupptrade.shared.dto.GateResultDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Layer 5 — Position Sizing.
 *
 * Real expected loss per lot = max_loss_per_lot × 0.50 (Agent 3 T2 exit assumption).
 * Lots = floor(max_loss_budget ÷ real_expected_loss_per_lot).
 * Max loss budget = capital × max_loss_pct / 100.
 *
 * After lot sizing, G4 (RoC gate) is evaluated and appended to gate results.
 */
@Component
public class PositionSizer {

    private static final Logger log = LoggerFactory.getLogger(PositionSizer.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    // Credit: Agent 3 exits at T3 when MTM loss reaches 50% of theoretical max loss (per CLAUDE.md Layer 5).
    private static final BigDecimal CREDIT_REAL_LOSS_FACTOR = new BigDecimal("0.50");
    // Debit: Agent 3 exits at T3 when MTM loss = 50% of premium paid (per CLAUDE.md monitor thresholds).
    private static final BigDecimal DEBIT_REAL_LOSS_FACTOR = new BigDecimal("0.50");
    private static final BigDecimal ANNUALISATION_DAYS = BigDecimal.valueOf(252);

    private final GateValidator gateValidator;
    private final TradingConfig config;

    public PositionSizer(GateValidator gateValidator, TradingConfig config) {
        this.gateValidator = gateValidator;
        this.config = config;
    }

    public void execute(RecommendationContext ctx) {
        TradeLegDto shortLeg = ctx.getShortLeg();
        TradeLegDto longLeg = ctx.getLongLeg();
        int lotSize = ctx.getLotSize();
        BigDecimal capital = ctx.getUserProfile().getCapital();

        // Iron Condor has a second spread (CE side) stored in shortLeg2/longLeg2.
        // Net premium = sum of both spreads; max loss = larger spread width - total premium
        // (a condor can only lose on one side at expiry).
        boolean hasLeg2 = ctx.getShortLeg2() != null && ctx.getLongLeg2() != null;

        int spreadWidth1 = Math.abs(shortLeg.strike() - longLeg.strike());
        int spreadWidth = hasLeg2
                ? Math.max(spreadWidth1, Math.abs(ctx.getShortLeg2().strike() - ctx.getLongLeg2().strike()))
                : spreadWidth1;

        BigDecimal netPremiumPerUnit = computeNetPremium(ctx.getSpreadDirection(), shortLeg, longLeg);
        if (hasLeg2) {
            netPremiumPerUnit = netPremiumPerUnit
                    .add(computeNetPremium(ctx.getSpreadDirection(), ctx.getShortLeg2(), ctx.getLongLeg2()));
        }

        BigDecimal maxLossPerLot = computeMaxLossPerLot(ctx.getSpreadDirection(), spreadWidth, netPremiumPerUnit, lotSize);
        BigDecimal maxProfitPerLot = computeMaxProfitPerLot(ctx.getSpreadDirection(), spreadWidth, netPremiumPerUnit, lotSize);

        int lots;
        BigDecimal realExpectedLossTotal;
        if (ctx.getStrategy().isDebit()) {
            lots = sizeDebitLots(capital, maxLossPerLot);
            realExpectedLossTotal = maxLossPerLot.multiply(BigDecimal.valueOf(lots))
                    .multiply(DEBIT_REAL_LOSS_FACTOR).setScale(2, RoundingMode.HALF_UP);
        } else {
            lots = sizeCreditLots(capital, ctx.getUserProfile().getMaxLossPct(), maxLossPerLot);
            realExpectedLossTotal = maxLossPerLot.multiply(BigDecimal.valueOf(lots))
                    .multiply(CREDIT_REAL_LOSS_FACTOR).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal maxProfitTotal = maxProfitPerLot.multiply(BigDecimal.valueOf(lots));
        BigDecimal theoreticalMaxLossTotal = maxLossPerLot.multiply(BigDecimal.valueOf(lots));

        BigDecimal roc = maxProfitTotal.divide(capital, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED).setScale(4, RoundingMode.HALF_UP);
        BigDecimal rocAnnualised = roc.multiply(ANNUALISATION_DAYS)
                .divide(BigDecimal.valueOf(ctx.getDte()), 4, RoundingMode.HALF_UP);

        // Include leg2 delta for net delta calculation (Iron Condor)
        BigDecimal leg2Delta = hasLeg2
                ? ctx.getShortLeg2().delta().add(ctx.getLongLeg2().delta())
                : BigDecimal.ZERO;
        BigDecimal netDelta = shortLeg.delta().add(longLeg.delta()).add(leg2Delta)
                .setScale(4, RoundingMode.HALF_UP);

        ctx.setLots(lots);
        ctx.setMaxProfitTotal(maxProfitTotal);
        ctx.setTheoreticalMaxLossTotal(theoreticalMaxLossTotal);
        ctx.setRealExpectedLossTotal(realExpectedLossTotal);
        ctx.setRoc(roc);
        ctx.setRocAnnualised(rocAnnualised);
        ctx.setNetDelta(netDelta);

        // G4 / G4D can only be evaluated after sizing is complete — append to existing gate results
        GateResultDto finalGate = ctx.getStrategy().isDebit()
                ? gateValidator.validateG4D(ctx)
                : gateValidator.validateG4(ctx);
        List<GateResultDto> allGates = new java.util.ArrayList<>(ctx.getGateResults());
        allGates.add(finalGate);
        ctx.setGateResults(allGates);

        if (!finalGate.passed()) {
            ctx.setAllHardGatesPassed(false);
        }

        log.info("layer5.position.sizing",
                kv("strategy", ctx.getStrategy()),
                kv("lots", lots),
                kv("maxProfitTotal", maxProfitTotal),
                kv("theoreticalMaxLossTotal", theoreticalMaxLossTotal),
                kv("realExpectedLossTotal", realExpectedLossTotal),
                kv("roc", roc),
                kv("rocAnnualised", rocAnnualised),
                kv("netDelta", netDelta),
                kv("finalGatePassed", finalGate.passed()));
    }

    private int sizeDebitLots(BigDecimal capital, BigDecimal maxLossPerLot) {
        // Budget = 0.5% of capital; maxLossPerLot = net debit × lotSize (the most we can lose per lot)
        BigDecimal budget = capital.multiply(config.getMaxLossDebitPct()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        int lots = maxLossPerLot.compareTo(BigDecimal.ZERO) > 0
                ? budget.divide(maxLossPerLot, 0, RoundingMode.FLOOR).intValue()
                : 1;
        return Math.max(lots, 1);
    }

    private int sizeCreditLots(BigDecimal capital, BigDecimal maxLossPct, BigDecimal maxLossPerLot) {
        BigDecimal realExpectedLossPerLot = maxLossPerLot.multiply(CREDIT_REAL_LOSS_FACTOR)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal maxLossBudget = capital.multiply(maxLossPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        int lots = realExpectedLossPerLot.compareTo(BigDecimal.ZERO) > 0
                ? maxLossBudget.divide(realExpectedLossPerLot, 0, RoundingMode.FLOOR).intValue()
                : 1;
        return Math.max(lots, 1);
    }

    private BigDecimal computeNetPremium(SpreadDirection direction, TradeLegDto shortLeg, TradeLegDto longLeg) {
        return switch (direction) {
            case CREDIT -> shortLeg.ltp().subtract(longLeg.ltp());   // net received
            case DEBIT  -> longLeg.ltp().subtract(shortLeg.ltp());   // net paid
        };
    }

    private BigDecimal computeMaxLossPerLot(SpreadDirection direction, int spreadWidth,
                                             BigDecimal netPremiumPerUnit, int lotSize) {
        BigDecimal spreadValue = BigDecimal.valueOf(spreadWidth).multiply(BigDecimal.valueOf(lotSize));
        BigDecimal premiumTotal = netPremiumPerUnit.multiply(BigDecimal.valueOf(lotSize));
        return switch (direction) {
            case CREDIT -> spreadValue.subtract(premiumTotal);  // (spread_width × lot_size) - net_premium
            case DEBIT  -> premiumTotal;                        // cost to enter is the max loss
        };
    }

    private BigDecimal computeMaxProfitPerLot(SpreadDirection direction, int spreadWidth,
                                               BigDecimal netPremiumPerUnit, int lotSize) {
        BigDecimal premiumTotal = netPremiumPerUnit.multiply(BigDecimal.valueOf(lotSize));
        BigDecimal spreadValue = BigDecimal.valueOf(spreadWidth).multiply(BigDecimal.valueOf(lotSize));
        return switch (direction) {
            case CREDIT -> premiumTotal;                         // net premium received
            case DEBIT  -> spreadValue.subtract(premiumTotal);  // (spread_width × lot_size) - cost
        };
    }
}
