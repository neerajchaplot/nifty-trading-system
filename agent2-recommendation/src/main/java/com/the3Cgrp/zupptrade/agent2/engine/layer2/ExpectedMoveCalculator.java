package com.the3Cgrp.zupptrade.agent2.engine.layer2;

import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.agent2.engine.math.BlackScholesCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Layer 2 — Expected Move Calculation.
 *
 * Method 1 (cross-check): ATM straddle price = ATM CE LTP + ATM PE LTP
 * Method 2 (primary):     EM = Spot × ATM IV × √(DTE/365)
 * 1.4 SD boundary used for short strike placement (84% probability).
 *
 * If divergence between methods > 15%, logs a warning — unusual environment.
 * Uses the more conservative (larger) boundary in that case.
 */
@Component
public class ExpectedMoveCalculator {

    private static final Logger log = LoggerFactory.getLogger(ExpectedMoveCalculator.class);
    // 1.2 SD captures 77% of the two-sided normal distribution band (88.5% one-sided put PoP).
    // Chosen over 1.4 SD (84% band / 92% one-sided) to bring the short strike closer to spot
    // and collect meaningful premium while still maintaining a high probability of expiry OTM.
    private static final BigDecimal SD_MULTIPLIER = new BigDecimal("1.2");
    private static final BigDecimal DIVERGENCE_THRESHOLD = new BigDecimal("0.15");

    private final BlackScholesCalculator blackScholes;

    public ExpectedMoveCalculator(BlackScholesCalculator blackScholes) {
        this.blackScholes = blackScholes;
    }

    public void execute(RecommendationContext ctx) {
        BigDecimal spot = ctx.getSpot();
        int dte = ctx.getDte();

        BigDecimal atmIv = ctx.getOptionChainData().calls().stream()
                .filter(s -> s.strike() == ctx.getOptionChainData().atmStrike())
                .findFirst()
                .map(s -> s.iv())
                .orElse(BigDecimal.ZERO);

        // Method 2 — Black-Scholes expected move
        BigDecimal bsExpectedMove = blackScholes.expectedMove(spot, atmIv, dte);
        BigDecimal oneFourBoundary = bsExpectedMove.multiply(SD_MULTIPLIER).setScale(2, RoundingMode.HALF_UP);

        // Method 1 — ATM straddle cross-check
        BigDecimal straddleMove = ctx.getOptionChainData().atmCallLtp()
                .add(ctx.getOptionChainData().atmPutLtp())
                .setScale(2, RoundingMode.HALF_UP);

        checkDivergence(bsExpectedMove, straddleMove, ctx, spot);

        ctx.setExpectedMove(bsExpectedMove);
        ctx.setOneFourSdBoundary(oneFourBoundary);
        ctx.setMarketExpectedMove(straddleMove);

        log.info("layer2.expected.move",
                kv("spot", spot),
                kv("atmIv", atmIv),
                kv("dte", dte),
                kv("bsExpectedMove", bsExpectedMove),
                kv("straddleMove", straddleMove),
                kv("oneFourSdBoundary", oneFourBoundary));
    }

    private void checkDivergence(BigDecimal bsMove, BigDecimal straddleMove,
                                  RecommendationContext ctx, BigDecimal spot) {
        if (straddleMove.compareTo(BigDecimal.ZERO) == 0) return;

        BigDecimal divergence = bsMove.subtract(straddleMove).abs()
                .divide(straddleMove, 4, RoundingMode.HALF_UP);

        if (divergence.compareTo(DIVERGENCE_THRESHOLD) > 0) {
            log.warn("layer2.expected.move.divergence",
                    kv("bsMove", bsMove),
                    kv("straddleMove", straddleMove),
                    kv("divergencePct", divergence.multiply(BigDecimal.valueOf(100))),
                    kv("action", "using_conservative_boundary"));

            // Use the larger of the two as the conservative choice
            BigDecimal conservativeMove = bsMove.max(straddleMove);
            ctx.setOneFourSdBoundary(conservativeMove.multiply(SD_MULTIPLIER).setScale(2, RoundingMode.HALF_UP));
        }
    }
}
