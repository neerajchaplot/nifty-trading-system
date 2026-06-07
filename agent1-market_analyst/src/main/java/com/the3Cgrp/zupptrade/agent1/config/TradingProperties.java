package com.the3Cgrp.zupptrade.agent1.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * All scoring thresholds bound from application.yml nifty.trading.* — never hardcoded.
 */
@ConfigurationProperties(prefix = "nifty.trading")
public class TradingProperties {

    private Vix vix = new Vix();
    private Fii fii = new Fii();
    private Pcr pcr = new Pcr();
    private GiftNifty giftNifty = new GiftNifty();
    private Scoring scoring = new Scoring();
    private Confidence confidence = new Confidence();
    private VixModifier vixModifier = new VixModifier();
    private AdxModifier adxModifier = new AdxModifier();

    public static class Vix {
        private BigDecimal lowThreshold = new BigDecimal("13.0");
        private BigDecimal normalHigh = new BigDecimal("18.0");
        private BigDecimal highExtreme = new BigDecimal("24.0");
        private BigDecimal dailyChangeBullish = new BigDecimal("-2.0");   // % change, bullish if change < this
        private BigDecimal dailyChangeBearish = new BigDecimal("10.0");   // % change, bearish if change > this

        public BigDecimal getLowThreshold() { return lowThreshold; }
        public void setLowThreshold(BigDecimal v) { this.lowThreshold = v; }
        public BigDecimal getNormalHigh() { return normalHigh; }
        public void setNormalHigh(BigDecimal v) { this.normalHigh = v; }
        public BigDecimal getHighExtreme() { return highExtreme; }
        public void setHighExtreme(BigDecimal v) { this.highExtreme = v; }
        public BigDecimal getDailyChangeBullish() { return dailyChangeBullish; }
        public void setDailyChangeBullish(BigDecimal v) { this.dailyChangeBullish = v; }
        public BigDecimal getDailyChangeBearish() { return dailyChangeBearish; }
        public void setDailyChangeBearish(BigDecimal v) { this.dailyChangeBearish = v; }
    }

    public static class Fii {
        private BigDecimal significantFlowCrore = new BigDecimal("500");
        private BigDecimal longRatioBullish = new BigDecimal("0.60");
        private BigDecimal longRatioBearish = new BigDecimal("0.40");

        public BigDecimal getSignificantFlowCrore() { return significantFlowCrore; }
        public void setSignificantFlowCrore(BigDecimal v) { this.significantFlowCrore = v; }
        public BigDecimal getLongRatioBullish() { return longRatioBullish; }
        public void setLongRatioBullish(BigDecimal v) { this.longRatioBullish = v; }
        public BigDecimal getLongRatioBearish() { return longRatioBearish; }
        public void setLongRatioBearish(BigDecimal v) { this.longRatioBearish = v; }
    }

    public static class Pcr {
        private BigDecimal bullishAbove = new BigDecimal("1.20");
        private BigDecimal bearishBelow = new BigDecimal("0.80");

        public BigDecimal getBullishAbove() { return bullishAbove; }
        public void setBullishAbove(BigDecimal v) { this.bullishAbove = v; }
        public BigDecimal getBearishBelow() { return bearishBelow; }
        public void setBearishBelow(BigDecimal v) { this.bearishBelow = v; }
    }

    public static class GiftNifty {
        private BigDecimal significantPts = new BigDecimal("50");

        public BigDecimal getSignificantPts() { return significantPts; }
        public void setSignificantPts(BigDecimal v) { this.significantPts = v; }
    }

    public static class Scoring {
        private BigDecimal tier1aWeight = new BigDecimal("0.30");
        private BigDecimal tier1bWeight = new BigDecimal("0.20");
        private BigDecimal tier2Weight  = new BigDecimal("0.30");
        private BigDecimal tier3Weight  = new BigDecimal("0.10");
        private BigDecimal tier4Weight  = new BigDecimal("0.10");
        private BigDecimal futuresPremiumThreshold = new BigDecimal("20");
        private BigDecimal maxPainBand = new BigDecimal("100");
        private BigDecimal marketauxBullish = new BigDecimal("0.30");
        private BigDecimal marketauxBearish = new BigDecimal("-0.30");
        private BigDecimal rsiOverbought = new BigDecimal("60");
        private BigDecimal rsiOversold   = new BigDecimal("40");
        private BigDecimal diiSignificantCrore = new BigDecimal("500");

