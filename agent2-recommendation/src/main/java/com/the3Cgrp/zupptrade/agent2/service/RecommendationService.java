package com.the3Cgrp.zupptrade.agent2.service;

import tools.jackson.core.type.TypeReference;
import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
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
import com.the3Cgrp.zupptrade.agent2.explain.RecommendationRationale;
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
import com.the3Cgrp.zupptrade.shared.calc.CreditLadderCalculator;
import com.the3Cgrp.zupptrade.shared.dto.*;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import com.the3Cgrp.zupptrade.shared.constants.TradingConstants;
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
    private final TradingConfig config;
    private final Clock clock;
    private final com.the3Cgrp.zupptrade.core.security.OwnershipGuard guard;

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
                                  TradeLedgerService ledger,
                                  TradingConfig config,
                                  Clock clock,
                                  com.the3Cgrp.zupptrade.core.security.OwnershipGuard guard) {
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
        this.config = config;
        this.clock = clock;
        this.guard = guard;
    }

    @Transactional
    public TradeCardDto recommend(RecommendRequestDto request) {
        // Phase 5: a user may only create a trade under their own profile (admin may act for any). 401/403.
        guard.requireOwner(request.userProfileId());

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

        int dte = (int) ChronoUnit.DAYS.between(LocalDate.now(clock), signal.getExpiryDate());

        // Expiry-day guard: never open a fresh weekly trade with 0 days left. The expected-move and
        // Black-Scholes maths are degenerate at t=0 (zero boundary, binary probabilities) and the
        // gamma/volatility risk near expiry is extreme. Reject cleanly with a clear message rather
        // than build a meaningless trade card (which also caused the divide-by-zero in Layer 5).
        if (dte <= 0) {
            RecommendationContext expiryCtx = new RecommendationContext();
            expiryCtx.setSignal(signal);
            expiryCtx.setUserProfile(userProfile);
            expiryCtx.setLotSize(lotSize);
            expiryCtx.setSpot(spot);
            expiryCtx.setVix(vix);
            expiryCtx.setHistoricalVolatility(BigDecimal.ZERO);
            expiryCtx.setExpiryDate(signal.getExpiryDate());
            expiryCtx.setDte(dte);
            expiryCtx.setStrategy(Strategy.NO_TRADE);
            // Keep ≤ 100 chars — this is persisted to trades.close_reason (VARCHAR(100)).
            expiryCtx.setSkipReason("No trade — expiry day (DTE=0): excessive volatility & gamma risk. Wait for next expiry.");
            log.warn("recommendation.blocked reason=EXPIRY_DAY dte={} expiry={} spot={} vix={}",
                    dte, signal.getExpiryDate(), spot, vix);
            return handleNoTrade(expiryCtx, signal, userProfile);
        }

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
        ctx.setHardGateEnabled(config.isHardGateEnabled());

        applyUserWeightRecomposition(ctx, signal, userProfile);

        engine.execute(ctx);
        // StrategySelector always picks a real strategy; SKIP/NO_TRADE decisions are marked
        // as ctx.skipDecision=true and routed to REJECTED (prod) or PENDING_CONFIRM (testing mode).

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
            String rejectReason = ctx.isSkipDecision() ? "SKIP_DECISION" : "GATE_FAILURE";
            ledger.record(trade.getId(), LedgerEventType.TRADE_REJECTED,
                    new TradeRejectedPayload(rejectReason, trade.getCloseReason()),
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

        // Phase 5: a user may only confirm their own trade (admin may confirm any). 401/403.
        guard.requireOwner(trade.getUserProfile() != null ? trade.getUserProfile().getId() : null);

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
                    // T1/T2/T3 exit levels are algorithm-locked (70/64/57-style ladder scaled to entry
                    // PoP, recomputed live by Agent 3). User-supplied threshold overrides are ignored.
                    if (request.overrideThresholds() != null) {
                        log.info("confirm.threshold.override.ignored tradeId={} — exit ladder is algorithm-locked",
                                trade.getId());
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
                        originalSummary != null ? originalSummary.pop() : BigDecimal.ZERO,   // pop is already a percentage (0–100)
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
        int dte = (int) ChronoUnit.DAYS.between(LocalDate.now(clock), trade.getExpiryDate());
        BigDecimal capital = trade.getUserProfile().getCapital();
        boolean isIc = req.ceShortStrike() != null && req.ceLongStrike() != null;
        // Primary pair option type — CALL spreads (bear/bull call) resolve against the calls
        // chain, not puts. Null defaults to PE for backward compatibility (put spreads / IC).
        OptionType primaryType = req.optionType() != null ? req.optionType() : OptionType.PE;

        // Fetch live option chain for current expiry
        OptionChainData chain = optionChainClient.fetch(trade.getExpiryDate());
        List<StrikeData> primaryStrikes = (primaryType == OptionType.CE) ? chain.calls() : chain.puts();

        // Resolve LTP and IV for each requested strike (primary pair from calls or puts per type)
        StrikeData peShortData = findStrike(primaryStrikes, req.peShortStrike(), primaryType + " short");
        StrikeData peLongData  = findStrike(primaryStrikes, req.peLongStrike(),  primaryType + " long");

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

        // Black-Scholes PoP on the primary short strike, using its actual option type
        // (short PUT → N(d2); short CALL → N(-d2)). A bear call spread was previously
        // mis-priced as a put here, tripping the PoP floor and blocking placement.
        BigDecimal iv = peShortData.iv() != null ? peShortData.iv() : chain.spot().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal popRaw = blackScholes.calculatePop(chain.spot(), BigDecimal.valueOf(req.peShortStrike()), iv, dte, RISK_FREE_RATE, primaryType);
        BigDecimal pop = popRaw.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);

        // Two-rule hard blocks — bypassed in testing mode (hardGateEnabled=false)
        boolean popBlocked  = config.isHardGateEnabled() && pop.compareTo(POP_HARD_FLOOR) < 0;
        // Max-loss guardrail honors the user's profile limit (max_loss_pct, e.g. 2.00 = 2%),
        // not a hardcoded 1.5%. Editing the profile now changes what the override panel allows.
        BigDecimal maxLossPctLimit = trade.getUserProfile().getMaxLossPct();
        BigDecimal maxLossBudget = capital.multiply(maxLossPctLimit).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        boolean lossBlocked = config.isHardGateEnabled() && realExpectedLossTotal.compareTo(maxLossBudget) > 0;

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
                kv("lossBlocked", lossBlocked),
                kv("maxLossPctLimit", maxLossPctLimit));

        return new CalculateOverrideResultDto(
                peShortLtp, peLongLtp, ceShortLtp, ceLongLtp,
                peShortData.instrumentKey(), peLongData.instrumentKey(),
                isIc ? ceShortData.instrumentKey() : null,
                isIc ? ceLongData.instrumentKey()  : null,
                netPremiumPerUnit, pop,
                maxProfitTotal, theoreticalMaxLossTotal, realExpectedLossTotal, roc,
                popBlocked, lossBlocked, !config.isHardGateEnabled(), maxLossPctLimit);
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
        int dte = (int) ChronoUnit.DAYS.between(LocalDate.now(clock), trade.getExpiryDate());

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
            // No live option chain here, so use spec-defined fixed buffers (§9: T1=150, T2=75).
            BigDecimal peShortBd = BigDecimal.valueOf(peShort.strike());
            BigDecimal ceShortBd = BigDecimal.valueOf(ceShort.strike());
            BigDecimal icMaxLoss = summary.theoreticalMaxLossTotal() != null
                    && summary.theoreticalMaxLossTotal().compareTo(BigDecimal.ZERO) > 0
                    ? summary.theoreticalMaxLossTotal() : actualMaxLoss;
            MonitorThresholdsDto icThresholds = MonitorThresholdsDto.ironCondor(
                    peShortBd.add(BigDecimal.valueOf(150)),      // T1 watch: Nifty 150 above PE short
                    peShortBd.add(BigDecimal.valueOf(75)),       // T2 readjust: Nifty 75 above PE short
                    peShortBd,
                    ceShortBd.subtract(BigDecimal.valueOf(150)), // T1 watch: Nifty 150 below CE short
                    ceShortBd.subtract(BigDecimal.valueOf(75)),  // T2 readjust: Nifty 75 below CE short
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

        // PoP / PoPP / gap on the card are PERCENTAGES (0–100) — the UI and gates use %, not the
        // raw 0–1 leg probabilities (Upstox pop is the BUYER's P(ITM)). Direction-aware:
        //   Credit: seller PoP = (1 − short buyer-pop)×100 (matches Gate G1); PoPP = (1 − long buyer-pop)×100.
        //   Debit:  directional — long-leg ITM prob as PoP, short strike ITM prob as the PoPP boundary.
        BigDecimal shortBuyerPop = ctx.getShortLeg().pop() != null ? ctx.getShortLeg().pop() : BigDecimal.ZERO;
        BigDecimal longBuyerPop  = ctx.getLongLeg().pop()  != null ? ctx.getLongLeg().pop()  : BigDecimal.ZERO;
        boolean isIronCondor = ctx.getShortLeg2() != null && ctx.getLongLeg2() != null;
        BigDecimal popPct, poppPct;
        if (direction == SpreadDirection.DEBIT) {
            popPct  = longBuyerPop.multiply(HUNDRED);
            poppPct = shortBuyerPop.multiply(HUNDRED);
        } else if (isIronCondor) {
            // DISPLAY-ONLY combined PoP. Gates (G1/G3) and Agent 3 monitoring stay per-side.
            // A condor keeps its premium only if spot finishes BETWEEN both shorts. "Spot below the
            // PE short" and "spot above the CE short" are mutually exclusive tails, so they ADD:
            //   PoP = 1 − P(PE ITM) − P(CE ITM). See ironCondorDisplayPopPct().
            BigDecimal ceBuyerPop = ctx.getShortLeg2().pop() != null ? ctx.getShortLeg2().pop() : BigDecimal.ZERO;
            popPct  = ironCondorDisplayPopPct(shortBuyerPop, ceBuyerPop);
            poppPct = popPct;   // single combined headline — no per-leg gap on the IC card
        } else {
            popPct  = BigDecimal.ONE.subtract(shortBuyerPop).multiply(HUNDRED);
            poppPct = BigDecimal.ONE.subtract(longBuyerPop).multiply(HUNDRED);
        }
        popPct  = popPct.setScale(2, RoundingMode.HALF_UP);
        poppPct = poppPct.setScale(2, RoundingMode.HALF_UP);
        BigDecimal popGapPct = popPct.subtract(poppPct).abs().setScale(2, RoundingMode.HALF_UP);

        // Layer 5 (PositionSizer) fields are null when engine exits early on HARD_GATE_FAILURE
        TradeSummary summary = new TradeSummary(
                netPremium, ctx.getLots(), ctx.getLotSize(),
                orZero(ctx.getMaxProfitTotal()), orZero(ctx.getTheoreticalMaxLossTotal()), orZero(ctx.getRealExpectedLossTotal()),
                popPct, poppPct, popGapPct,
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
                ctx.getExpectedMove(), ctx.getOneFourSdBoundary(),
                ctx.getRelaxedGate1PopPct() != null   // readjustment re-entry → EXIT-only downstream
        );

        MonitorThresholdsDto thresholds = buildThresholds(ctx);

        String rationale = buildRationale(ctx);

        TradeEntity trade = new TradeEntity();
        // Status: testing mode always PENDING_CONFIRM; production rejects skip decisions and gate failures.
        TradeStatus status;
        String closeReason = null;
        if (!ctx.isHardGateEnabled()) {
            status = TradeStatus.PENDING_CONFIRM;
        } else if (ctx.isSkipDecision()) {
            status = TradeStatus.REJECTED;
            closeReason = "SKIP_DECISION:" + ctx.getSkipReason();
        } else if (!ctx.isAllHardGatesPassed()) {
            status = TradeStatus.REJECTED;
            closeReason = resolveSkipReason(ctx);
        } else {
            status = TradeStatus.PENDING_CONFIRM;
        }

        trade.setAgent1Signal(signal);
        trade.setUserProfile(userProfile);
        trade.setStatus(status);
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
        trade.setCloseReason(closeReason);

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
            // Iron Condor: bilateral credit ladder — PE short (down) + CE short (up), each scaled to
            // its own entry seller PoP (70/64/57-style fractions). Levels are recomputed by Agent 3
            // every cycle from live IV/DTE; ±125/100/75 floors guarantee ordering and a ≥75pt exit.
            int peStrike = ctx.getShortLeg().strike();
            int ceStrike = ctx.getShortLeg2().strike();
            BigDecimal peIv = lookupIv(ctx.getOptionChainData(), peStrike, OptionType.PE);
            BigDecimal ceIv = lookupIv(ctx.getOptionChainData(), ceStrike, OptionType.CE);
            BigDecimal peEntryPop = sellerEntryPop(ctx.getShortLeg());
            BigDecimal ceEntryPop = sellerEntryPop(ctx.getShortLeg2());
            int dte = ctx.getDte();
            CreditLadderCalculator.Ladder put  = CreditLadderCalculator.compute(peStrike, OptionType.PE, peEntryPop, peIv, dte, RISK_FREE_RATE);
            CreditLadderCalculator.Ladder call = CreditLadderCalculator.compute(ceStrike, OptionType.CE, ceEntryPop, ceIv, dte, RISK_FREE_RATE);
            return MonitorThresholdsDto.ironCondorCredit(
                    put.t1Nifty(),  put.t2Nifty(),  put.t3Nifty(),
                    call.t1Nifty(), call.t2Nifty(), call.t3Nifty(),
                    t2Loss, t3Loss, peEntryPop, ceEntryPop);
        }

        SpreadDirection direction = ctx.getSpreadDirection();

        if (direction == SpreadDirection.CREDIT) {
            // Bull Put (PE short) / Bear Call (CE short): 70/64/57-style ladder scaled to entry PoP,
            // recomputed live by Agent 3. PE levels sit above the short strike, CE below; ±75 T3 floor.
            int strike = ctx.getShortLeg().strike();
            OptionType shortType = ctx.getShortLeg().optionType();
            BigDecimal shortIv = lookupIv(ctx.getOptionChainData(), strike, shortType);
            BigDecimal entryPop = sellerEntryPop(ctx.getShortLeg());
            CreditLadderCalculator.Ladder l = CreditLadderCalculator.compute(
                    strike, shortType, entryPop, shortIv, ctx.getDte(), RISK_FREE_RATE);
            return MonitorThresholdsDto.twoLegCredit(
                    l.t1Nifty(), l.t2Nifty(), l.t3Nifty(), t2Loss, t3Loss, entryPop);
        } else {
            // Debit spread (Bull Call / Bear Put): record entry directional PoP (at breakeven) and
            // PoPP (at the short strike) so Agent 3 monitors via PoP (disaster stop) + the PoP−PoPP
            // gap (give-back lock). Direction-correct for both — bull call profits up, bear put down.
            TradeLegDto longLeg  = ctx.getLongLeg();
            TradeLegDto shortLeg = ctx.getShortLeg();
            boolean isBullCall = longLeg.optionType() == OptionType.CE;
            BigDecimal netDebit    = longLeg.ltp().subtract(shortLeg.ltp());
            BigDecimal longStrike  = BigDecimal.valueOf(longLeg.strike());
            BigDecimal shortStrike = BigDecimal.valueOf(shortLeg.strike());
            BigDecimal breakeven   = isBullCall ? longStrike.add(netDebit) : longStrike.subtract(netDebit);

            BigDecimal iv = lookupIv(ctx.getOptionChainData(), longLeg.strike(), longLeg.optionType());
            if (iv == null || iv.signum() <= 0) {
                iv = (ctx.getVix() != null && ctx.getVix().signum() > 0)
                        ? ctx.getVix().divide(HUNDRED, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            }
            int dte = ctx.getDte();
            // directionalPop: probability spot finishes on the PROFIT side of a level.
            //   bull call (up):  P(spot > level) = N(d2)  → calculatePop(..., PE)
            //   bear put (down): P(spot < level) = N(-d2) → calculatePop(..., CE)
            OptionType popType = isBullCall ? OptionType.PE : OptionType.CE;
            BigDecimal entryPop  = blackScholes.calculatePop(ctx.getSpot(), breakeven,   iv, dte, RISK_FREE_RATE, popType);
            BigDecimal entryPopp = blackScholes.calculatePop(ctx.getSpot(), shortStrike, iv, dte, RISK_FREE_RATE, popType);
            return MonitorThresholdsDto.debitSpread(breakeven, shortStrike, longStrike, t2Loss, t3Loss, entryPop, entryPopp);
        }
    }

    private static final BigDecimal DEFAULT_ENTRY_POP = new BigDecimal("0.80");

    /**
     * Iron Condor headline PoP for the trade card — DISPLAY ONLY. Gate validation (per-side G1/G3)
     * and Agent 3 monitoring (per-side thresholds) are unaffected by this figure.
     * <p>
     * A condor keeps its full premium only if spot finishes BETWEEN the two short strikes. The two
     * ways to lose — spot below the PE short, or spot above the CE short — are mutually exclusive
     * (spot cannot be both), so those disjoint tail probabilities ADD:
     * <pre>PoP = 1 − P(spot &lt; PE short) − P(spot &gt; CE short) = 1 − peBuyerPop − ceBuyerPop</pre>
     * {@code peBuyerPop}/{@code ceBuyerPop} are the Upstox buyer P(ITM) of each short leg, i.e. each
     * side's breach probability. Result is a percentage clamped to [0, 100].
     */
    static BigDecimal ironCondorDisplayPopPct(BigDecimal peBuyerPop, BigDecimal ceBuyerPop) {
        BigDecimal pe = peBuyerPop != null ? peBuyerPop : BigDecimal.ZERO;
        BigDecimal ce = ceBuyerPop != null ? ceBuyerPop : BigDecimal.ZERO;
        BigDecimal pop = BigDecimal.ONE.subtract(pe).subtract(ce).multiply(HUNDRED);
        if (pop.signum() < 0) pop = BigDecimal.ZERO;
        if (pop.compareTo(HUNDRED) > 0) pop = HUNDRED;
        return pop.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Seller PoP at entry for a credit short leg = 1 − buyer PoP (Upstox pop is buyer-side P(ITM)).
     * Falls back to the 80% gate default when the leg has no PoP or the value is outside (0,1).
     */
    private BigDecimal sellerEntryPop(TradeLegDto shortLeg) {
        if (shortLeg == null || shortLeg.pop() == null) return DEFAULT_ENTRY_POP;
        BigDecimal seller = BigDecimal.ONE.subtract(shortLeg.pop());
        if (seller.signum() <= 0 || seller.compareTo(BigDecimal.ONE) >= 0) return DEFAULT_ENTRY_POP;
        return seller;
    }

    /**
     * Entry seller PoP from a manual override's PoP percentage (already seller-side from
     * /calculate-override, e.g. 85.00 → 0.85). Falls back to the 80% default when out of (0,1).
     */
    private BigDecimal overrideEntryPop(BigDecimal popPct) {
        if (popPct == null) return DEFAULT_ENTRY_POP;
        BigDecimal p = popPct.divide(HUNDRED, 4, RoundingMode.HALF_UP);
        if (p.signum() <= 0 || p.compareTo(BigDecimal.ONE) >= 0) return DEFAULT_ENTRY_POP;
        return p;
    }

    /** Looks up the IV for a specific strike from the option chain. Returns null if not found. */
    private BigDecimal lookupIv(OptionChainData chain, int strike, OptionType optionType) {
        if (chain == null) return null;
        List<StrikeData> strikes = (optionType == OptionType.PE) ? chain.puts() : chain.calls();
        return strikes.stream()
                .filter(sd -> sd.strike() == strike)
                .findFirst()
                .map(StrikeData::iv)
                .orElse(null);
    }

    private String buildRationale(RecommendationContext ctx) {
        // Plain-English "why", built via the shared explain helpers (core-module) plus agent2's
        // own strategy/gate/leg vocabulary. Shown behind the recommendation-card help icon.
        return RecommendationRationale.build(ctx);
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
        // Prefer the human-readable skip reason (e.g. the expiry-day message); fall back to the enum name.
        String reason = (ctx.getSkipReason() != null && !ctx.getSkipReason().isBlank())
                ? ctx.getSkipReason()
                : ctx.getStrategy().name();

        TradeSummary summary = new TradeSummary(
                BigDecimal.ZERO, 0, ctx.getLotSize(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        MarketContext marketContext = new MarketContext(
                ctx.getSpot(), ctx.getVix(), BigDecimal.ZERO,
                ctx.getHistoricalVolatility(), BigDecimal.ZERO,
                ctx.getIvRegime(), signal.getVixRegime(),
                null, null, false);   // no-trade cards are never monitored

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
                trade.getGeneratedAt(), trade.getValidUntil(), trade.getStatus(),
                !config.isHardGateEnabled(), ctx.isSkipDecision(), ctx.getSkipReason()
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
                trade.getGeneratedAt(), trade.getValidUntil(), trade.getStatus(),
                !config.isHardGateEnabled(), false, null  // skipDecision/skipReason not available from entity
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
        // Primary pair option type — CALL spreads build CE legs, not PE. Null → PE (back-compat).
        OptionType primaryType = ov.optionType() != null ? ov.optionType() : OptionType.PE;

        TradeLegDto peShortLeg = new TradeLegDto(primaryType, ov.peShortStrike(), ov.peShortLtp(),
                LegAction.SELL, null, null, ov.peShortInstrumentKey());
        TradeLegDto peLongLeg = new TradeLegDto(primaryType, ov.peLongStrike(), ov.peLongLtp(),
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
                ov.pop(),   // already a percentage (0–100) from /calculate-override — store as-is
                existingSummary.popp(), existingSummary.popGap(),
                ov.roc(), existingSummary.rocAnnualised(), existingSummary.netDelta()
        );

        trade.setLegs(jsonUtil.toJson(newLegs));
        trade.setSummary(jsonUtil.toJson(updatedSummary));

        // Exit ladder is algorithm-locked even for hand-built overrides: derive the 70/64/57-style
        // ladder from the override's OWN entry seller PoP (ov.pop()), and store entryPop so Agent 3
        // recomputes the Nifty levels live each cycle — identical to algorithm-recommended trades.
        // Initial levels use the ±125/100/75 floors here (no live IV in the confirm path); Agent 3
        // replaces them from live IV on its first cycle. Users cannot adjust these levels.
        BigDecimal maxLoss = ov.theoreticalMaxLossTotal() != null ? ov.theoreticalMaxLossTotal() : BigDecimal.ZERO;
        BigDecimal t2Loss = maxLoss.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal entryPop = overrideEntryPop(ov.pop());
        int dte = trade.getExpiryDate() != null
                ? (int) ChronoUnit.DAYS.between(LocalDate.now(clock), trade.getExpiryDate()) : 0;
        MonitorThresholdsDto overrideThresholds;
        if (isIc) {
            // Only the primary (PE) PoP is supplied for a hand-built IC — reuse it for both sides.
            CreditLadderCalculator.Ladder down = CreditLadderCalculator.compute(
                    ov.peShortStrike(), OptionType.PE, entryPop, null, dte, RISK_FREE_RATE);
            CreditLadderCalculator.Ladder up = CreditLadderCalculator.compute(
                    ov.ceShortStrike(), OptionType.CE, entryPop, null, dte, RISK_FREE_RATE);
            overrideThresholds = MonitorThresholdsDto.ironCondorCredit(
                    down.t1Nifty(), down.t2Nifty(), down.t3Nifty(),
                    up.t1Nifty(),   up.t2Nifty(),   up.t3Nifty(),
                    t2Loss, maxLoss, entryPop, entryPop);
        } else {
            CreditLadderCalculator.Ladder l = CreditLadderCalculator.compute(
                    ov.peShortStrike(), primaryType, entryPop, null, dte, RISK_FREE_RATE);
            overrideThresholds = MonitorThresholdsDto.twoLegCredit(
                    l.t1Nifty(), l.t2Nifty(), l.t3Nifty(), t2Loss, maxLoss, entryPop);
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

    /**
     * Recomputes the Agent 1 composite score using the user's custom tier weights.
     * Sets effectiveBias and effectiveStrength on the context so Layer 1 StrategySelector
     * uses user-adjusted signal values rather than the market-wide signal defaults.
     *
     * Falls back to signal values (no-op) if score_breakdown is absent or unparseable.
     */
    private void applyUserWeightRecomposition(RecommendationContext ctx,
                                               Agent1SignalEntity signal,
                                               UserProfileEntity userProfile) {
        String scoreBreakdownJson = signal.getScoreBreakdown();
        if (scoreBreakdownJson == null || scoreBreakdownJson.isBlank()) {
            ctx.setWeightsSource("SYSTEM_DEFAULT");
            log.warn("agent2.weights.recomp.skipped reason=score_breakdown_absent signalId={}", signal.getId());
            return;
        }

        try {
            // Agent 1 writes score_breakdown as a JSON OBJECT keyed by tier name:
            //   { "TIER_1A_PRICE_STRUCTURE": { "average": .., "weight": .., "contribution": .. }, ... }
            // It was previously parsed as a List, which always threw → this method silently fell back
            // to SYSTEM_DEFAULT and user tier weights never took effect. Parse the correct shape.
            TypeReference<Map<String, Map<String, Object>>> mapType = new TypeReference<>() {};
            Map<String, Map<String, Object>> tiers = jsonUtil.fromJson(scoreBreakdownJson, mapType);

            Map<String, BigDecimal> tierWeightMap = Map.of(
                    "TIER_1A_PRICE_STRUCTURE",    userProfile.getTier1aWeight(),
                    "TIER_1B_TECHNICAL",          userProfile.getTier1bWeight(),
                    "TIER_2_INSTITUTIONAL_FLOW",  userProfile.getTier2Weight(),
                    "TIER_3_VOLATILITY_MACRO",    userProfile.getTier3Weight(),
                    "TIER_4_COMMENTARY_SENTIMENT", userProfile.getTier4Weight()
            );

            BigDecimal composite = BigDecimal.ZERO;
            for (Map.Entry<String, Map<String, Object>> entry : tiers.entrySet()) {
                String tierName          = entry.getKey();
                Map<String, Object> tier = entry.getValue();
                if (tier == null) continue;
                Object avgObj = tier.get("average");
                if (avgObj == null) continue;

                BigDecimal average    = new BigDecimal(avgObj.toString());
                BigDecimal userWeight = tierWeightMap.getOrDefault(tierName, BigDecimal.ZERO);
                composite = composite.add(average.multiply(userWeight));
            }
            composite = composite.setScale(4, RoundingMode.HALF_UP);

            Bias     adjustedBias     = deriveEffectiveBias(composite);
            Strength adjustedStrength = deriveEffectiveStrength(composite);

            ctx.setEffectiveBias(adjustedBias);
            ctx.setEffectiveStrength(adjustedStrength);
            ctx.setWeightsSource("USER_OVERRIDE");

            log.info("agent2.weights.recomp.applied signalId={} signalBias={} signalStrength={} "
                            + "adjustedComposite={} adjustedBias={} adjustedStrength={}",
                    signal.getId(), signal.getBias(), signal.getStrength(),
                    composite, adjustedBias, adjustedStrength);

        } catch (Exception ex) {
            ctx.setWeightsSource("SYSTEM_DEFAULT");
            log.warn("agent2.weights.recomp.failed signalId={} reason={} — falling back to signal values",
                    signal.getId(), ex.getMessage());
        }
    }

    private static final BigDecimal BIAS_NEUTRAL_BAND = new BigDecimal("0.10");
    private static final BigDecimal BIAS_MILD         = new BigDecimal("0.25");
    private static final BigDecimal BIAS_EXTREME      = new BigDecimal("0.50");

    private Bias deriveEffectiveBias(BigDecimal composite) {
        if (composite.abs().compareTo(BIAS_NEUTRAL_BAND) <= 0) return Bias.NEUTRAL;
        return composite.compareTo(BigDecimal.ZERO) > 0 ? Bias.BULLISH : Bias.BEARISH;
    }

    private Strength deriveEffectiveStrength(BigDecimal composite) {
        BigDecimal abs = composite.abs();
        if (abs.compareTo(BIAS_EXTREME) > 0) return Strength.EXTREME;
        if (abs.compareTo(BIAS_MILD) > 0)    return Strength.MILD;
        return Strength.WEAK;
    }
}
