package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.config.FuturesConfig;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.*;
import com.the3Cgrp.zupptrade.shared.enums.ArmCardStatus;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Composes the pure futures calculators into one complete plan (spec §2–§6).
 *
 * Pipeline: Camarilla → open zone → four-arm grid → arm selection → probability ranking →
 * per-arm cost + R:R (display) → per-arm Camarilla-based sizing + margin → per-arm status
 * (confidence is the only hard gate; compression vetoes rotation arms only).
 *
 * No Spring/DB/I-O — fully unit-testable end-to-end.
 */
@Component
public class FuturesPlanEngine {

    private final CamarillaCalculator camarilla;
    private final OpenClassifier openClassifier;
    private final FourArmGridBuilder gridBuilder;
    private final ArmSelector armSelector;
    private final ProbabilityRanker probabilityRanker;
    private final RiskRewardCalculator riskReward;
    private final CostModel costModel;
    private final CompressionGate compressionGate;
    private final PositionSizer positionSizer;
    private final MarginEstimator marginEstimator;
    private final ConfidenceGate confidenceGate;

    public FuturesPlanEngine(CamarillaCalculator camarilla, OpenClassifier openClassifier,
                             FourArmGridBuilder gridBuilder, ArmSelector armSelector,
                             ProbabilityRanker probabilityRanker, RiskRewardCalculator riskReward,
                             CostModel costModel, CompressionGate compressionGate,
                             PositionSizer positionSizer, MarginEstimator marginEstimator,
                             ConfidenceGate confidenceGate) {
        this.camarilla = camarilla;
        this.openClassifier = openClassifier;
        this.gridBuilder = gridBuilder;
        this.armSelector = armSelector;
        this.probabilityRanker = probabilityRanker;
        this.riskReward = riskReward;
        this.costModel = costModel;
        this.compressionGate = compressionGate;
        this.positionSizer = positionSizer;
        this.marginEstimator = marginEstimator;
        this.confidenceGate = confidenceGate;
    }

    public FuturesPlanResult plan(FuturesPlanInputs in, FuturesConfig cfg) {
        CamarillaLevels levels = camarilla.calculate(in.priorHigh(), in.priorLow(), in.priorClose());
        var openZone = openClassifier.classify(in.sessionOpen(), levels);
        List<FuturesArm> grid = gridBuilder.build(levels);
        ArmSelection selection = armSelector.select(in.bias(), openZone);

        List<ArmProbability> probs = probabilityRanker.rank(selection, in.bias(), in.confidenceScore());
        Map<FutureArmType, BigDecimal> probByArm = new EnumMap<>(FutureArmType.class);
        probs.forEach(p -> probByArm.put(p.type(), p.probabilityPct()));

        CompressionResult compression = compressionGate.evaluate(
                in.prevDayRange(), in.last20Ranges(), cfg.getCompressionThreshold());
        ConfidenceGateResult confGate = confidenceGate.validate(in.confidenceScore(), cfg.getMinConfidence());

        BigDecimal riskCapital = in.capital().multiply(cfg.getRiskPerTradePct());
        BigDecimal pointValuePerLot = BigDecimal.valueOf(in.lotSize());

        List<ArmPlan> armPlans = new ArrayList<>();
        for (FuturesArm arm : grid) {
            BigDecimal cost = costModel.roundTripCostPoints(
                    arm.entry(), pointValuePerLot,
                    cfg.getSlippagePoints(), cfg.getFlatChargesPerRoundTrip(), cfg.getTaxPctPerRoundTrip());
            ArmRiskReward rr = riskReward.compute(arm.entry(), arm.stop(), arm.target(), cost);
            SizingResult sizing = positionSizer.size(riskCapital, rr.riskPoints(), in.lotSize());
            MarginEstimate margin = marginEstimator.estimate(arm.entry(), in.lotSize(), sizing.lots(), cfg.getMarginPct());

            String blockedReason = blockReason(arm.type(), confGate, compression);
            ArmCardStatus status = status(arm.type(), selection, blockedReason);

            armPlans.add(new ArmPlan(arm, rr, probByArm.get(arm.type()), sizing, margin, status, blockedReason));
        }

        FutureArmType effectivePrimary = effectivePrimary(selection, armPlans);
        boolean allBlocked = armPlans.stream().allMatch(a -> a.status() == ArmCardStatus.BLOCKED);
        String noTradeReason = noTradeReason(allBlocked, confGate, compression, selection);

        return new FuturesPlanResult(levels, openZone, armPlans, effectivePrimary,
                selection.rangeFade(), selection.reason(), compression, confGate, allBlocked, noTradeReason);
    }

    /** Non-null reason means the arm is blocked. Confidence is global; compression hits rotation only. */
    private String blockReason(FutureArmType type, ConfidenceGateResult confGate, CompressionResult compression) {
        if (!confGate.passed()) {
            return "Signal too weak to trade today";
        }
        if (compressionGate.blocks(type, false, compression)) {
            return "Market is coiled — range trade skipped";
        }
        return null;
    }

    private ArmCardStatus status(FutureArmType type, ArmSelection selection, String blockedReason) {
        if (blockedReason != null) {
            return ArmCardStatus.BLOCKED;
        }
        boolean isRecommended = type == selection.primary()
                || (selection.rangeFade() && type.isRotation());
        return isRecommended ? ArmCardStatus.RECOMMENDED : ArmCardStatus.ALLOWED;
    }

    private FutureArmType effectivePrimary(ArmSelection selection, List<ArmPlan> armPlans) {
        if (selection.primary() == null) {
            return null; // range-fade / no-trade: no single primary (UI highlights RECOMMENDED arms)
        }
        return armPlans.stream()
                .filter(a -> a.arm().type() == selection.primary() && a.status() == ArmCardStatus.RECOMMENDED)
                .map(a -> a.arm().type())
                .findFirst()
                .orElse(null); // primary was blocked (e.g. coiled) → no recommendation, alternatives remain
    }

    private String noTradeReason(boolean allBlocked, ConfidenceGateResult confGate,
                                 CompressionResult compression, ArmSelection selection) {
        if (allBlocked) {
            return !confGate.passed()
                    ? "Signal too weak — no trades today"
                    : "All trades blocked today";
        }
        if (selection.noTrade()) {
            return selection.reason(); // no clean primary (bias vs open conflict) — user may still choose
        }
        if (compression.compressed()) {
            return "Market coiled — range trades skipped; breakout trades available";
        }
        return null;
    }
}