        public BigDecimal getTier1aWeight() { return tier1aWeight; }
        public void setTier1aWeight(BigDecimal v) { this.tier1aWeight = v; }
        public BigDecimal getTier1bWeight() { return tier1bWeight; }
        public void setTier1bWeight(BigDecimal v) { this.tier1bWeight = v; }
        public BigDecimal getTier2Weight() { return tier2Weight; }
        public void setTier2Weight(BigDecimal v) { this.tier2Weight = v; }
        public BigDecimal getTier3Weight() { return tier3Weight; }
        public void setTier3Weight(BigDecimal v) { this.tier3Weight = v; }
        public BigDecimal getTier4Weight() { return tier4Weight; }
        public void setTier4Weight(BigDecimal v) { this.tier4Weight = v; }
        public BigDecimal getFuturesPremiumThreshold() { return futuresPremiumThreshold; }
        public void setFuturesPremiumThreshold(BigDecimal v) { this.futuresPremiumThreshold = v; }
        public BigDecimal getMaxPainBand() { return maxPainBand; }
        public void setMaxPainBand(BigDecimal v) { this.maxPainBand = v; }
        public BigDecimal getMarketauxBullish() { return marketauxBullish; }
        public void setMarketauxBullish(BigDecimal v) { this.marketauxBullish = v; }
        public BigDecimal getMarketauxBearish() { return marketauxBearish; }
        public void setMarketauxBearish(BigDecimal v) { this.marketauxBearish = v; }
        public BigDecimal getRsiOverbought() { return rsiOverbought; }
        public void setRsiOverbought(BigDecimal v) { this.rsiOverbought = v; }
        public BigDecimal getRsiOversold() { return rsiOversold; }
        public void setRsiOversold(BigDecimal v) { this.rsiOversold = v; }
        public BigDecimal getDiiSignificantCrore() { return diiSignificantCrore; }
        public void setDiiSignificantCrore(BigDecimal v) { this.diiSignificantCrore = v; }
    }

    public static class Confidence {
        private BigDecimal lowBelow    = new BigDecimal("0.41");
        private BigDecimal highAbove   = new BigDecimal("0.70");
        private BigDecimal divergencePenalty = new BigDecimal("0.80");

        public BigDecimal getLowBelow() { return lowBelow; }
        public void setLowBelow(BigDecimal v) { this.lowBelow = v; }
        public BigDecimal getHighAbove() { return highAbove; }
        public void setHighAbove(BigDecimal v) { this.highAbove = v; }
        public BigDecimal getDivergencePenalty() { return divergencePenalty; }
        public void setDivergencePenalty(BigDecimal v) { this.divergencePenalty = v; }
    }

    public static class VixModifier {
        private BigDecimal low    = new BigDecimal("1.10");
        private BigDecimal normal = new BigDecimal("1.00");
        private BigDecimal high   = new BigDecimal("0.85");
        private BigDecimal extreme = new BigDecimal("0.60");

        public BigDecimal getLow() { return low; }
        public void setLow(BigDecimal v) { this.low = v; }
        public BigDecimal getNormal() { return normal; }
        public void setNormal(BigDecimal v) { this.normal = v; }
        public BigDecimal getHigh() { return high; }
        public void setHigh(BigDecimal v) { this.high = v; }
        public BigDecimal getExtreme() { return extreme; }
        public void setExtreme(BigDecimal v) { this.extreme = v; }
    }

    public static class AdxModifier {
        private BigDecimal strongTrendAbove    = new BigDecimal("30");
        private BigDecimal moderateTrendAbove  = new BigDecimal("20");
        private BigDecimal strongModifier      = new BigDecimal("1.15");
        private BigDecimal moderateModifier    = new BigDecimal("1.00");
        private BigDecimal weakModifier        = new BigDecimal("0.80");

        public BigDecimal getStrongTrendAbove() { return strongTrendAbove; }
        public void setStrongTrendAbove(BigDecimal v) { this.strongTrendAbove = v; }
        public BigDecimal getModerateTrendAbove() { return moderateTrendAbove; }
        public void setModerateTrendAbove(BigDecimal v) { this.moderateTrendAbove = v; }
        public BigDecimal getStrongModifier() { return strongModifier; }
        public void setStrongModifier(BigDecimal v) { this.strongModifier = v; }
        public BigDecimal getModerateModifier() { return moderateModifier; }
        public void setModerateModifier(BigDecimal v) { this.moderateModifier = v; }
        public BigDecimal getWeakModifier() { return weakModifier; }
        public void setWeakModifier(BigDecimal v) { this.weakModifier = v; }
    }

    public Vix getVix() { return vix; }
    public void setVix(Vix v) { this.vix = v; }
    public Fii getFii() { return fii; }
    public void setFii(Fii v) { this.fii = v; }
    public Pcr getPcr() { return pcr; }
    public void setPcr(Pcr v) { this.pcr = v; }
    public GiftNifty getGiftNifty() { return giftNifty; }
    public void setGiftNifty(GiftNifty v) { this.giftNifty = v; }
    public Scoring getScoring() { return scoring; }
    public void setScoring(Scoring v) { this.scoring = v; }
    public Confidence getConfidence() { return confidence; }
    public void setConfidence(Confidence v) { this.confidence = v; }
    public VixModifier getVixModifier() { return vixModifier; }
    public void setVixModifier(VixModifier v) { this.vixModifier = v; }
    public AdxModifier getAdxModifier() { return adxModifier; }
    public void setAdxModifier(AdxModifier v) { this.adxModifier = v; }
}
