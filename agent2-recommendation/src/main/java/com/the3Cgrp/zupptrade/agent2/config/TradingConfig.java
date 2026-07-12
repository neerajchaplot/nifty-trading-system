package com.the3Cgrp.zupptrade.agent2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "trading")
public class TradingConfig {

    private BigDecimal vixLowThreshold = new BigDecimal("13");
    private BigDecimal vixNormalThreshold = new BigDecimal("18");
    private BigDecimal vixHighThreshold = new BigDecimal("24");
    private BigDecimal ivHvRichThreshold = new BigDecimal("1.05");
    private BigDecimal ivHvCheapThreshold = new BigDecimal("0.85");
    private BigDecimal riskFreeRate = new BigDecimal("0.065");
    private BigDecimal minPopSellSpread = new BigDecimal("80");
    private BigDecimal minPopDebitSpread = new BigDecimal("35");
    private BigDecimal maxPopPoppGap = new BigDecimal("15");
    private BigDecimal minRocBasePct = new BigDecimal("0.5");
    private BigDecimal slippageAlertThreshold = new BigDecimal("0.10");
    private int tradeCardValidMinutes = 20;
    private int debitSpreadWidth = 200;               // pts — debit spread width at low VIX
    private BigDecimal maxLossDebitPct = new BigDecimal("0.5");  // 0.5% of capital max loss for debit
    private BigDecimal maxDebitBreakevenDistancePts = new BigDecimal("100"); // max pts from spot to breakeven
    private BigDecimal minDebitRr = new BigDecimal("1.4"); // min max-profit:net-debit ratio (G1D)
    private Upstox upstox = new Upstox();

    public static class Upstox {
        private String baseUrl = "https://api.upstox.com";
        private String accessToken;         // fallback if DB token loading is skipped
        private String tokenEncryptionKey;  // AES-256 key — set via TOKEN_ENCRYPTION_KEY env var
        private String niftyInstrumentKey = "NSE_INDEX|Nifty 50";
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 15;
        private int retryMax = 3;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getTokenEncryptionKey() { return tokenEncryptionKey; }
        public void setTokenEncryptionKey(String tokenEncryptionKey) { this.tokenEncryptionKey = tokenEncryptionKey; }
        public String getNiftyInstrumentKey() { return niftyInstrumentKey; }
        public void setNiftyInstrumentKey(String niftyInstrumentKey) { this.niftyInstrumentKey = niftyInstrumentKey; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
        public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }
        public int getRetryMax() { return retryMax; }
        public void setRetryMax(int retryMax) { this.retryMax = retryMax; }
    }

    public BigDecimal getVixLowThreshold() { return vixLowThreshold; }
    public void setVixLowThreshold(BigDecimal v) { this.vixLowThreshold = v; }
    public BigDecimal getVixNormalThreshold() { return vixNormalThreshold; }
    public void setVixNormalThreshold(BigDecimal v) { this.vixNormalThreshold = v; }
    public BigDecimal getVixHighThreshold() { return vixHighThreshold; }
    public void setVixHighThreshold(BigDecimal v) { this.vixHighThreshold = v; }
    public BigDecimal getIvHvRichThreshold() { return ivHvRichThreshold; }
    public void setIvHvRichThreshold(BigDecimal v) { this.ivHvRichThreshold = v; }
    public BigDecimal getIvHvCheapThreshold() { return ivHvCheapThreshold; }
    public void setIvHvCheapThreshold(BigDecimal v) { this.ivHvCheapThreshold = v; }
    public BigDecimal getRiskFreeRate() { return riskFreeRate; }
    public void setRiskFreeRate(BigDecimal v) { this.riskFreeRate = v; }
    public BigDecimal getMinPopSellSpread() { return minPopSellSpread; }
    public void setMinPopSellSpread(BigDecimal v) { this.minPopSellSpread = v; }
    public BigDecimal getMinPopDebitSpread() { return minPopDebitSpread; }
    public void setMinPopDebitSpread(BigDecimal v) { this.minPopDebitSpread = v; }
    public BigDecimal getMaxPopPoppGap() { return maxPopPoppGap; }
    public void setMaxPopPoppGap(BigDecimal v) { this.maxPopPoppGap = v; }
    public BigDecimal getMinRocBasePct() { return minRocBasePct; }
    public void setMinRocBasePct(BigDecimal v) { this.minRocBasePct = v; }
    public BigDecimal getSlippageAlertThreshold() { return slippageAlertThreshold; }
    public void setSlippageAlertThreshold(BigDecimal v) { this.slippageAlertThreshold = v; }
    public int getTradeCardValidMinutes() { return tradeCardValidMinutes; }
    public void setTradeCardValidMinutes(int v) { this.tradeCardValidMinutes = v; }
    public int getDebitSpreadWidth() { return debitSpreadWidth; }
    public void setDebitSpreadWidth(int v) { this.debitSpreadWidth = v; }
    public BigDecimal getMaxLossDebitPct() { return maxLossDebitPct; }
    public void setMaxLossDebitPct(BigDecimal v) { this.maxLossDebitPct = v; }
    public BigDecimal getMaxDebitBreakevenDistancePts() { return maxDebitBreakevenDistancePts; }
    public void setMaxDebitBreakevenDistancePts(BigDecimal v) { this.maxDebitBreakevenDistancePts = v; }
    public BigDecimal getMinDebitRr() { return minDebitRr; }
    public void setMinDebitRr(BigDecimal v) { this.minDebitRr = v; }
    public Upstox getUpstox() { return upstox; }
    public void setUpstox(Upstox upstox) { this.upstox = upstox; }
}
