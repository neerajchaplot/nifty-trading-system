package com.the3Cgrp.zupptrade.agent2.service;

import tools.jackson.core.type.TypeReference;
import com.the3Cgrp.zupptrade.agent2.client.MarketDataClient;
import com.the3Cgrp.zupptrade.agent2.client.OptionChainClient;
import com.the3Cgrp.zupptrade.agent2.client.model.MarketSnapshot;
import com.the3Cgrp.zupptrade.agent2.client.model.OptionChainData;
import com.the3Cgrp.zupptrade.agent2.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.ReferenceDataEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.TradeEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent2.domain.model.MarketContext;
import com.the3Cgrp.zupptrade.agent2.domain.model.TradeSummary;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationEngine;
import com.the3Cgrp.zupptrade.agent2.exception.TradeNotFoundException;
import com.the3Cgrp.zupptrade.agent2.repository.Agent1SignalRepository;
import com.the3Cgrp.zupptrade.agent2.repository.ReferenceDataRepository;
import com.the3Cgrp.zupptrade.agent2.repository.TradeRepository;
import com.the3Cgrp.zupptrade.agent2.repository.UserProfileRepository;
import com.the3Cgrp.zupptrade.agent2.util.JsonUtil;
import com.the3Cgrp.zupptrade.shared.dto.*;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private static final String LOT_SIZE_KEY = "nifty.lot.size";

    private final Agent1SignalRepository signalRepository;
    private final UserProfileRepository userProfileRepository;
    private final TradeRepository tradeRepository;
    private final ReferenceDataRepository referenceDataRepository;
    private final OptionChainClient optionChainClient;
    private final MarketDataClient marketDataClient;
    private final RecommendationEngine engine;
    private final VolatilityService volatilityService;
    private final JsonUtil jsonUtil;

    public RecommendationService(Agent1SignalRepository signalRepository,
                                  UserProfileRepository userProfileRepository,
                                  TradeRepository tradeRepository,
                                  ReferenceDataRepository referenceDataRepository,
                                  OptionChainClient optionChainClient,
                                  MarketDataClient marketDataClient,
                                  RecommendationEngine engine,
                                  VolatilityService volatilityService,
                                  JsonUtil jsonUtil) {
        this.signalRepository = signalRepository;
        this.userProfileRepository = userProfileRepository;
        this.tradeRepository = tradeRepository;
        this.referenceDataRepository = referenceDataRepository;
        this.optionChainClient = optionChainClient;
        this.marketDataClient = marketDataClient;
        this.engine = engine;
        this.volatilityService = volatilityService;
        this.jsonUtil = jsonUtil;
    }

    @Transactional
    public TradeCardDto recommend(RecommendRequestDto request) {
        Agent1SignalEntity signal = signalRepository.findById(request.agent1SignalId())
                .orElseThrow(() -> new IllegalArgumentException("Agent1 signal not found: " + request.agent1SignalId()));

        UserProfileEntity userProfile = userProfileRepository.findById(request.userProfileId())
                .orElseThrow(() -> new IllegalArgumentException("User profile not found: " + request.userProfileId()));

        int lotSize = fetchLotSize();
        MarketSnapshot snapshot = marketDataClient.fetchSnapshot();
        OptionChainData optionChain = optionChainClient.fetch(signal.getExpiryDate());

        int dte = (int) ChronoUnit.DAYS.between(LocalDate.now(), signal.getExpiryDate());

        RecommendationContext ctx = new RecommendationContext();
        ctx.setSignal(signal);
        ctx.setUserProfile(userProfile);
        ctx.setLotSize(lotSize);
        ctx.setSpot(snapshot.spot());
        ctx.setVix(snapshot.vix());
        // Compute 20-day annualised Historical Volatility from Upstox daily closes.
        // Returns null if Upstox is unavailable or there is insufficient data (e.g. holiday).
        // StrategySelector treats null/zero HV as IV regime = FAIR (no ratio computation).
        BigDecimal hv = volatilityService.computeHv20d();
        ctx.setHistoricalVolatility(hv != null ? hv : BigDecimal.ZERO);
        ctx.setExpiryDate(signal.getExpiryDate());
        ctx.setDte(dte);
        ctx.setOptionChainData(optionChain);

        engine.execute(ctx);

        TradeEntity trade = buildAndPersistTrade(ctx, signal, userProfile, snapshot, optionChain);

        log.info("recommendation.generated",
                kv("tradeId", trade.getId()),
                kv("strategy", ctx.getStrategy()),
                kv("lots", ctx.getLots()),
                kv("roc", ctx.getRoc()),
                kv("allGatesPassed", ctx.isAllHardGatesPassed()));

        return toTradeCardDto(trade, ctx);
    }

    @Transactional
    public TradeCardDto confirm(TradeConfirmRequestDto request) {
        TradeEntity trade = tradeRepository.findById(request.tradeId())
                .orElseThrow(() -> new TradeNotFoundException(request.tradeId()));

        if (trade.getStatus() != TradeStatus.PENDING_CONFIRM) {
            throw new IllegalStateException("Trade " + request.tradeId() + " is not in PENDING_CONFIRM state");
        }
        if (LocalDateTime.now().isAfter(trade.getValidUntil())) {
            trade.setStatus(TradeStatus.REJECTED);
            trade.setCloseReason("EXPIRED");
            tradeRepository.save(trade);
            throw new IllegalStateException("Trade card expired at " + trade.getValidUntil() + ". Please request a fresh recommendation.");
        }

        switch (request.action()) {
            case CONFIRM -> {
                if (request.overrideLots() != null) {
                    applyLotOverride(trade, request.overrideLots());
                }
                trade.setStatus(TradeStatus.CONFIRMED);
                trade.setConfirmedAt(LocalDateTime.now());
            }
            case REJECT -> {
                trade.setStatus(TradeStatus.REJECTED);
                trade.setCloseReason("USER_REJECTED");
            }
        }

        tradeRepository.save(trade);

        log.info("trade.confirmed",
                kv("tradeId", trade.getId()),
                kv("action", request.action()),
                kv("status", trade.getStatus()));

        return toTradeCardDtoFromEntity(trade);
    }

    @Transactional
    public MonitorConfigDto buildMonitorConfig(UUID tradeId, BigDecimal actualShortFill, BigDecimal actualLongFill) {
        TradeEntity trade = tradeRepository.findById(tradeId)
                .orElseThrow(() -> new TradeNotFoundException(tradeId));

        List<TradeLegDto> legs = jsonUtil.fromJson(trade.getLegs(), new TypeReference<>() {});
        TradeLegDto shortLeg = legs.stream().filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.SELL).findFirst()
                .orElseThrow(() -> new IllegalStateException("Short leg not found in trade " + tradeId));
        TradeLegDto longLeg = legs.stream().filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.BUY).findFirst()
                .orElseThrow(() -> new IllegalStateException("Long leg not found in trade " + tradeId));

        TradeSummary summary = jsonUtil.fromJson(trade.getSummary(), TradeSummary.class);

        TradeLegDto filledShortLeg = new TradeLegDto(shortLeg.optionType(), shortLeg.strike(),
                actualShortFill, shortLeg.action(), shortLeg.delta(), shortLeg.pop(), shortLeg.instrumentKey());
        TradeLegDto filledLongLeg = new TradeLegDto(longLeg.optionType(), longLeg.strike(),
                actualLongFill, longLeg.action(), longLeg.delta(), longLeg.pop(), longLeg.instrumentKey());

        SpreadDirection direction = trade.getSpreadDirection();
        BigDecimal actualNetPremium = direction == SpreadDirection.CREDIT
                ? actualShortFill.subtract(actualLongFill)
                : actualLongFill.subtract(actualShortFill);

        BigDecimal expectedNetPremium = summary.netPremiumPerUnit();
        BigDecimal slippageThreshold = expectedNetPremium.multiply(new BigDecimal("0.10"));
        boolean slippageAlert = actualNetPremium.compareTo(expectedNetPremium.subtract(slippageThreshold)) < 0;
        BigDecimal slippageAmount = slippageAlert
                ? expectedNetPremium.subtract(actualNetPremium).multiply(BigDecimal.valueOf(summary.lotSize()))
                        .multiply(BigDecimal.valueOf(summary.lots())).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        MonitorThresholdsDto thresholds = jsonUtil.fromJson(trade.getThresholds(), MonitorThresholdsDto.class);

        BigDecimal actualMaxLoss = BigDecimal.valueOf(
                Math.abs(shortLeg.strike() - longLeg.strike()))
                .multiply(BigDecimal.valueOf(summary.lotSize()))
                .subtract(actualNetPremium.multiply(BigDecimal.valueOf(summary.lotSize())))
                .multiply(BigDecimal.valueOf(summary.lots()))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal actualMaxProfit = actualNetPremium.multiply(BigDecimal.valueOf(summary.lotSize()))
                .multiply(BigDecimal.valueOf(summary.lots())).setScale(2, RoundingMode.HALF_UP);

        MonitorConfigDto monitorConfig = new MonitorConfigDto(
                trade.getId(), trade.getStrategy(), direction,
                filledShortLeg, filledLongLeg,
                actualNetPremium, summary.lots(), summary.lotSize(),
                actualMaxProfit, actualMaxLoss,
                slippageAlert, slippageAmount,
                thresholds,
                trade.getExpiryDate(),
                (int) ChronoUnit.DAYS.between(LocalDate.now(), trade.getExpiryDate())
        );

        trade.setMonitorConfig(jsonUtil.toJson(monitorConfig));
        tradeRepository.save(trade);

        log.info("monitor.config.built",
                kv("tradeId", tradeId),
                kv("actualNetPremium", actualNetPremium),
                kv("slippageAlert", slippageAlert),
                kv("slippageAmount", slippageAmount));

        return monitorConfig;
    }

    private int fetchLotSize() {
        return referenceDataRepository.findById(LOT_SIZE_KEY)
                .filter(ref -> !ref.isExpired())
                .map(ref -> jsonUtil.fromJson(ref.getValue(), Map.class))
                .map(map -> ((Number) map.get("lotSize")).intValue())
                .orElseThrow(() -> new IllegalStateException(
                        "Nifty lot size not found in reference_data. Run startup data loader first."));
    }

    private TradeEntity buildAndPersistTrade(RecommendationContext ctx, Agent1SignalEntity signal,
                                              UserProfileEntity userProfile, MarketSnapshot snapshot,
                                              OptionChainData optionChain) {
        LocalDateTime now = LocalDateTime.now();
        SpreadDirection direction = ctx.getSpreadDirection();

        List<TradeLegDto> legs = List.of(ctx.getShortLeg(), ctx.getLongLeg());
        BigDecimal netPremium = direction == SpreadDirection.CREDIT
                ? ctx.getShortLeg().ltp().subtract(ctx.getLongLeg().ltp())
                : ctx.getLongLeg().ltp().subtract(ctx.getShortLeg().ltp());

        // Layer 5 (PositionSizer) fields are null when engine exits early on HARD_GATE_FAILURE
        TradeSummary summary = new TradeSummary(
                netPremium, ctx.getLots(), ctx.getLotSize(),
                orZero(ctx.getMaxProfitTotal()), orZero(ctx.getTheoreticalMaxLossTotal()), orZero(ctx.getRealExpectedLossTotal()),
                ctx.getShortLeg().pop(), ctx.getLongLeg().pop(),
                ctx.getShortLeg().pop().subtract(ctx.getLongLeg().pop()).abs(),
                orZero(ctx.getRoc()), orZero(ctx.getRocAnnualised()), orZero(ctx.getNetDelta())
        );

        BigDecimal atmIv = optionChain.calls().stream()
                .filter(s -> s.strike() == optionChain.atmStrike())
                .findFirst().map(s -> s.iv()).orElse(BigDecimal.ZERO);

        BigDecimal ivHvRatio = ctx.getHistoricalVolatility().compareTo(BigDecimal.ZERO) > 0
                ? atmIv.divide(ctx.getHistoricalVolatility(), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        MarketContext marketContext = new MarketContext(
                snapshot.spot(), snapshot.vix(), atmIv,
                ctx.getHistoricalVolatility(), ivHvRatio,
                ctx.getIvRegime(), signal.getVixRegime(),
                ctx.getExpectedMove(), ctx.getOneFourSdBoundary()
        );

        MonitorThresholdsDto thresholds = buildThresholds(ctx);

        String rationale = buildRationale(ctx);

        TradeEntity trade = new TradeEntity();
        trade.setAgent1Signal(signal);
        trade.setUserProfile(userProfile);
        trade.setStatus(ctx.isAllHardGatesPassed() ? TradeStatus.PENDING_CONFIRM : TradeStatus.REJECTED);
        trade.setStrategy(ctx.getStrategy());
        trade.setSpreadDirection(ctx.getSpreadDirection() != null ? ctx.getSpreadDirection() : SpreadDirection.CREDIT);
        trade.setExpiryDate(signal.getExpiryDate());
        trade.setDte(ctx.getDte());
        trade.setLegs(jsonUtil.toJson(legs));
        trade.setSummary(jsonUtil.toJson(summary));
        trade.setMarketContext(jsonUtil.toJson(marketContext));
        trade.setThresholds(jsonUtil.toJson(thresholds));
        trade.setGateResults(jsonUtil.toJson(ctx.getGateResults()));
        trade.setGeneratedAt(now);
        trade.setValidUntil(now.plusMinutes(20));

        if (!ctx.isAllHardGatesPassed()) {
            trade.setCloseReason(resolveSkipReason(ctx));
        }

        // Generate unique trade code from DB sequence: T-YYYYMMDD-XXXX
        long seqVal = tradeRepository.nextTradeCodeSeq();
        String tradeCode = "T-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%04d", seqVal);
        trade.setTradeCode(tradeCode);

        return tradeRepository.save(trade);
    }

    private MonitorThresholdsDto buildThresholds(RecommendationContext ctx) {
        // Gate-failed trades are REJECTED — thresholds are irrelevant (trade won't be monitored)
        if (ctx.getTheoreticalMaxLossTotal() == null || !ctx.isAllHardGatesPassed()) {
            return new MonitorThresholdsDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal shortStrike = BigDecimal.valueOf(ctx.getShortLeg().strike());
        SpreadDirection direction = ctx.getSpreadDirection();

        if (direction == SpreadDirection.CREDIT) {
            // For sell spreads — downside thresholds below short strike.
            // T2 P&L: Agent 3 readjust alert at 30% of max loss (matches REAL_LOSS_FACTOR in PositionSizer).
            // T3 P&L: hard exit at full theoretical max loss.
            return new MonitorThresholdsDto(
                    shortStrike.add(BigDecimal.valueOf(100)),   // T1: Nifty at short + 100
                    shortStrike.add(BigDecimal.valueOf(50)),    // T2: Nifty at short + 50
                    shortStrike,                                // T3: Nifty at short strike
                    ctx.getTheoreticalMaxLossTotal().multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP),
                    ctx.getTheoreticalMaxLossTotal()
            );
        } else {
            // For debit spreads — profit exit targets.
            // T2 P&L: alert at 30% of max loss (premium paid).
            return new MonitorThresholdsDto(
                    ctx.getSpot().multiply(new BigDecimal("1.005")).setScale(0, RoundingMode.HALF_UP), // T1: 0.5% RoC level
                    ctx.getSpot().multiply(new BigDecimal("1.010")).setScale(0, RoundingMode.HALF_UP), // T2: 1% RoC level
                    ctx.getSpot(),                                                                       // T3: entry level (loss exit)
                    ctx.getTheoreticalMaxLossTotal().multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP),
                    ctx.getTheoreticalMaxLossTotal()
            );
        }
    }

    private String buildRationale(RecommendationContext ctx) {
        if (ctx.getStrategy() == Strategy.NO_TRADE || ctx.getStrategy() == Strategy.SKIP) {
            return "Strategy: " + ctx.getStrategy() + " — conditions not met for trade entry";
        }
        return "Strategy: " + ctx.getStrategy()
                + " | VIX: " + ctx.getVix() + " (" + ctx.getSignal().getVixRegime() + ")"
                + " | IV regime: " + ctx.getIvRegime()
                + " | EM(1.4SD): " + ctx.getOneFourSdBoundary()
                + " | Gates: " + (ctx.isAllHardGatesPassed() ? "ALL PASSED" : "FAILED");
    }

    private String resolveSkipReason(RecommendationContext ctx) {
        return ctx.getGateResults().stream()
                .filter(g -> !g.passed())
                .map(g -> g.gate() + "_FAILED")
                .reduce((a, b) -> a + "," + b)
                .orElse(ctx.getStrategy().name());
    }

    private TradeCardDto toTradeCardDto(TradeEntity trade, RecommendationContext ctx) {
        TradeSummary summary = jsonUtil.fromJson(trade.getSummary(), TradeSummary.class);
        return new TradeCardDto(
                trade.getId(), trade.getStrategy(), trade.getSpreadDirection(),
                trade.getExpiryDate(), trade.getDte(),
                ctx.getShortLeg(), ctx.getLongLeg(),
                summary.netPremiumPerUnit(), summary.lots(), summary.lotSize(),
                summary.maxProfitTotal(), summary.theoreticalMaxLossTotal(), summary.realExpectedLossTotal(),
                summary.pop(), summary.popp(), summary.popGap(),
                summary.roc(), summary.rocAnnualised(), summary.netDelta(),
                ctx.getGateResults(),
                jsonUtil.fromJson(trade.getThresholds(), MonitorThresholdsDto.class),
                buildRationale(ctx),
                trade.getGeneratedAt(), trade.getValidUntil(), trade.getStatus()
        );
    }

    private TradeCardDto toTradeCardDtoFromEntity(TradeEntity trade) {
        TradeSummary summary = jsonUtil.fromJson(trade.getSummary(), TradeSummary.class);
        List<TradeLegDto> legs = jsonUtil.fromJson(trade.getLegs(), new TypeReference<>() {});
        List<GateResultDto> gates = jsonUtil.fromJson(trade.getGateResults(), new TypeReference<>() {});
        TradeLegDto shortLeg = legs.stream().filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.SELL).findFirst().orElse(null);
        TradeLegDto longLeg = legs.stream().filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.BUY).findFirst().orElse(null);

        return new TradeCardDto(
                trade.getId(), trade.getStrategy(), trade.getSpreadDirection(),
                trade.getExpiryDate(), trade.getDte(),
                shortLeg, longLeg,
                summary.netPremiumPerUnit(), summary.lots(), summary.lotSize(),
                summary.maxProfitTotal(), summary.theoreticalMaxLossTotal(), summary.realExpectedLossTotal(),
                summary.pop(), summary.popp(), summary.popGap(),
                summary.roc(), summary.rocAnnualised(), summary.netDelta(),
                gates,
                jsonUtil.fromJson(trade.getThresholds(), MonitorThresholdsDto.class),
                trade.getCloseReason(),
                trade.getGeneratedAt(), trade.getValidUntil(), trade.getStatus()
        );
    }

    /** Null-safe BigDecimal — returns ZERO for null Layer 5 fields when engine exits early. */
    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private void applyLotOverride(TradeEntity trade, int overrideLots) {
        TradeSummary existing = jsonUtil.fromJson(trade.getSummary(), TradeSummary.class);
        BigDecimal scaleFactor = BigDecimal.valueOf(overrideLots)
                .divide(BigDecimal.valueOf(existing.lots()), 6, RoundingMode.HALF_UP);

        TradeSummary updated = new TradeSummary(
                existing.netPremiumPerUnit(), overrideLots, existing.lotSize(),
                existing.maxProfitTotal().multiply(scaleFactor).setScale(2, RoundingMode.HALF_UP),
                existing.theoreticalMaxLossTotal().multiply(scaleFactor).setScale(2, RoundingMode.HALF_UP),
                existing.realExpectedLossTotal().multiply(scaleFactor).setScale(2, RoundingMode.HALF_UP),
                existing.pop(), existing.popp(), existing.popGap(),
                existing.roc(), existing.rocAnnualised(), existing.netDelta()
        );
        trade.setSummary(jsonUtil.toJson(updated));
    }
}
