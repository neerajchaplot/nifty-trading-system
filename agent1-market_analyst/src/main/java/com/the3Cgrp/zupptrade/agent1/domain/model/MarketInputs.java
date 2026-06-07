package com.the3Cgrp.zupptrade.agent1.domain.model;

import com.the3Cgrp.zupptrade.agent1.client.MarketauxClient.NseiSentiment;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * All inputs required for a single Agent1 scoring run.
 * Builder Pattern — assembled by ScoringPipeline from multiple data sources.
 * Null fields = data unavailable; scorers treat them as vote 0 (never throw).
 */
public class MarketInputs {

    // --- Price structure ---
    private final BigDecimal spot;
    private final BigDecimal futuresPremium;   // Nifty futures price − spot; null if unavailable
    private final BigDecimal pcr;              // put-call ratio from Upstox option chain
    private final Integer maxPain;             // max pain strike from option chain OI

    // --- Volatility ---
    private final BigDecimal vixLevel;
    private final BigDecimal vixPrevLevel;     // previous session VIX for % change
    private final VixRegime vixRegime;

    // --- Institutional flow ---
    private final BigDecimal fiiNetFutures;    // crore; null if unavailable
    private final BigDecimal fiiLongRatio;     // 0–1; null if unavailable
    private final BigDecimal fiiNetOptions;    // crore; null if unavailable
    private final BigDecimal diiNet;           // crore; null if unavailable
    private final FiiDiiTrend fiiTrend;        // 5-day FII futures trend; null on first run

    // --- Option OI changes ---
    private final BigDecimal callOiChange;     // total call OI change; null if unavailable
    private final BigDecimal putOiChange;      // total put OI change; null if unavailable

    // --- Macro ---
    private final BigDecimal giftNiftyPremium; // pts vs previous close; null if unavailable (score = 0 per Q4 stub)

    // --- Sentiment ---
    private final BigDecimal marketauxSentiment; // avg entity sentiment score — used by Tier 4 scorer
    private final NseiSentiment marketauxDetails; // full article list — persisted in raw_inputs for user review/override
    private final String commentaryBias;          // "BULLISH" / "BEARISH" / "NEUTRAL"; null = not provided
    private final CommentarySignal commentarySignal; // full LLM output — support/resistance/keyInsight for key_levels JSONB

    // --- Pre-computed TA4J indicators ---
    private final PrecomputedIndicators indicators;

    private final LocalDate expiryDate;

    private MarketInputs(Builder b) {
        this.spot = b.spot;
        this.futuresPremium = b.futuresPremium;
        this.pcr = b.pcr;
        this.maxPain = b.maxPain;
        this.vixLevel = b.vixLevel;
        this.vixPrevLevel = b.vixPrevLevel;
        this.vixRegime = b.vixRegime;
        this.fiiNetFutures = b.fiiNetFutures;
        this.fiiLongRatio = b.fiiLongRatio;
        this.fiiNetOptions = b.fiiNetOptions;
        this.diiNet = b.diiNet;
        this.fiiTrend = b.fiiTrend;
        this.callOiChange = b.callOiChange;
        this.putOiChange = b.putOiChange;
        this.giftNiftyPremium = b.giftNiftyPremium;
        this.marketauxSentiment = b.marketauxSentiment;
        this.marketauxDetails = b.marketauxDetails;
        this.commentaryBias = b.commentaryBias;
        this.commentarySignal = b.commentarySignal;
        this.indicators = b.indicators != null ? b.indicators : PrecomputedIndicators.empty();
        this.expiryDate = b.expiryDate;
    }

    public static Builder builder() { return new Builder(); }

