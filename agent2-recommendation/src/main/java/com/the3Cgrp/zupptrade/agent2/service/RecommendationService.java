package com.the3Cgrp.zupptrade.agent2.service;

import tools.jackson.core.type.TypeReference;
import com.the3Cgrp.zupptrade.agent2.client.MarketDataClient;
import com.the3Cgrp.zupptrade.agent2.client.OptionChainClient;
import com.the3Cgrp.zupptrade.agent2.client.model.MarketSnapshot;
import com.the3Cgrp.zupptrade.agent2.client.model.OptionChainData;
import com.the3Cgrp.zupptrade.agent2.client.model.StrikeData;
import com.the3Cgrp.zupptrade.agent2.engine.math.BlackScholesCalculator;
import com.the3Cgrp.zupptrade.agent2.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.ReferenceDataEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.TradeEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent2.domain.model.MarketContext;
import com.the3Cgrp.zupptrade.agent2.domain.model.TradeSummary;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationEngine;
import com.the3Cgrp.zupptrade.agent2.exception.MarketDataUnavailableException;
import com.the3Cgrp.zupptrade.agent2.exception.TradeNotFoundException;
import com.the3Cgrp.zupptrade.agent2.repository.Agent1SignalRepository;
import com.the3Cgrp.zupptrade.agent2.repository.ReferenceDataRepository;
import com.the3Cgrp.zupptrade.agent2.repository.TradeRepository;
import com.the3Cgrp.zupptrade.agent2.repository.UserProfileRepository;
import com.the3Cgrp.zupptrade.agent2.util.JsonUtil;
import com.the3Cgrp.zupptrade.ledger.LedgerEventType;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.ledger.payload.*;
import com.the3Cgrp.zupptrade.ledger.payload.TradeOverrideConfirmedPayload;
import com.the3Cgrp.zupptrade.shared.dto.*;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
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

    private static final BigDecimal RISK_FREE_RATE = new BigDecimal("0.065");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal POP_HARD_FLOOR = new BigDecimal("50");
    private static final BigDecimal REAL_LOSS_FACTOR = new BigDecimal("0.50");

    private final Agent1SignalRepository signalRepository;
    private final UserProfileRepository userProfileRepository;
    private final TradeRepository tradeRepository;
    private final ReferenceDataRepository referenceDataRepository;
    private final OptionChainClient optionChainClient;
    private final MarketDataClient marketDataClient;
    private final RecommendationEngine engine;
    private final VolatilityService volatilityService;
    private final BlackScholesCalculator blackScholes;
    private final JsonUtil jsonUtil;
    private final TradeLedgerService ledger;

    public RecommendationService(Agent1SignalRepository signalRepository,
                                  UserProfileRepository userProfileRepository,
                                  TradeRepository tradeRepository,
                                  ReferenceDataRepository referenceDataRepository,
                                  OptionChainClient optionChainClient,
                                  MarketDataClient marketDataClient,
                                  RecommendationEngine engine,
                                  VolatilityService volatilityService,
                                  BlackScholesCalculator blackScholes,
                                  JsonUtil jsonUtil,
                                  TradeLedgerService ledger) {
        this.signalRepository = signalRepository;
        this.userProfileRepository = userProfileRepository;
        this.tradeRepository = tradeRepository;
        this.referenceDataRepository = referenceDataRepository;
        this.optionChainClient = optionChainClient;
        this.marketDataClient = marketDataClient;
        this.engine = engine;
        this.volatilityService = volatilityService;
        this.blackScholes = blackScholes;
        this.jsonUtil = jsonUtil;
        this.ledger = ledger;
    }

    @Transactional
    public TradeCardDto recommend(RecommendRequestDto request) {
        Agent1SignalEntity signal = signalRepository.findById(request.agent1SignalId())
                .orElseThrow(() -> new IllegalArgumentException("Agent1 signal not found: " + request.agent1SignalId()));

        UserProfileEntity userProfile = userProfileRepository.findById(request.userProfileId())
                .orElseThrow(() -> new IllegalArgumentException("User profile not found: " + request.userProfileId()));

        int lotSize = fetchLotSize();
        // Option chain comes first — underlying_spot_price is available 24/7 (last-session data).
        OptionChainData optionChain = optionChainClient.fetch(signal.getExpiryDate());

        // Live market quote is only available during market hours.
        // On weekends/holidays it returns empty data, so we catch and fall back gracefully.
        MarketSnapshot snapshot = null;
        try {
            snapshot = marketDataClient.fetchSnapshot();
        } catch (MarketDataUnavailableException ex) {
            log.warn("market.snapshot.unavailable reason=market_closed_or_holiday fallback=option_chain_spot_and_signal_vix");
        }
        BigDecimal spot = (snapshot != null && snapshot.spot().compareTo(BigDecimal.ZERO) > 0)
                ? snapshot.spot() : optionChain.spot();
        BigDecimal vix = (snapshot != null && snapshot.vix().compareTo(BigDecimal.ZERO) > 0)
                ? snapshot.vix() : (signal.getVixLevel() != null ? signal.getVixLevel() : BigDecimal.ZERO);

        int dte = (int) ChronoUnit.DAYS.between(LocalDate.now(), signal.getExpiryDate());

        RecommendationContext ctx = new RecommendationContext();
        ctx.setSignal(signal);
        ctx.setUserProfile(userProfile);
        ctx.setLotSize(lotSize);
        ctx.setSpot(spot);
        ctx.setVix(vix);
        // Compute 20-day annualised Historical Volatility from Upstox daily closes.
        // Returns null if Upstox is unavailable or there is insufficient data (e.g. holiday).
        // StrategySelector treats null/zero HV as IV regime = FAIR (no ratio computation).
        BigDecimal hv = volatilityService.computeHv20d();
        ctx.setHistoricalVolatility(hv != null ? hv : BigDecimal.ZERO);
        ctx.setExpiryDate(signal.getExpiryDate());
        ctx.setDte(dte);
        ctx.setOptionChainData(optionChain);
        ctx.setRelaxedGate1PopPct(request.relaxedGate1PopPct());  // null for normal flow; set by ReadjustmentService

        engine.execute(ctx);

        // Layer 1 selected NO_TRADE or SKIP — engine exits before strike selection;
        // shortLeg/longLeg remain null and List.of(null, null) in buildAndPersistTrade would NPE.
        if (ctx.getStrategy() == Strategy.NO_TRADE || ctx.getStrategy() == Strategy.SKIP) {
            return handleNoTrade(ctx, signal, userProfile);
        }

        TradeEntity trade = buildAndPersistTrade(ctx, signal, userProfile, optionChain);

        // Ledger: TRADE_PENDING (gates passed) or TRADE_REJECTED (gate failure)
        // Record joins the outer @Transactional — commits with the trade row (FK safe).
        TradeSummary ledgerSummary = jsonUtil.fromJson(trade.getSummary(), TradeSummary.class);
        if (trade.getStatus() == TradeStatus.PENDING_CONFIRM) {
            ledger.record(trade.getId(), LedgerEventType.TRADE_PENDING,
                    new TradePendingPayload(
                            signal.getId(), userProfile.getId(),
                            ctx.getStrategy(),
                            signal.getCompositeScore(),
                            signal.getBias() != null ? signal.getBias().name() : null,
                            signal.getStrength() != null ? signal.getStrength().name() : null,
                            ledgerSummary.netPremiumPerUnit(),
                            ctx.getLots(), ctx.getLotSize(),
                            ledgerSummary.maxProfitTotal(),
                            ledgerSummary.theoreticalMaxLossTotal(),
                            ledgerSummary.pop(),
                            orZero(ctx.getRoc())),
                    "AGENT2:SYSTEM");
        } else {
            ledger.record(trade.getId(), LedgerEventType.TRADE_REJECTED,
                    new TradeRejectedPayload("GATE_FAILURE", trade.getCloseReason()),
                    "AGENT2:SYSTEM");
        }

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

        boolean isManualOverride = request.overrideParams() != null
                && request.action() == com.the3Cgrp.zupptrade.shared.enums.ConfirmAction.CONFIRM;

        // Normal path: only PENDING_CONFIRM allowed; check expiry.
        // Override path: also allow REJECTED trades (gate-failed) since user is bypassing gates.
        if (isManualOverride) {
            if (trade.getStatus() != TradeStatus.PENDING_CONFIRM
                    && trade.getStatus() != TradeStatus.REJECTED) {
                throw new IllegalStateException(
                        "Trade " + request.tradeId() + " is not in a confirmable state for override (status=" + trade.getStatus() + ")");
            }
        } else {
            if (trade.getStatus() != TradeStatus.PENDING_CONFIRM) {
                throw new IllegalStateException("Trade " + request.tradeId() + " is not in PENDING_CONFIRM state");
            }
            if (LocalDateTime.now().isAfter(trade.getValidUntil())) {
                trade.setStatus(TradeStatus.REJECTED);
                trade.setCloseReason("EXPIRED");
                tradeRepository.save(trade);
                throw new IllegalStateException("Trade card expired at " + trade.getValidUntil() + ". Please request a fresh recommendation.");
            }
        }

        // Capture originals before any mutation for the audit payload
        String originalLegsJson    = trade.getLegs();
        TradeSummary originalSummary = isManualOverride
                ? jsonUtil.fromJson(trade.getSummary(), TradeSummary.class) : null;

        switch (request.action()) {
            case CONFIRM -> {
                if (isManualOverride) {
                    applyManualOverride(trade, request.overrideParams());
                } else {
                    if (request.overrideLots() != null) {
                        applyLotOverride(trade, request.overrideLots());
                    }
                    if (request.overrideThresholds() != null) {
                        applyThresholdOverride(trade, request.overrideThresholds());
                    }
                }
                trade.setStatus(TradeStatus.CONFIRMED);
                trade.setConfirmedAt(LocalDateTime.now());
                trade.setCloseReason(null);
            }
            case REJECT -> {
                trade.setStatus(TradeStatus.REJECTED);
                trade.setCloseReason("USER_REJECTED");
            }
        }

        tradeRepository.save(trade);

        if (request.action() == com.the3Cgrp.zupptrade.shared.enums.ConfirmAction.CONFIRM) {
            if (isManualOverride) {
                TradeOverrideConfirmedPayload payload = new TradeOverrideConfirmedPayload(
                        trade.getUserProfile() != null ? trade.getUserProfile().getId() : null,
                        originalLegsJson,
                        originalSummary != null ? originalSummary.pop().multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                        originalSummary != null ? originalSummary.roc() : BigDecimal.ZERO,
                        request.overrideParams().peShortStrike(), request.overrideParams().peLongStrike(),
                        request.overrideParams().ceShortStrike(), request.overrideParams().ceLongStrike(),
                        request.overrideParams().lots(), request.overrideParams().pop(), request.overrideParams().roc(),
                        request.overrideParams().netPremiumPerUnit(), request.overrideParams().maxProfitTotal(),
                        request.overrideParams().realExpectedLossTotal());
                ledger.record(trade.getId(), LedgerEventType.TRADE_CONFIRMED_WITH_OVERRIDE, payload, "AGENT2:USER");
            } else {
                ledger.record(trade.getId(), LedgerEventType.TRADE_APPROVED,
                        new TradeApprovedPayload(
                                trade.getUserProfile() != null ? trade.getUserProfile().getId() : null,
                                request.overrideLots()),
                        "AGENT2:USER");
            }
        } else {
            ledger.record(trade.getId(), LedgerEventType.TRADE_REJECTED,
                    new TradeRejectedPayload("USER", "USER_REJECTED"),
                    "AGENT2:USER");
        }

        log.info("trade.confirmed",
                kv("tradeId", trade.getId()),
                kv("action", request.action()),
                kv("manualOverride", isManualOverride),
                kv("status", trade.getStatus()));

        // Seed monitor_config immediately at confirm time using algo LTPs.
        // Agent3 scheduler will overwrite this with actual fill prices on its next cycle.
        // This ensures the live monitor shows data immediately — even outside market hours.
        if (request.action() == com.the3Cgrp.zupptrade.shared.enums.ConfirmAction.CONFIRM) {
            seedMonitorConfigFromAlgoLtps(trade);
        }

        return toTradeCardDtoFromEntity(trade);
    }

    /**
     * Seeds monitor_config at confirm time using algo LTPs as stand-in fill prices.
     * Non-fatal — any failure is logged and skipped; Agent3 scheduler will bootstrap on first cycle.
     */
    private void seedMonitorConfigFromAlgoLtps(TradeEntity trade) {
        try {
            List<TradeLegDto> legs = jsonUtil.fromJson(trade.getLegs(), new TypeReference<List<TradeLegDto>>() {});
            boolean isIc = trade.getStrategy() == com.the3Cgrp.zupptrade.shared.enums.Strategy.IRON_CONDOR
                        || trade.getStrategy() == com.the3Cgrp.zupptrade.shared.enums.Strategy.WIDE_IRON_CONDOR;

            BigDecimal peShortLtp, peLongLtp;
            BigDecimal ceShortLtp = null, ceLongLtp = null;

            if (isIc) {
                peShortLtp = legs.stream()
                        .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.SELL
                                  && l.optionType() == com.the3Cgrp.zupptrade.shared.enums.OptionType.PE)
                        .map(TradeLegDto::ltp).findFirst().orElseThrow();
                peLongLtp  = legs.stream()
                        .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.BUY
                                  && l.optionType() == com.the3Cgrp.zupptrade.shared.enums.OptionType.PE)
                        .map(TradeLegDto::ltp).findFirst().orElseThrow();
                ceShortLtp = legs.stream()
                        .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.SELL
                                  && l.optionType() == com.the3Cgrp.zupptrade.shared.enums.OptionType.CE)
                        .map(TradeLegDto::ltp).findFirst().orElseThrow();
                ceLongLtp  = legs.stream()
                        .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.BUY
                                  && l.optionType() == com.the3Cgrp.zupptrade.shared.enums.OptionType.CE)
                        .map(TradeLegDto::ltp).findFirst().orElseThrow();
            } else {
                peShortLtp = legs.stream()
                        .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.SELL)
                        .map(TradeLegDto::ltp).findFirst().orElseThrow();
                peLongLtp  = legs.stream()
                        .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.BUY)
                        .map(TradeLegDto::ltp).findFirst().orElseThrow();
            }

            buildMonitorConfig(trade.getId(), peShortLtp, peLongLtp, ceShortLtp, ceLongLtp);
            log.info("monitor.config.seeded_at_confirm tradeId={}", trade.getId());
        } catch (Exception e) {
            log.warn("monitor.config.seed_failed_at_confirm tradeId={} error={} — Agent3 will bootstrap on first market-hours cycle",
                    trade.getId(), e.getMessage());
        }
    }

    /**
     * Re-seeds monitor_config for an existing CONFIRMED/ACTIVE trade using the LTPs
     * stored in trade.legs. Used to repair trades that went through the override-confirm
     * path before thresholds were computed correctly.
     */
    @org.springframework.transaction.annotation.Transactional
    public MonitorConfigDto refreshMonitorConfig(UUID tradeId) {
        TradeEntity trade = tradeRepository.findById(tradeId)
                .orElseThrow(() -> new TradeNotFoundException(tradeId));
        seedMonitorConfigFromAlgoLtps(trade);
        // Flush so the monitor_config write is visible in the same tx before we read it back.
        tradeRepository.flush();
        TradeEntity refreshed = tradeRepository.findById(tradeId)
                .orElseThrow(() -> new TradeNotFoundException(tradeId));
        return jsonUtil.fromJson(refreshed.getMonitorConfig(), MonitorConfigDto.class);
    }

    /**
     * Stateless recalculation for the manual override builder. Does not persist anything.
     * Fetches live LTP from Upstox for each provided strike, runs Black-Scholes PoP,
     * and evaluates the two hard override rules: PoP ≥ 50% and real expected loss ≤ 1.5%.
     */
    public CalculateOverrideResultDto calculateOverride(CalculateOverrideRequestDto req) {
        TradeEntity trade = tradeRepository.findById(req.tradeId())
                .orElseThrow(() -> new TradeNotFoundException(req.tradeId()));

        int lotSize = fetchLotSize();
        int dte = (int) ChronoUnit.DAYS.between(LocalDate.now(), trade.getExpiryDate());
        BigDecimal capital = trade.getUserProfile().getCapital();
        boolean isIc = req.ceShortStrike() != null && req.ceLongStrike() != null;

        // Fetch live option chain for current expiry
        OptionChainData chain = optionChainClient.fetch(trade.getExpiryDate());

        // Resolve LTP and IV for each requested strike
        StrikeData peShortData = findStrike(chain.puts(), req.peShortStrike(), "PE short");
        StrikeData peLongData  = findStrike(chain.puts(), req.peLongStrike(),  "PE long");

        StrikeData ceShortData = isIc ? findStrike(chain.calls(), req.ceShortStrike(), "CE short") : null;
        StrikeData ceLongData  = isIc ? findStrike(chain.calls(), req.ceLongStrike(),  "CE long")  : null;

        BigDecimal peShortLtp = peShortData.ltp();
        BigDecimal peLongLtp  = peLongData.ltp();
        BigDecimal ceShortLtp = isIc ? ceShortData.ltp() : null;
        BigDecimal ceLongLtp  = isIc ? ceLongData.ltp()  : null;

        // Combined net premium
        BigDecimal pePremium = peShortLtp.subtract(peLongLtp);
        BigDecimal cePremium = isIc ? ceShortLtp.subtract(ceLongLtp) : BigDecimal.ZERO;
        BigDecimal netPremiumPerUnit = pePremium.add(cePremium).setScale(2, RoundingMode.HALF_UP);

        // Spread widths
        int peWidth = Math.abs(req.peShortStrike() - req.peLongStrike());
        int spreadWidth = isIc
                ? Math.max(peWidth, Math.abs(req.ceShortStrike() - req.ceLongStrike()))
                : peWidth;

        BigDecimal spreadValue   = BigDecimal.valueOf(spreadWidth).multiply(BigDecimal.valueOf(lotSize));
        BigDecimal premiumTotal  = netPremiumPerUnit.multiply(BigDecimal.valueOf(lotSize));
        BigDecimal maxLossPerLot = spreadValue.subtract(premiumTotal);
        BigDecimal maxProfitPerLot = premiumTotal;

        BigDecimal maxProfitTotal           = maxProfitPerLot.multiply(BigDecimal.valueOf(req.lots())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal theoreticalMaxLossTotal  = maxLossPerLot.multiply(BigDecimal.valueOf(req.lots())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal realExpectedLossTotal    = theoreticalMaxLossTotal.multiply(REAL_LOSS_FACTOR).setScale(2, RoundingMode.HALF_UP);

        BigDecimal roc = maxProfitTotal.divide(capital, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED).setScale(4, RoundingMode.HALF_UP);

        // Black-Scholes PoP on PE short strike (the defining downside risk leg)
        BigDecimal iv = peShortData.iv() != null ? peShortData.iv() : chain.spot().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal popRaw = blackScholes.calculatePop(chain.spot(), BigDecimal.valueOf(req.peShortStrike()), iv, dte, RISK_FREE_RATE);
        BigDecimal pop = popRaw.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);

        // Two-rule hard blocks
        boolean popBlocked  = pop.compareTo(POP_HARD_FLOOR) < 0;
        BigDecimal maxLossPct = capital.multiply(new BigDecimal("0.015"));
        boolean lossBlocked = realExpectedLossTotal.compareTo(maxLossPct) > 0;

        log.info("override.calculate",
                kv("tradeId", req.tradeId()),
                kv("peShortStrike", req.peShortStrike()),
                kv("peLongStrike", req.peLongStrike()),
                kv("ceShortStrike", req.ceShortStrike()),
                kv("ceLongStrike", req.ceLongStrike()),
                kv("lots", req.lots()),
                kv("netPremium", netPremiumPerUnit),
                kv("pop", pop),
                kv("roc", roc),
                kv("popBlocked", popBlocked),
                kv("lossBlocked", lossBlocked));

        return new CalculateOverrideResultDto(
                peShortLtp, peLongLtp, ceShortLtp, ceLongLtp,
                peShortData.instrumentKey(), peLongData.instrumentKey(),
                isIc ? ceShortData.instrumentKey() : null,
                isIc ? ceLongData.instrumentKey()  : null,
                netPremiumPerUnit, pop,
                maxProfitTotal, theoreticalMaxLossTotal, realExpectedLossTotal, roc,
                popBlocked, lossBlocked);
    }

    private StrikeData findStrike(List<com.the3Cgrp.zupptrade.agent2.client.model.StrikeData> chain,
                                   int strike, String label) {
        return chain.stream()
                .filter(s -> s.strike() == strike)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Strike " + strike + " (" + label + ") not found in Upstox option chain for this expiry."));
    }

    @Transactional
    public MonitorConfigDto buildMonitorConfig(UUID tradeId,
                                               BigDecimal actualPeShortFill, BigDecimal actualPeLongFill,
                                               BigDecimal actualCeShortFill, BigDecimal actualCeLongFill) {
        TradeEntity trade = tradeRepository.findById(tradeId)
                .orElseThrow(() -> new TradeNotFoundException(tradeId));

        List<TradeLegDto> legs = jsonUtil.fromJson(trade.getLegs(), new TypeReference<>() {});
        TradeSummary summary = jsonUtil.fromJson(trade.getSummary(), TradeSummary.class);
        // For 2-leg spreads only — IC thresholds are computed from legs below (override path can leave zeros here)
        MonitorThresholdsDto storedThresholds = jsonUtil.fromJson(trade.getThresholds(), MonitorThresholdsDto.class);
        SpreadDirection direction = trade.getSpreadDirection();
        int dte = (int) ChronoUnit.DAYS.between(LocalDate.now(), trade.getExpiryDate());

        MonitorConfigDto monitorConfig;
        boolean isIronCondor = trade.getStrategy() == Strategy.IRON_CONDOR
                || trade.getStrategy() == Strategy.WIDE_IRON_CONDOR;

        if (isIronCondor) {
            // IC: 4 legs — find each by optionType + action to avoid order dependency
            TradeLegDto peShort = legs.stream()
                    .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.SELL
                              && l.optionType() == com.the3Cgrp.zupptrade.shared.enums.OptionType.PE)
                    .findFirst().orElseThrow(() -> new IllegalStateException("IC PE SELL leg not found in " + tradeId));
            TradeLegDto peLong = legs.stream()
                    .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.BUY
                              && l.optionType() == com.the3Cgrp.zupptrade.shared.enums.OptionType.PE)
                    .findFirst().orElseThrow(() -> new IllegalStateException("IC PE BUY leg not found in " + tradeId));
            TradeLegDto ceShort = legs.stream()
                    .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.SELL
                              && l.optionType() == com.the3Cgrp.zupptrade.shared.enums.OptionType.CE)
                    .findFirst().orElseThrow(() -> new IllegalStateException("IC CE SELL leg not found in " + tradeId));
            TradeLegDto ceLong = legs.stream()
                    .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.BUY
                              && l.optionType() == com.the3Cgrp.zupptrade.shared.enums.OptionType.CE)
                    .findFirst().orElseThrow(() -> new IllegalStateException("IC CE BUY leg not found in " + tradeId));

            TradeLegDto filledPeShort = withFill(peShort, actualPeShortFill);
            TradeLegDto filledPeLong  = withFill(peLong,  actualPeLongFill);
            TradeLegDto filledCeShort = withFill(ceShort, actualCeShortFill);
            TradeLegDto filledCeLong  = withFill(ceLong,  actualCeLongFill);

            // Net premium = (PE net) + (CE net)
            BigDecimal peNet = actualPeShortFill.subtract(actualPeLongFill);
            BigDecimal ceNet = actualCeShortFill.subtract(actualCeLongFill);
            BigDecimal actualNetPremium = peNet.add(ceNet);

            BigDecimal expectedNetPremium = summary.netPremiumPerUnit();
            boolean slippageAlert = actualNetPremium.compareTo(
                    expectedNetPremium.subtract(expectedNetPremium.multiply(new BigDecimal("0.10")))) < 0;
            BigDecimal slippageAmount = slippageAlert
                    ? expectedNetPremium.subtract(actualNetPremium)
                            .multiply(BigDecimal.valueOf(summary.lotSize()))
                            .multiply(BigDecimal.valueOf(summary.lots())).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            // Max loss = max-of-spreads × lots (IC can only lose on one side at a time)
            int peWidth = Math.abs(peShort.strike() - peLong.strike());
            int ceWidth = Math.abs(ceShort.strike() - ceLong.strike());
            int maxWidth = Math.max(peWidth, ceWidth);
            BigDecimal actualMaxLoss = BigDecimal.valueOf(maxWidth)
                    .multiply(BigDecimal.valueOf(summary.lotSize()))
                    .subtract(actualNetPremium.multiply(BigDecimal.valueOf(summary.lotSize())))
                    .multiply(BigDecimal.valueOf(summary.lots()))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal actualMaxProfit = actualNetPremium
                    .multiply(BigDecimal.valueOf(summary.lotSize()))
                    .multiply(BigDecimal.valueOf(summary.lots())).setScale(2, RoundingMode.HALF_UP);

            // Compute IC thresholds from the actual strikes — trade.thresholds can be stale
            // (all-zeros) when the trade was generated via override from a gate-failed recommendation.
            BigDecimal peShortBd = BigDecimal.valueOf(peShort.strike());
            BigDecimal ceShortBd = BigDecimal.valueOf(ceShort.strike());
            BigDecimal icMaxLoss = summary.theoreticalMaxLossTotal() != null
                    && summary.theoreticalMaxLossTotal().compareTo(BigDecimal.ZERO) > 0
                    ? summary.theoreticalMaxLossTotal() : actualMaxLoss;
            MonitorThresholdsDto icThresholds = MonitorThresholdsDto.ironCondor(
                    peShortBd.add(BigDecimal.valueOf(100)),
                    peShortBd.add(BigDecimal.valueOf(50)),
                    peShortBd,
                    ceShortBd.subtract(BigDecimal.valueOf(100)),
                    ceShortBd.subtract(BigDecimal.valueOf(50)),
                    ceShortBd,
                    icMaxLoss.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP),
                    icMaxLoss);

            monitorConfig = MonitorConfigDto.ironCondor(
                    trade.getId(), trade.getStrategy(), direction,
                    filledPeShort, filledPeLong, filledCeShort, filledCeLong,
                    actualNetPremium, summary.lots(), summary.lotSize(),
                    actualMaxProfit, actualMaxLoss,
                    slippageAlert, slippageAmount,
                    icThresholds, trade.getExpiryDate(), dte);

        } else {
            // 2-leg spread: find by action (no ambiguity — only one SELL and one BUY)
            TradeLegDto shortLeg = legs.stream()
                    .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.SELL).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Short leg not found in trade " + tradeId));
            TradeLegDto longLeg = legs.stream()
                    .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.BUY).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Long leg not found in trade " + tradeId));

            TradeLegDto filledShortLeg = withFill(shortLeg, actualPeShortFill);
            TradeLegDto filledLongLeg  = withFill(longLeg,  actualPeLongFill);

            BigDecimal actualNetPremium = direction == SpreadDirection.CREDIT
                    ? actualPeShortFill.subtract(actualPeLongFill)
                    : actualPeLongFill.subtract(actualPeShortFill);

            BigDecimal expectedNetPremium = summary.netPremiumPerUnit();
            boolean slippageAlert = actualNetPremium.compareTo(
                    expectedNetPremium.subtract(expectedNetPremium.multiply(new BigDecimal("0.10")))) < 0;
            BigDecimal slippageAmount = slippageAlert
                    ? expectedNetPremium.subtract(actualNetPremium)
                            .multiply(BigDecimal.valueOf(summary.lotSize()))
                            .multiply(BigDecimal.valueOf(summary.lots())).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            BigDecimal actualMaxLoss = BigDecimal.valueOf(Math.abs(shortLeg.strike() - longLeg.strike()))
                    .multiply(BigDecimal.valueOf(summary.lotSize()))
                    .subtract(actualNetPremium.multiply(BigDecimal.valueOf(summary.lotSize())))
                    .multiply(BigDecimal.valueOf(summary.lots()))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal actualMaxProfit = actualNetPremium
                    .multiply(BigDecimal.valueOf(summary.lotSize()))
                    .multiply(BigDecimal.valueOf(summary.lots())).setScale(2, RoundingMode.HALF_UP);

            monitorConfig = MonitorConfigDto.twoLeg(
                    trade.getId(), trade.getStrategy(), direction,
                    filledShortLeg, filledLongLeg,
                    actualNetPremium, summary.lots(), summary.lotSize(),
                    actualMaxProfit, actualMaxLoss,
                    slippageAlert, slippageAmount,
                    storedThresholds, trade.getExpiryDate(), dte);
        }

        trade.setMonitorConfig(jsonUtil.toJson(monitorConfig));
        tradeRepository.save(trade);

        log.info("monitor.config.built",
                kv("tradeId", tradeId),
                kv("strategy", trade.getStrategy()),
                kv("isIronCondor", isIronCondor));

        return monitorConfig;
    }

    private static TradeLegDto withFill(TradeLegDto leg, BigDecimal fillPrice) {
        return new TradeLegDto(leg.optionType(), leg.strike(), fillPrice,
                leg.action(), leg.delta(), leg.pop(), leg.instrumentKey());
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
                                              UserProfileEntity userProfile,
                                              OptionChainData optionChain) {
        LocalDateTime now = LocalDateTime.now();
        SpreadDirection direction = ctx.getSpreadDirection();

        // Iron Condor has 4 legs; all others have 2
        List<TradeLegDto> legs = (ctx.getStrategy() == Strategy.IRON_CONDOR
                || ctx.getStrategy() == Strategy.WIDE_IRON_CONDOR)
                ? List.of(ctx.getShortLeg(), ctx.getLongLeg(), ctx.getShortLeg2(), ctx.getLongLeg2())
                : List.of(ctx.getShortLeg(), ctx.getLongLeg());
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
                ctx.getSpot(), ctx.getVix(), atmIv,
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

        // saveAndFlush forces JPA to send the INSERT to the DB immediately (within the current connection/tx).
        // Without this, the ledger's JdbcTemplate INSERT on trade_ledger sees an unflushed trade row and
        // triggers a FK violation even though both are in the same @Transactional.
        return tradeRepository.saveAndFlush(trade);
    }

    private MonitorThresholdsDto buildThresholds(RecommendationContext ctx) {
        // Gate-failed trades are REJECTED — thresholds are irrelevant (trade won't be monitored)
        if (ctx.getTheoreticalMaxLossTotal() == null || !ctx.isAllHardGatesPassed()) {
            return MonitorThresholdsDto.twoLeg(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal t2Loss = ctx.getTheoreticalMaxLossTotal().multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal t3Loss = ctx.getTheoreticalMaxLossTotal();

        if (ctx.getStrategy() == Strategy.IRON_CONDOR || ctx.getStrategy() == Strategy.WIDE_IRON_CONDOR) {
            // Iron Condor: bilateral thresholds — PE short (down side) + CE short (up side)
            BigDecimal peShort = BigDecimal.valueOf(ctx.getShortLeg().strike());
            BigDecimal ceShort = BigDecimal.valueOf(ctx.getShortLeg2().strike());
            return MonitorThresholdsDto.ironCondor(
                    peShort.add(BigDecimal.valueOf(100)),      // T1 watch: Nifty 100 above PE short
                    peShort.add(BigDecimal.valueOf(50)),       // T2 readjust: Nifty 50 above PE short
                    peShort,                                   // T3 exit: Nifty at PE short (breach)
                    ceShort.subtract(BigDecimal.valueOf(100)), // T1 watch: Nifty 100 below CE short
                    ceShort.subtract(BigDecimal.valueOf(50)),  // T2 readjust: Nifty 50 below CE short
                    ceShort,                                   // T3 exit: Nifty at CE short (breach)
                    t2Loss, t3Loss);
        }

        BigDecimal shortStrike = BigDecimal.valueOf(ctx.getShortLeg().strike());
        SpreadDirection direction = ctx.getSpreadDirection();

        if (direction == SpreadDirection.CREDIT) {
            // Bull Put Spread (PE short): danger is Nifty FALLING → T1/T2 are ABOVE the short strike
            // Bear Call Spread (CE short): danger is Nifty RISING → T1/T2 are BELOW the short strike
            boolean isCeShort = ctx.getShortLeg().optionType() == OptionType.CE;
            BigDecimal t1 = isCeShort
                    ? shortStrike.subtract(BigDecimal.valueOf(100))  // 100 pts below CE short
                    : shortStrike.add(BigDecimal.valueOf(100));       // 100 pts above PE short
            BigDecimal t2 = isCeShort
                    ? shortStrike.subtract(BigDecimal.valueOf(50))
                    : shortStrike.add(BigDecimal.valueOf(50));
            return MonitorThresholdsDto.twoLeg(t1, t2, shortStrike, t2Loss, t3Loss);
        } else {
            // Debit spreads: profit exit targets (Nifty rising = profit for bull call)
            return MonitorThresholdsDto.twoLeg(
                    ctx.getSpot().multiply(new BigDecimal("1.005")).setScale(0, RoundingMode.HALF_UP), // T1: 0.5% RoC level
                    ctx.getSpot().multiply(new BigDecimal("1.010")).setScale(0, RoundingMode.HALF_UP), // T2: 1% RoC level
                    ctx.getSpot(),                                                                       // T3: entry level (loss exit)
                    t2Loss, t3Loss);
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

    private TradeCardDto handleNoTrade(RecommendationContext ctx,
                                       Agent1SignalEntity signal,
                                       UserProfileEntity userProfile) {
        LocalDateTime now = LocalDateTime.now();
        String reason = ctx.getStrategy().name();

        TradeSummary summary = new TradeSummary(
                BigDecimal.ZERO, 0, ctx.getLotSize(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        MarketContext marketContext = new MarketContext(
                ctx.getSpot(), ctx.getVix(), BigDecimal.ZERO,
                ctx.getHistoricalVolatility(), BigDecimal.ZERO,
                ctx.getIvRegime(), signal.getVixRegime(),
                null, null);

        MonitorThresholdsDto thresholds = MonitorThresholdsDto.twoLeg(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);

        TradeEntity trade = new TradeEntity();
        trade.setAgent1Signal(signal);
        trade.setUserProfile(userProfile);
        trade.setStatus(TradeStatus.REJECTED);
        trade.setStrategy(ctx.getStrategy());
        trade.setSpreadDirection(null);
        trade.setExpiryDate(signal.getExpiryDate());
        trade.setDte(ctx.getDte());
        trade.setLegs(jsonUtil.toJson(List.of()));
        trade.setSummary(jsonUtil.toJson(summary));
        trade.setMarketContext(jsonUtil.toJson(marketContext));
        trade.setThresholds(jsonUtil.toJson(thresholds));
        trade.setGateResults(jsonUtil.toJson(List.of()));
        trade.setGeneratedAt(now);
        trade.setValidUntil(now);
        trade.setCloseReason(reason);

        long seqVal = tradeRepository.nextTradeCodeSeq();
        String tradeCode = "T-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%04d", seqVal);
        trade.setTradeCode(tradeCode);

        tradeRepository.saveAndFlush(trade);

        ledger.record(trade.getId(), LedgerEventType.TRADE_REJECTED,
                new TradeRejectedPayload(reason, reason),
                "AGENT2:SYSTEM");

        log.info("recommendation.no_trade",
                kv("tradeId", trade.getId()),
                kv("strategy", ctx.getStrategy()),
                kv("vixRegime", signal.getVixRegime()),
                kv("reason", reason));

        return toTradeCardDtoFromEntity(trade);
    }

    private TradeCardDto toTradeCardDto(TradeEntity trade, RecommendationContext ctx) {
        TradeSummary summary = jsonUtil.fromJson(trade.getSummary(), TradeSummary.class);
        boolean isIc = trade.getStrategy() == Strategy.IRON_CONDOR || trade.getStrategy() == Strategy.WIDE_IRON_CONDOR;
        return new TradeCardDto(
                trade.getId(), trade.getStrategy(), trade.getSpreadDirection(),
                trade.getExpiryDate(), trade.getDte(),
                ctx.getShortLeg(), ctx.getLongLeg(),
                isIc ? ctx.getShortLeg2() : null, isIc ? ctx.getLongLeg2() : null,
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

        boolean isIc = trade.getStrategy() == Strategy.IRON_CONDOR || trade.getStrategy() == Strategy.WIDE_IRON_CONDOR;
        TradeLegDto shortLeg, longLeg, shortLeg2, longLeg2;
        if (isIc) {
            // IC: 4 legs — find each by optionType + action to avoid order dependency
            shortLeg  = legs.stream().filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.SELL && l.optionType() == OptionType.PE).findFirst().orElse(null);
            longLeg   = legs.stream().filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.BUY  && l.optionType() == OptionType.PE).findFirst().orElse(null);
            shortLeg2 = legs.stream().filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.SELL && l.optionType() == OptionType.CE).findFirst().orElse(null);
            longLeg2  = legs.stream().filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.BUY  && l.optionType() == OptionType.CE).findFirst().orElse(null);
        } else {
            shortLeg  = legs.stream().filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.SELL).findFirst().orElse(null);
            longLeg   = legs.stream().filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.BUY).findFirst().orElse(null);
            shortLeg2 = null;
            longLeg2  = null;
        }

        return new TradeCardDto(
                trade.getId(), trade.getStrategy(), trade.getSpreadDirection(),
                trade.getExpiryDate(), trade.getDte(),
                shortLeg, longLeg, shortLeg2, longLeg2,
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

    /**
     * Replaces legs and summary on the trade entity with the user's manually chosen values.
     * The two override hard rules (PoP≥50%, loss≤1.5%) were already enforced by /calculate-override.
     */
    private void applyManualOverride(TradeEntity trade, TradeConfirmRequestDto.OverrideParams ov) {
        boolean isIc = ov.ceShortStrike() != null && ov.ceLongStrike() != null;

        TradeLegDto peShortLeg = new TradeLegDto(OptionType.PE, ov.peShortStrike(), ov.peShortLtp(),
                LegAction.SELL, null, null, ov.peShortInstrumentKey());
        TradeLegDto peLongLeg = new TradeLegDto(OptionType.PE, ov.peLongStrike(), ov.peLongLtp(),
                LegAction.BUY, null, null, ov.peLongInstrumentKey());

        List<TradeLegDto> newLegs;
        if (isIc) {
            TradeLegDto ceShortLeg = new TradeLegDto(OptionType.CE, ov.ceShortStrike(), ov.ceShortLtp(),
                    LegAction.SELL, null, null, ov.ceShortInstrumentKey());
            TradeLegDto ceLongLeg = new TradeLegDto(OptionType.CE, ov.ceLongStrike(), ov.ceLongLtp(),
                    LegAction.BUY, null, null, ov.ceLongInstrumentKey());
            newLegs = List.of(peShortLeg, peLongLeg, ceShortLeg, ceLongLeg);
        } else {
            newLegs = List.of(peShortLeg, peLongLeg);
        }

        TradeSummary existingSummary = jsonUtil.fromJson(trade.getSummary(), TradeSummary.class);
        TradeSummary updatedSummary = new TradeSummary(
                ov.netPremiumPerUnit(), ov.lots(), existingSummary.lotSize(),
                ov.maxProfitTotal(), ov.theoreticalMaxLossTotal(), ov.realExpectedLossTotal(),
                ov.pop().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP),
                existingSummary.popp(), existingSummary.popGap(),
                ov.roc(), existingSummary.rocAnnualised(), existingSummary.netDelta()
        );

        trade.setLegs(jsonUtil.toJson(newLegs));
        trade.setSummary(jsonUtil.toJson(updatedSummary));

        // Compute thresholds from override strikes so monitor_config seeding gets correct values.
        BigDecimal maxLoss = ov.theoreticalMaxLossTotal() != null ? ov.theoreticalMaxLossTotal() : BigDecimal.ZERO;
        BigDecimal t2Loss = maxLoss.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP);
        MonitorThresholdsDto overrideThresholds;
        if (isIc) {
            BigDecimal peShortBd = BigDecimal.valueOf(ov.peShortStrike());
            BigDecimal ceShortBd = BigDecimal.valueOf(ov.ceShortStrike());
            overrideThresholds = MonitorThresholdsDto.ironCondor(
                    peShortBd.add(BigDecimal.valueOf(100)),
                    peShortBd.add(BigDecimal.valueOf(50)),
                    peShortBd,
                    ceShortBd.subtract(BigDecimal.valueOf(100)),
                    ceShortBd.subtract(BigDecimal.valueOf(50)),
                    ceShortBd,
                    t2Loss, maxLoss);
        } else {
            boolean isCeShort = trade.getStrategy() == Strategy.BEAR_CALL_SPREAD;
            BigDecimal shortStrikeBd = BigDecimal.valueOf(ov.peShortStrike());
            BigDecimal t1 = isCeShort
                    ? shortStrikeBd.subtract(BigDecimal.valueOf(100))
                    : shortStrikeBd.add(BigDecimal.valueOf(100));
            BigDecimal t2 = isCeShort
                    ? shortStrikeBd.subtract(BigDecimal.valueOf(50))
                    : shortStrikeBd.add(BigDecimal.valueOf(50));
            overrideThresholds = MonitorThresholdsDto.twoLeg(t1, t2, shortStrikeBd, t2Loss, maxLoss);
        }
        trade.setThresholds(jsonUtil.toJson(overrideThresholds));

        log.info("override.applied.to.trade",
                kv("tradeId", trade.getId()),
                kv("peShortStrike", ov.peShortStrike()),
                kv("peLongStrike", ov.peLongStrike()),
                kv("ceShortStrike", ov.ceShortStrike()),
                kv("lots", ov.lots()),
                kv("netPremium", ov.netPremiumPerUnit()),
                kv("pop", ov.pop()),
                kv("roc", ov.roc()));
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

    /**
     * Validates and persists user-supplied T1/T2/T3 Nifty level overrides.
     *
     * Validation rules:
     *  - For PE short (Bull Put Spread): T3 ≥ PE short strike — ensures PoP at exit ≥ 50%.
     *    Setting T3 below the short strike means the exit is triggered only after the put is ITM.
     *  - For CE short (Bear Call Spread): T3 ≤ CE short strike — same logic in reverse.
     *  - Ordering: T1 > T2 > T3 for PE short (Nifty falling triggers each level).
     *              T1 < T2 < T3 for CE short (Nifty rising triggers each level).
     *  - Iron Condor: override not supported (use default algorithm-computed thresholds).
     *
     * Only non-null supplied values are replaced; null means keep the existing value.
     */
    private void applyThresholdOverride(TradeEntity trade,
                                         TradeConfirmRequestDto.OverrideThresholds ov) {
        boolean isIronCondor = trade.getStrategy() == Strategy.IRON_CONDOR
                || trade.getStrategy() == Strategy.WIDE_IRON_CONDOR;
        if (isIronCondor) {
            log.warn("confirm.threshold.override.ic_not_supported tradeId={} — IC threshold override ignored",
                    trade.getId());
            return;
        }

        MonitorThresholdsDto existing = jsonUtil.fromJson(trade.getThresholds(), MonitorThresholdsDto.class);

        // Read the short leg to determine PE/CE direction for validation
        List<TradeLegDto> legs = jsonUtil.fromJson(trade.getLegs(), new TypeReference<>() {});
        TradeLegDto shortLeg = legs.stream()
                .filter(l -> l.action() == com.the3Cgrp.zupptrade.shared.enums.LegAction.SELL)
                .findFirst().orElse(null);

        if (shortLeg != null && ov.t3ExitNiftyLevel() != null) {
            boolean isPeShort = shortLeg.optionType() == OptionType.PE;
            int shortStrike   = shortLeg.strike();
            if (isPeShort && ov.t3ExitNiftyLevel() < shortStrike) {
                throw new IllegalArgumentException(
                        "T3 exit level (" + ov.t3ExitNiftyLevel() + ") cannot be below the PE short strike ("
                        + shortStrike + "). At that level PoP < 50%.");
            }
            if (!isPeShort && ov.t3ExitNiftyLevel() > shortStrike) {
                throw new IllegalArgumentException(
                        "T3 exit level (" + ov.t3ExitNiftyLevel() + ") cannot be above the CE short strike ("
                        + shortStrike + "). At that level PoP < 50%.");
            }
        }

        // Resolve effective values (override if non-null, else keep existing)
        int t1 = ov.t1WatchNiftyLevel()    != null ? ov.t1WatchNiftyLevel()    : existing.t1WatchNiftyLevel().intValue();
        int t2 = ov.t2ReadjustNiftyLevel() != null ? ov.t2ReadjustNiftyLevel() : existing.t2ReadjustNiftyLevel().intValue();
        int t3 = ov.t3ExitNiftyLevel()     != null ? ov.t3ExitNiftyLevel()     : existing.t3ExitNiftyLevel().intValue();

        // Validate ordering — direction depends on option type
        boolean isPeShort = shortLeg == null || shortLeg.optionType() == OptionType.PE;
        if (isPeShort) {
            // Nifty falling toward put short strike: T1 (watch first) > T2 > T3
            if (t1 <= t2) throw new IllegalArgumentException(
                    "T1 watch level (" + t1 + ") must be higher than T2 readjust level (" + t2 + ")");
            if (t2 <= t3) throw new IllegalArgumentException(
                    "T2 readjust level (" + t2 + ") must be higher than T3 exit level (" + t3 + ")");
        } else {
            // Nifty rising toward call short strike: T1 (watch first) < T2 < T3
            if (t1 >= t2) throw new IllegalArgumentException(
                    "T1 watch level (" + t1 + ") must be lower than T2 readjust level (" + t2 + ")");
            if (t2 >= t3) throw new IllegalArgumentException(
                    "T2 readjust level (" + t2 + ") must be lower than T3 exit level (" + t3 + ")");
        }

        MonitorThresholdsDto updated = MonitorThresholdsDto.twoLeg(
                BigDecimal.valueOf(t1), BigDecimal.valueOf(t2), BigDecimal.valueOf(t3),
                existing.t2LossThreshold(), existing.t3LossThreshold());

        trade.setThresholds(jsonUtil.toJson(updated));

        log.info("confirm.threshold.override.applied",
                kv("tradeId", trade.getId()), kv("t1", t1), kv("t2", t2), kv("t3", t3));
    }
}
