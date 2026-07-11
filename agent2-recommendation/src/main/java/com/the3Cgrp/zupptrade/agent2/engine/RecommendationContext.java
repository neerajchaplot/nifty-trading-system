package com.the3Cgrp.zupptrade.agent2.engine;

import com.the3Cgrp.zupptrade.agent2.client.model.OptionChainData;
import com.the3Cgrp.zupptrade.agent2.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.shared.dto.GateResultDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.IvRegime;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable context object that flows through all 5 engine layers.
 * Each layer reads its inputs and writes its outputs into this object.
 */
public class RecommendationContext {

    // ── Inputs ──────────────────────────────────────────────────────────────
    private Agent1SignalEntity signal;
    private UserProfileEntity userProfile;
    private int lotSize;
    private BigDecimal spot;
    private BigDecimal vix;
    private BigDecimal historicalVolatility;
    private LocalDate expiryDate;
    private int dte;
    private OptionChainData optionChainData;

    // ── Pre-Layer 1: User weight recomposition ──────────────────────────────
    // Set when the user has custom Agent 1 tier weights.
    // StrategySelector uses these in preference to signal.getBias()/getStrength().
    private com.the3Cgrp.zupptrade.shared.enums.Bias effectiveBias;
    private com.the3Cgrp.zupptrade.shared.enums.Strength effectiveStrength;
    private String weightsSource; // "USER_OVERRIDE" | "SYSTEM_DEFAULT"

    // ── Layer 1: Strategy Selection ─────────────────────────────────────────
    private Strategy strategy;
    private SpreadDirection spreadDirection;
    private IvRegime ivRegime;

    // ── Layer 2: Expected Move ──────────────────────────────────────────────
    private BigDecimal expectedMove;        // 1 SD
    private BigDecimal oneFourSdBoundary;   // 1.4 SD — used for short strike target
    private BigDecimal marketExpectedMove;  // ATM straddle cross-check

    // ── Layer 3: Strike Selection ───────────────────────────────────────────
    private TradeLegDto shortLeg;
    private TradeLegDto longLeg;
    // Iron Condor only: CE spread (shortLeg/longLeg hold the PE spread)
    private TradeLegDto shortLeg2;
    private TradeLegDto longLeg2;

    // ── Layer 4: Gate Validation ────────────────────────────────────────────
    private List<GateResultDto> gateResults = new ArrayList<>();
    private boolean allHardGatesPassed;
    private BigDecimal relaxedGate1PopPct;  // null = standard threshold; set for readjustment re-entry

    // ── Layer 5: Position Sizing ────────────────────────────────────────────
    private int lots;
    private BigDecimal maxProfitTotal;
    private BigDecimal theoreticalMaxLossTotal;
    private BigDecimal realExpectedLossTotal;
    private BigDecimal roc;
    private BigDecimal rocAnnualised;
    private BigDecimal netDelta;

    // ── Getters / Setters ───────────────────────────────────────────────────

    public com.the3Cgrp.zupptrade.shared.enums.Bias getEffectiveBias() { return effectiveBias; }
    public void setEffectiveBias(com.the3Cgrp.zupptrade.shared.enums.Bias v) { this.effectiveBias = v; }
    public com.the3Cgrp.zupptrade.shared.enums.Strength getEffectiveStrength() { return effectiveStrength; }
    public void setEffectiveStrength(com.the3Cgrp.zupptrade.shared.enums.Strength v) { this.effectiveStrength = v; }
    public String getWeightsSource() { return weightsSource; }
    public void setWeightsSource(String v) { this.weightsSource = v; }

