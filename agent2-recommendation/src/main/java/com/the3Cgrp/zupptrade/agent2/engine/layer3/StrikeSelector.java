package com.the3Cgrp.zupptrade.agent2.engine.layer3;

import com.the3Cgrp.zupptrade.agent2.client.model.StrikeData;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.agent2.exception.MarketDataUnavailableException;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Layer 3 — Strike Selection.
 *
 * Credit spreads: Short strike at or beyond 1.4 SD boundary, delta ≤ 0.20, rounded to 50pt.
 *                 Long strike: 50–100 pts further OTM.
 *
 * Debit spreads: Long strike at or near ATM, short strike 200 pts OTM (Bull Call Spread).
 */
@Component
public class StrikeSelector {

    private static final Logger log = LoggerFactory.getLogger(StrikeSelector.class);
    private static final BigDecimal MAX_SHORT_DELTA = new BigDecimal("0.20");

    // DTE threshold for spread width selection.
    // ≤ 4 calendar days → narrow spread (spread_width_min): less time for adverse moves,
    //                      tighter hedge is sufficient.
    //  > 4 calendar days → wide spread (spread_width_max): more DTE = more premium available,
    //                      wider spread collects more credit per lot.
    private static final int SHORT_DTE_THRESHOLD = 4;

    public void execute(RecommendationContext ctx) {
        switch (ctx.getStrategy()) {
            case BULL_PUT_SPREAD -> selectCreditPutSpread(ctx);
            case BEAR_CALL_SPREAD -> selectCreditCallSpread(ctx);
            case BULL_CALL_SPREAD -> selectDebitCallSpread(ctx);
            case IRON_CONDOR, WIDE_IRON_CONDOR -> selectIronCondor(ctx);
            default -> throw new IllegalStateException("StrikeSelector called for unsupported strategy: " + ctx.getStrategy());
        }
    }

    private void selectCreditPutSpread(RecommendationContext ctx) {
        BigDecimal boundary = ctx.getSpot().subtract(ctx.getOneFourSdBoundary());
        int targetShortStrike = roundToNearest50(boundary.intValue(), false); // round down — go more OTM

        List<StrikeData> puts = ctx.getOptionChainData().puts();

        StrikeData shortPut = puts.stream()
                .filter(s -> s.strike() <= targetShortStrike)
                .filter(s -> s.delta().abs().compareTo(MAX_SHORT_DELTA) <= 0)
                .max(Comparator.comparingInt(StrikeData::strike)) // highest strike that meets criteria
                .orElseThrow(() -> new MarketDataUnavailableException(
                        "No put strike found at or below " + targetShortStrike + " with delta ≤ 0.20"));

        int spreadWidth = selectSpreadWidth(ctx);
        int longStrike = roundToNearest50(shortPut.strike() - spreadWidth, false);

        StrikeData longPut = puts.stream()
                .filter(s -> s.strike() == longStrike)
                .findFirst()
                .orElseGet(() -> puts.stream()
                        .filter(s -> s.strike() < shortPut.strike())
                        .min(Comparator.comparingInt(s -> Math.abs(s.strike() - longStrike)))
                        .orElseThrow(() -> new MarketDataUnavailableException("No long put strike found below " + shortPut.strike())));

        ctx.setShortLeg(toLeg(shortPut, OptionType.PE, LegAction.SELL));
        ctx.setLongLeg(toLeg(longPut, OptionType.PE, LegAction.BUY));

        logStrikes(ctx, "bull_put_spread", shortPut, longPut, boundary);
    }

    private void selectCreditCallSpread(RecommendationContext ctx) {
        BigDecimal boundary = ctx.getSpot().add(ctx.getOneFourSdBoundary());
        int targetShortStrike = roundToNearest50(boundary.intValue(), true); // round up — go more OTM

        List<StrikeData> calls = ctx.getOptionChainData().calls();

        StrikeData shortCall = calls.stream()
                .filter(s -> s.strike() >= targetShortStrike)
                .filter(s -> s.delta().compareTo(MAX_SHORT_DELTA) <= 0)
                .min(Comparator.comparingInt(StrikeData::strike)) // lowest strike that meets criteria
                .orElseThrow(() -> new MarketDataUnavailableException(
                        "No call strike found at or above " + targetShortStrike + " with delta ≤ 0.20"));

        int spreadWidth = selectSpreadWidth(ctx);
        int longStrike = roundToNearest50(shortCall.strike() + spreadWidth, true);

        StrikeData longCall = calls.stream()
                .filter(s -> s.strike() == longStrike)
                .findFirst()
                .orElseGet(() -> calls.stream()
                        .filter(s -> s.strike() > shortCall.strike())
                        .min(Comparator.comparingInt(s -> Math.abs(s.strike() - longStrike)))
                        .orElseThrow(() -> new MarketDataUnavailableException("No long call strike found above " + shortCall.strike())));

        ctx.setShortLeg(toLeg(shortCall, OptionType.CE, LegAction.SELL));
        ctx.setLongLeg(toLeg(longCall, OptionType.CE, LegAction.BUY));

        logStrikes(ctx, "bear_call_spread", shortCall, longCall, boundary);
    }

