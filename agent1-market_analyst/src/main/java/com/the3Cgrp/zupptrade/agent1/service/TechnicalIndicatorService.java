package com.the3Cgrp.zupptrade.agent1.service;

import com.the3Cgrp.zupptrade.agent1.domain.model.OhlcCandle;
import com.the3Cgrp.zupptrade.agent1.domain.model.PrecomputedIndicators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.candles.BullishEngulfingIndicator;
import org.ta4j.core.indicators.candles.BearishEngulfingIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Computes all TA4J indicators from raw OHLC candles.
 * Returns PrecomputedIndicators — nulls for any indicator needing more bars than available.
 * TA4J uses double internally; we bridge via doubleValue() then wrap in BigDecimal.
 */
@Service
public class TechnicalIndicatorService {

    private static final Logger log = LoggerFactory.getLogger(TechnicalIndicatorService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public PrecomputedIndicators compute(List<OhlcCandle> candles) {
        if (candles == null || candles.isEmpty()) {
            return PrecomputedIndicators.empty();
        }

        BarSeries series = buildSeries(candles);
        int endIdx = series.getEndIndex();
        int barCount = series.getBarCount();

        ClosePriceIndicator close = new ClosePriceIndicator(series);

        BigDecimal ema20   = barCount >= 20  ? toBD(new EMAIndicator(close, 20).getValue(endIdx))  : null;
        BigDecimal ema50   = barCount >= 50  ? toBD(new EMAIndicator(close, 50).getValue(endIdx))  : null;
        BigDecimal ema200  = barCount >= 200 ? toBD(new EMAIndicator(close, 200).getValue(endIdx)) : null;
        BigDecimal rsi14   = barCount >= 14  ? toBD(new RSIIndicator(close, 14).getValue(endIdx))  : null;

        BigDecimal macdLine = null, macdSignal = null;
        if (barCount >= 35) {
            MACDIndicator macd = new MACDIndicator(close, 12, 26);
            EMAIndicator signal = new EMAIndicator(macd, 9);
            macdLine   = toBD(macd.getValue(endIdx));
            macdSignal = toBD(signal.getValue(endIdx));
        }

        BigDecimal adx = barCount >= 28 ? toBD(new ADXIndicator(series, 14).getValue(endIdx)) : null;

        boolean bullish = false, bearish = false;
        if (barCount >= 2) {
            bullish = new BullishEngulfingIndicator(series).getValue(endIdx);
            bearish = new BearishEngulfingIndicator(series).getValue(endIdx);
        }

        Boolean higherHighs = null, higherLows = null;
        if (barCount >= 3) {
            higherHighs = isHigherHighs(series, endIdx);
            higherLows  = isHigherLows(series, endIdx);
        }

        log.debug("indicators.computed",
                "barCount={}, ema20={}, ema50={}, rsi14={}, adx={}", barCount, ema20, ema50, rsi14, adx);

        return new PrecomputedIndicators(ema20, ema50, ema200, rsi14, macdLine, macdSignal,
                adx, bullish, bearish, higherHighs, higherLows);
    }

    private BarSeries buildSeries(List<OhlcCandle> candles) {
        // TA4J requires bars in strict ascending chronological order.
        // Upstox returns candles newest-first — sort a local copy (do NOT mutate the caller's list;
        // ScoringPipeline.candles.get(0) intentionally accesses the most-recent candle).
        List<OhlcCandle> ascending = candles.stream()
                .sorted(Comparator.comparing(OhlcCandle::date))
                .toList();

        BarSeries series = new BaseBarSeriesBuilder().withName("NIFTY_DAILY").build();
        for (OhlcCandle c : ascending) {
            ZonedDateTime endTime = c.date().atTime(15, 30).atZone(IST);
            series.addBar(endTime,
                    c.open().doubleValue(),
                    c.high().doubleValue(),
                    c.low().doubleValue(),
                    c.close().doubleValue(),
                    (double) c.volume());
        }
        return series;
    }

    private boolean isHigherHighs(BarSeries series, int endIdx) {
        return series.getBar(endIdx).getHighPrice().isGreaterThan(series.getBar(endIdx - 1).getHighPrice())
                && series.getBar(endIdx - 1).getHighPrice().isGreaterThan(series.getBar(endIdx - 2).getHighPrice());
    }

    private boolean isHigherLows(BarSeries series, int endIdx) {
        return series.getBar(endIdx).getLowPrice().isGreaterThan(series.getBar(endIdx - 1).getLowPrice())
                && series.getBar(endIdx - 1).getLowPrice().isGreaterThan(series.getBar(endIdx - 2).getLowPrice());
    }

    private BigDecimal toBD(org.ta4j.core.num.Num num) {
        if (num == null || num.isNaN()) return null;
        return BigDecimal.valueOf(num.doubleValue());
    }
}