    public Agent1SignalEntity getSignal() { return signal; }
    public void setSignal(Agent1SignalEntity signal) { this.signal = signal; }
    public UserProfileEntity getUserProfile() { return userProfile; }
    public void setUserProfile(UserProfileEntity userProfile) { this.userProfile = userProfile; }
    public int getLotSize() { return lotSize; }
    public void setLotSize(int lotSize) { this.lotSize = lotSize; }
    public BigDecimal getSpot() { return spot; }
    public void setSpot(BigDecimal spot) { this.spot = spot; }
    public BigDecimal getVix() { return vix; }
    public void setVix(BigDecimal vix) { this.vix = vix; }
    public BigDecimal getHistoricalVolatility() { return historicalVolatility; }
    public void setHistoricalVolatility(BigDecimal historicalVolatility) { this.historicalVolatility = historicalVolatility; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public int getDte() { return dte; }
    public void setDte(int dte) { this.dte = dte; }
    public OptionChainData getOptionChainData() { return optionChainData; }
    public void setOptionChainData(OptionChainData optionChainData) { this.optionChainData = optionChainData; }
    public Strategy getStrategy() { return strategy; }
    public void setStrategy(Strategy strategy) { this.strategy = strategy; }
    public SpreadDirection getSpreadDirection() { return spreadDirection; }
    public void setSpreadDirection(SpreadDirection spreadDirection) { this.spreadDirection = spreadDirection; }
    public IvRegime getIvRegime() { return ivRegime; }
    public void setIvRegime(IvRegime ivRegime) { this.ivRegime = ivRegime; }
    public BigDecimal getExpectedMove() { return expectedMove; }
    public void setExpectedMove(BigDecimal expectedMove) { this.expectedMove = expectedMove; }
    public BigDecimal getOneFourSdBoundary() { return oneFourSdBoundary; }
    public void setOneFourSdBoundary(BigDecimal oneFourSdBoundary) { this.oneFourSdBoundary = oneFourSdBoundary; }
    public BigDecimal getMarketExpectedMove() { return marketExpectedMove; }
    public void setMarketExpectedMove(BigDecimal marketExpectedMove) { this.marketExpectedMove = marketExpectedMove; }
    public TradeLegDto getShortLeg() { return shortLeg; }
    public void setShortLeg(TradeLegDto shortLeg) { this.shortLeg = shortLeg; }
    public TradeLegDto getLongLeg() { return longLeg; }
    public void setLongLeg(TradeLegDto longLeg) { this.longLeg = longLeg; }
    public TradeLegDto getShortLeg2() { return shortLeg2; }
    public void setShortLeg2(TradeLegDto shortLeg2) { this.shortLeg2 = shortLeg2; }
    public TradeLegDto getLongLeg2() { return longLeg2; }
    public void setLongLeg2(TradeLegDto longLeg2) { this.longLeg2 = longLeg2; }
    public List<GateResultDto> getGateResults() { return gateResults; }
    public void setGateResults(List<GateResultDto> gateResults) { this.gateResults = gateResults; }
    public boolean isAllHardGatesPassed() { return allHardGatesPassed; }
    public void setAllHardGatesPassed(boolean allHardGatesPassed) { this.allHardGatesPassed = allHardGatesPassed; }
    public BigDecimal getRelaxedGate1PopPct() { return relaxedGate1PopPct; }
    public void setRelaxedGate1PopPct(BigDecimal relaxedGate1PopPct) { this.relaxedGate1PopPct = relaxedGate1PopPct; }
    public int getLots() { return lots; }
    public void setLots(int lots) { this.lots = lots; }
    public BigDecimal getMaxProfitTotal() { return maxProfitTotal; }
    public void setMaxProfitTotal(BigDecimal maxProfitTotal) { this.maxProfitTotal = maxProfitTotal; }
    public BigDecimal getTheoreticalMaxLossTotal() { return theoreticalMaxLossTotal; }
    public void setTheoreticalMaxLossTotal(BigDecimal theoreticalMaxLossTotal) { this.theoreticalMaxLossTotal = theoreticalMaxLossTotal; }
    public BigDecimal getRealExpectedLossTotal() { return realExpectedLossTotal; }
    public void setRealExpectedLossTotal(BigDecimal realExpectedLossTotal) { this.realExpectedLossTotal = realExpectedLossTotal; }
    public BigDecimal getRoc() { return roc; }
    public void setRoc(BigDecimal roc) { this.roc = roc; }
    public BigDecimal getRocAnnualised() { return rocAnnualised; }
    public void setRocAnnualised(BigDecimal rocAnnualised) { this.rocAnnualised = rocAnnualised; }
    public BigDecimal getNetDelta() { return netDelta; }
    public void setNetDelta(BigDecimal netDelta) { this.netDelta = netDelta; }
}