    public BigDecimal getSpot() { return spot; }
    public BigDecimal getFuturesPremium() { return futuresPremium; }
    public BigDecimal getPcr() { return pcr; }
    public Integer getMaxPain() { return maxPain; }
    public BigDecimal getVixLevel() { return vixLevel; }
    public BigDecimal getVixPrevLevel() { return vixPrevLevel; }
    public VixRegime getVixRegime() { return vixRegime; }
    public BigDecimal getFiiNetFutures() { return fiiNetFutures; }
    public BigDecimal getFiiLongRatio() { return fiiLongRatio; }
    public BigDecimal getFiiNetOptions() { return fiiNetOptions; }
    public BigDecimal getDiiNet() { return diiNet; }
    public FiiDiiTrend getFiiTrend() { return fiiTrend; }
    public BigDecimal getCallOiChange() { return callOiChange; }
    public BigDecimal getPutOiChange() { return putOiChange; }
    public BigDecimal getGiftNiftyPremium() { return giftNiftyPremium; }
    public BigDecimal getMarketauxSentiment() { return marketauxSentiment; }
    public NseiSentiment getMarketauxDetails()  { return marketauxDetails; }
    public String getCommentaryBias() { return commentaryBias; }
    public CommentarySignal getCommentarySignal() { return commentarySignal; }
    public PrecomputedIndicators getIndicators() { return indicators; }
    public LocalDate getExpiryDate() { return expiryDate; }

    public static class Builder {
        private BigDecimal spot;
        private BigDecimal futuresPremium;
        private BigDecimal pcr;
        private Integer maxPain;
        private BigDecimal vixLevel;
        private BigDecimal vixPrevLevel;
        private VixRegime vixRegime;
        private BigDecimal fiiNetFutures;
        private BigDecimal fiiLongRatio;
        private BigDecimal fiiNetOptions;
        private BigDecimal diiNet;
        private FiiDiiTrend fiiTrend;
        private BigDecimal callOiChange;
        private BigDecimal putOiChange;
        private BigDecimal giftNiftyPremium;
        private BigDecimal marketauxSentiment;
        private NseiSentiment marketauxDetails;
        private String commentaryBias;
        private CommentarySignal commentarySignal;
        private PrecomputedIndicators indicators;
        private LocalDate expiryDate;

        public Builder spot(BigDecimal v) { this.spot = v; return this; }
        public Builder futuresPremium(BigDecimal v) { this.futuresPremium = v; return this; }
        public Builder pcr(BigDecimal v) { this.pcr = v; return this; }
        public Builder maxPain(Integer v) { this.maxPain = v; return this; }
        public Builder vixLevel(BigDecimal v) { this.vixLevel = v; return this; }
        public Builder vixPrevLevel(BigDecimal v) { this.vixPrevLevel = v; return this; }
        public Builder vixRegime(VixRegime v) { this.vixRegime = v; return this; }
        public Builder fiiNetFutures(BigDecimal v) { this.fiiNetFutures = v; return this; }
        public Builder fiiLongRatio(BigDecimal v) { this.fiiLongRatio = v; return this; }
        public Builder fiiNetOptions(BigDecimal v) { this.fiiNetOptions = v; return this; }
        public Builder diiNet(BigDecimal v) { this.diiNet = v; return this; }
        public Builder fiiTrend(FiiDiiTrend v) { this.fiiTrend = v; return this; }
        public Builder callOiChange(BigDecimal v) { this.callOiChange = v; return this; }
        public Builder putOiChange(BigDecimal v) { this.putOiChange = v; return this; }
        public Builder giftNiftyPremium(BigDecimal v) { this.giftNiftyPremium = v; return this; }
        public Builder marketauxSentiment(BigDecimal v) { this.marketauxSentiment = v; return this; }
        public Builder marketauxDetails(NseiSentiment v)  { this.marketauxDetails = v; return this; }
        public Builder commentaryBias(String v) { this.commentaryBias = v; return this; }
        public Builder commentarySignal(CommentarySignal v) { this.commentarySignal = v; return this; }
        public Builder indicators(PrecomputedIndicators v) { this.indicators = v; return this; }
        public Builder expiryDate(LocalDate v) { this.expiryDate = v; return this; }

        public MarketInputs build() { return new MarketInputs(this); }
    }
}