    private void selectDebitCallSpread(RecommendationContext ctx) {
        int atmStrike = ctx.getOptionChainData().atmStrike();
        List<StrikeData> calls = ctx.getOptionChainData().calls();

        StrikeData longCall = calls.stream()
                .filter(s -> s.strike() == atmStrike)
                .findFirst()
                .orElseGet(() -> calls.stream()
                        .min(Comparator.comparingInt(s -> Math.abs(s.strike() - atmStrike)))
                        .orElseThrow(() -> new MarketDataUnavailableException("No ATM call strike found")));

        int shortStrike = roundToNearest50(atmStrike + 200, true);
        StrikeData shortCall = calls.stream()
                .filter(s -> s.strike() == shortStrike)
                .findFirst()
                .orElseGet(() -> calls.stream()
                        .filter(s -> s.strike() > longCall.strike())
                        .min(Comparator.comparingInt(s -> Math.abs(s.strike() - shortStrike)))
                        .orElseThrow(() -> new MarketDataUnavailableException("No OTM call strike found for debit spread")));

        ctx.setLongLeg(toLeg(longCall, OptionType.CE, LegAction.BUY));
        ctx.setShortLeg(toLeg(shortCall, OptionType.CE, LegAction.SELL));

        log.info("layer3.strikes.selected",
                kv("strategy", ctx.getStrategy()),
                kv("longStrike", longCall.strike()),
                kv("longLtp", longCall.ltp()),
                kv("shortStrike", shortCall.strike()),
                kv("shortLtp", shortCall.ltp()));
    }

    private void selectIronCondor(RecommendationContext ctx) {
        // Iron condor = put spread below + call spread above
        // Reuse credit spread logic for each side
        selectCreditPutSpread(ctx);
        TradeLegDto putShortLeg = ctx.getShortLeg();
        TradeLegDto putLongLeg = ctx.getLongLeg();

        selectCreditCallSpread(ctx);
        // For now stores call spread legs — Iron Condor full leg management handled in service
        log.info("layer3.iron.condor.selected",
                kv("strategy", ctx.getStrategy()),
                kv("putShortStrike", putShortLeg.strike()),
                kv("putLongStrike", putLongLeg.strike()),
                kv("callShortStrike", ctx.getShortLeg().strike()),
                kv("callLongStrike", ctx.getLongLeg().strike()));
    }

    private TradeLegDto toLeg(StrikeData strike, OptionType optionType, LegAction action) {
        return new TradeLegDto(optionType, strike.strike(), strike.ltp(), action,
                strike.delta(), strike.pop(), strike.instrumentKey());
    }

    /**
     * Selects spread width dynamically based on DTE.
     * Short DTE (≤ 4 calendar days): narrow spread — strikes are closer to expiry,
     *   less time for a large adverse move, hedge cost is proportionally higher.
     * Longer DTE (> 4 calendar days): wide spread — more time value available,
     *   wider spread collects more net premium per lot.
     */
    private int selectSpreadWidth(RecommendationContext ctx) {
        return ctx.getDte() <= SHORT_DTE_THRESHOLD
                ? ctx.getUserProfile().getSpreadWidthMin()
                : ctx.getUserProfile().getSpreadWidthMax();
    }

    private int roundToNearest50(int value, boolean roundUp) {
        int remainder = value % 50;
        if (remainder == 0) return value;
        return roundUp ? value + (50 - remainder) : value - remainder;
    }

    private void logStrikes(RecommendationContext ctx, String strategy,
                            StrikeData shortLeg, StrikeData longLeg, BigDecimal boundary) {
        log.info("layer3.strikes.selected",
                kv("strategy", strategy),
                kv("boundary", boundary),
                kv("shortStrike", shortLeg.strike()),
                kv("shortDelta", shortLeg.delta()),
                kv("shortLtp", shortLeg.ltp()),
                kv("longStrike", longLeg.strike()),
                kv("longLtp", longLeg.ltp()),
                kv("spreadWidth", Math.abs(shortLeg.strike() - longLeg.strike())));
    }
}
