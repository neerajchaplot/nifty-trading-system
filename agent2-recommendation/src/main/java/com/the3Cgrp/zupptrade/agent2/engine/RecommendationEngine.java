package com.the3Cgrp.zupptrade.agent2.engine;

import com.the3Cgrp.zupptrade.agent2.engine.layer1.StrategySelector;
import com.the3Cgrp.zupptrade.agent2.engine.layer2.ExpectedMoveCalculator;
import com.the3Cgrp.zupptrade.agent2.engine.layer3.StrikeSelector;
import com.the3Cgrp.zupptrade.agent2.engine.layer4.GateValidator;
import com.the3Cgrp.zupptrade.agent2.engine.layer5.PositionSizer;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Orchestrates the 5-layer recommendation algorithm.
 * Each layer reads from and writes into the shared RecommendationContext.
 * Halts early if strategy resolves to NO_TRADE or SKIP, or if hard gates fail.
 */
@Component
public class RecommendationEngine {

    private static final Logger log = LoggerFactory.getLogger(RecommendationEngine.class);

    private final StrategySelector strategySelector;
    private final ExpectedMoveCalculator expectedMoveCalculator;
    private final StrikeSelector strikeSelector;
    private final GateValidator gateValidator;
    private final PositionSizer positionSizer;

    public RecommendationEngine(StrategySelector strategySelector,
                                ExpectedMoveCalculator expectedMoveCalculator,
                                StrikeSelector strikeSelector,
                                GateValidator gateValidator,
                                PositionSizer positionSizer) {
        this.strategySelector = strategySelector;
        this.expectedMoveCalculator = expectedMoveCalculator;
        this.strikeSelector = strikeSelector;
        this.gateValidator = gateValidator;
        this.positionSizer = positionSizer;
    }

    public RecommendationContext execute(RecommendationContext ctx) {
        log.info("engine.start",
                kv("expiryDate", ctx.getExpiryDate() != null ? ctx.getExpiryDate().toString() : null),
                kv("dte", ctx.getDte()),
                kv("spot", ctx.getSpot()),
                kv("vix", ctx.getVix()));

        strategySelector.execute(ctx);

        if (ctx.getStrategy() == Strategy.NO_TRADE || ctx.getStrategy() == Strategy.SKIP) {
            log.info("engine.early.exit", kv("reason", ctx.getStrategy()));
            return ctx;
        }

        expectedMoveCalculator.execute(ctx);
        strikeSelector.execute(ctx);
        gateValidator.execute(ctx);

        if (!ctx.isAllHardGatesPassed()) {
            log.info("engine.early.exit", kv("reason", "HARD_GATE_FAILURE"));
            return ctx;
        }

        positionSizer.execute(ctx);

        log.info("engine.complete",
                kv("strategy", ctx.getStrategy()),
                kv("lots", ctx.getLots()),
                kv("roc", ctx.getRoc()),
                kv("allGatesPassed", ctx.isAllHardGatesPassed()));

        return ctx;
    }
}
