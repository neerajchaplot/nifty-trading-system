package com.the3Cgrp.zupptrade.agent2.engine.layer5;

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
    // Agent 3 exits the trade at T3 when mark-to-market loss reaches 30% of theoretical max loss.
    // Sizing is based on this real expected loss, not the theoretical max wipeout.
    private static final BigDecimal REAL_LOSS_FACTOR = new BigDecimal("0.30");
    private static final BigDecimal ANNUALISATION_DAYS = BigDecimal.valueOf(252);

    private final GateValidator gateValidator;

    public PositionSizer(GateValidator gateValidator) {
        this.gateValidator = gateValidator;
    }

    public void execute(RecommendationContext ctx) {
        TradeLegDto shortLeg = ctx.getShortLeg();
        TradeLegDto longLeg = ctx.getLongLeg();
        int lotSize = ctx.getLotSize();
        BigDecimal capital = ctx.getUserProfile().getCapital();
        BigDecimal maxLossPct = ctx.getUserProfile().getMaxLossPct();

        int spreadWidth = Math.abs(shortLeg.strike() - longLeg.strike());
        BigDecimal netPremiumPerUnit = computeNetPremium(ctx.getSpreadDirection(), shortLeg, longLeg);

        BigDecimal maxLossPerLot = computeMaxLossPerLot(ctx.getSpreadDirection(), spreadWidth, netPremiumPerUnit, lotSize);
        BigDecimal realExpectedLossPerLot = maxLossPerLot.multiply(REAL_LOSS_FACTOR).setScale(2, RoundingMode.HALF_UP);

        BigDecimal maxLossBudget = capital.multiply(maxLossPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);

        int lots = realExpectedLossPerLot.compareTo(BigDecimal.ZERO) > 0
                ? maxLossBudget.divide(realExpectedLossPerLot, 0, RoundingMode.FLOOR).intValue()
                : 1;
        lots = Math.max(lots, 1);

        BigDecimal maxProfitPerLot = computeMaxProfitPerLot(ctx.getSpreadDirection(), spreadWidth, netPremiumPerUnit, lotSize);
        BigDecimal maxProfitTotal = maxProfitPerLot.multiply(BigDecimal.valueOf(lots));
        BigDecimal theoreticalMaxLossTotal = maxLossPerLot.multiply(BigDecimal.valueOf(lots));
        BigDecimal realExpectedLossTotal = realExpectedLossPerLot.multiply(BigDecimal.valueOf(lots));

        BigDecimal roc = maxProfitTotal.divide(capital, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED).setScale(4, RoundingMode.HALF_UP);
        BigDecimal rocAnnualised = roc.multiply(ANNUALISATION_DAYS)
                .divide(BigDecimal.valueOf(ctx.getDte()), 4, RoundingMode.HALF_UP);

        BigDecimal netDelta = shortLeg.delta().add(longLeg.delta()).setScale(4, RoundingMode.HALF_UP);

        ctx.setLots(lots);
        ctx.setMaxProfitTotal(maxProfitTotal);
        ctx.setTheoreticalMaxLossTotal(theoreticalMaxLossTotal);
        ctx.setRealExpectedLossTotal(realExpectedLossTotal);
        ctx.setRoc(roc);
        ctx.setRocAnnualised(rocAnnualised);
        ctx.setNetDelta(netDelta);

        // G4 can only be evaluated after RoC is known — append to existing gate results
        GateResultDto g4 = gateValidator.validateG4(ctx);
        List<GateResultDto> allGates = new java.util.ArrayList<>(ctx.getGateResults());
        allGates.add(g4);
        ctx.setGateResults(allGates);

        // G4 failure is informational for SKIP signal, not hard rejection (RoC may still be acceptable)
        if (!g4.passed()) {
            ctx.setAllHardGatesPassed(false);
        }

        log.info("layer5.position.sizing",
                kv("lots", lots),
                kv("maxProfitTotal", maxProfitTotal),
                kv("theoreticalMaxLossTotal", theoreticalMaxLossTotal),
                kv("realExpectedLossTotal", realExpectedLossTotal),
                kv("roc", roc),
                kv("rocAnnualised", rocAnnualised),
                kv("netDelta", netDelta),
                kv("g4_passed", g4.passed()));
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
