package com.the3Cgrp.zupptrade.agent2.service;

import com.the3Cgrp.zupptrade.agent2.client.Agent1ScoreClient;
import com.the3Cgrp.zupptrade.agent2.client.MarketDataClient;
import com.the3Cgrp.zupptrade.agent2.client.model.MarketSnapshot;
import com.the3Cgrp.zupptrade.agent2.config.FuturesConfig;
import com.the3Cgrp.zupptrade.agent2.domain.entity.FutureTradeLedgerEntity;
import com.the3Cgrp.zupptrade.shared.dto.Agent1SignalDto;
import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent2.engine.futures.FuturesPlanEngine;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.ArmPlan;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.FuturesPlanInputs;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.FuturesPlanResult;
import com.the3Cgrp.zupptrade.agent2.exception.MarketDataUnavailableException;
import com.the3Cgrp.zupptrade.agent2.repository.FutureTradeLedgerRepository;
import com.the3Cgrp.zupptrade.agent2.repository.ReferenceDataRepository;
import com.the3Cgrp.zupptrade.agent2.repository.UserProfileRepository;
import com.the3Cgrp.zupptrade.agent2.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient.UpstoxCandle;
import com.the3Cgrp.zupptrade.shared.dto.*;
import com.the3Cgrp.zupptrade.shared.enums.ConfirmAction;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import tools.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Agent 2 — futures trade-plan service. Builds the full multi-arm card (all four arms + every
 * calculation) so the end user chooses which trade to arm, and persists it to trade_future_ledger.
 *
 * All the decision maths lives in {@link FuturesPlanEngine}; this class does I/O only: load the
 * Agent 1 signal + user profile + Upstox daily candles, run the engine, apply the kill-switch,
 * persist, and map to the DTO.
 */
@Service
public class FuturesRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(FuturesRecommendationService.class);
    private static final String LOT_SIZE_KEY = "nifty.lot.size";
    private static final int COMPRESSION_SMA_WINDOW = 20;
    // Trading calendar is IST regardless of the JVM/clock zone — "today" and the commentary lookup
    // must both resolve to the same IST calendar day.
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<FuturePlanStatus> COMMITTED =
            List.of(FuturePlanStatus.ARMED, FuturePlanStatus.CONFIRMED, FuturePlanStatus.FILLED);

    private final Agent1ScoreClient agent1ScoreClient;
    private final FuturesCommentaryReader commentaryReader;
    private final UserProfileRepository userProfileRepository;
    private final ReferenceDataRepository referenceDataRepository;
    private final FutureTradeLedgerRepository ledgerRepository;
    private final UpstoxHistoricalDataClient historicalClient;
    private final MarketDataClient marketDataClient;
    private final FuturesPlanEngine engine;
    private final FuturesInstrumentResolver instrumentResolver;
    private final FuturesConfig config;
    private final JsonUtil jsonUtil;
    private final Clock clock;

    public FuturesRecommendationService(Agent1ScoreClient agent1ScoreClient,
                                        FuturesCommentaryReader commentaryReader,
                                        UserProfileRepository userProfileRepository,
                                        ReferenceDataRepository referenceDataRepository,
                                        FutureTradeLedgerRepository ledgerRepository,
                                        UpstoxHistoricalDataClient historicalClient,
                                        MarketDataClient marketDataClient,
                                        FuturesPlanEngine engine,
                                        FuturesInstrumentResolver instrumentResolver,
                                        FuturesConfig config,
                                        JsonUtil jsonUtil,
                                        Clock clock) {
        this.agent1ScoreClient = agent1ScoreClient;
        this.commentaryReader = commentaryReader;
        this.userProfileRepository = userProfileRepository;
        this.referenceDataRepository = referenceDataRepository;
        this.ledgerRepository = ledgerRepository;
        this.historicalClient = historicalClient;
        this.marketDataClient = marketDataClient;
        this.engine = engine;
        this.instrumentResolver = instrumentResolver;
        this.config = config;
        this.jsonUtil = jsonUtil;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ recommend

    @Transactional
    public FuturesPlanCardDto recommend(FuturesRecommendRequestDto request) {
        UserProfileEntity profile = userProfileRepository.findById(request.userProfileId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "User profile not found: " + request.userProfileId()));

        int lotSize = fetchLotSize();
        int runPhase = request.runPhase() != null ? request.runPhase() : 900;
        // Resolve "today" on the IST trading calendar so the commentary lookup is that day's row only.
        LocalDate tradeDate = LocalDate.ofInstant(clock.instant(), IST);

        // Futures commentary is MANDATORY and admin-submitted (table futures_daily_commentary).
        // We regenerate the Agent 1 signal from it so the commentary actually shapes bias/confidence.
        String commentary = commentaryReader.findCommentary(tradeDate)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Futures commentary required: no admin commentary submitted for " + tradeDate));
        Agent1SignalDto signal = agent1ScoreClient.score(commentary);

        List<UpstoxCandle> completed = fetchCompletedDailyCandles(tradeDate);
        if (completed.isEmpty()) {
            throw new MarketDataUnavailableException(
                    "No completed Nifty daily candles available to derive Camarilla levels");
        }
        UpstoxCandle prior = completed.get(0);
        List<BigDecimal> ranges = completed.stream()
                .map(c -> c.high().subtract(c.low()))
                .toList();
        BigDecimal prevDayRange = ranges.get(0);
        List<BigDecimal> last20 = ranges.subList(0, Math.min(COMPRESSION_SMA_WINDOW, ranges.size()));

        BigDecimal openPx = resolveOpenPx(prior.close());

        FuturesPlanInputs inputs = new FuturesPlanInputs(
                prior.high(), prior.low(), prior.close(), openPx,
                signal.bias(), signal.confidenceScore(),
                prevDayRange, last20, lotSize, profile.getCapital());

        FuturesPlanResult result = engine.plan(inputs, config);
        String instrumentKey = instrumentResolver.resolveCurrentMonthFut();

        boolean killSwitch = killSwitchTripped(profile.getId(), tradeDate);
        FuturePlanStatus status = (killSwitch || result.planNoTrade())
                ? FuturePlanStatus.NO_TRADE : FuturePlanStatus.PRIMED;
        String noTradeReason = killSwitch
                ? "Daily trade limit reached (kill-switch)"
                : result.planNoTradeReason();

        List<FuturesArmCardDto> armCards = toArmCards(result);
        FutureTradeLedgerEntity saved = persist(request, signal, profile, runPhase, tradeDate,
                instrumentKey, openPx, prior, result, armCards, status, noTradeReason);

        log.info("futures.recommend",
                kv("planId", saved.getId()), kv("planCode", saved.getPlanCode()),
                kv("bias", signal.bias()), kv("openZone", result.openZone()),
                kv("primaryArm", result.primaryArm()), kv("status", status),
                kv("compressed", result.compression().compressed()), kv("killSwitch", killSwitch));

        return toCard(saved, result.confidenceGate().passed(), armCards);
    }

    // ------------------------------------------------------------------ confirm

    @Transactional
    public FuturesPlanCardDto confirm(FuturesConfirmRequestDto request) {
        FutureTradeLedgerEntity plan = ledgerRepository.findById(request.planId())
                .orElseThrow(() -> new IllegalArgumentException("Futures plan not found: " + request.planId()));

        List<FuturesArmCardDto> arms = readArms(plan);

        if (request.action() == ConfirmAction.REJECT) {
            plan.setStatus(FuturePlanStatus.REJECTED);
            ledgerRepository.save(plan);
            return toCard(plan, gatePassed(plan), arms);
        }

        // APPROVE
        if (plan.getStatus() != FuturePlanStatus.PRIMED) {
            throw new IllegalStateException("Plan " + plan.getPlanCode()
                    + " cannot be approved in status " + plan.getStatus());
        }
        FutureArmType chosenType = request.selectedArm();
        if (chosenType == null) {
            throw new IllegalArgumentException("selectedArm is required to approve a futures plan");
        }
        FuturesArmCardDto chosen = arms.stream()
                .filter(a -> a.armType() == chosenType)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Arm not on plan: " + chosenType));
        if (chosen.status() == com.the3Cgrp.zupptrade.shared.enums.ArmCardStatus.BLOCKED) {
            throw new IllegalStateException("Cannot arm a blocked trade: " + chosen.blockedReason());
        }

        plan.setPrimaryArm(chosenType);
        plan.setEntryPrice(chosen.entry());
        plan.setStopPrice(chosen.stop());
        plan.setTargetPrice(chosen.target());
        plan.setRrPrimary(chosen.rrGross());
        plan.setRrAfterCost(chosen.rrAfterCost());
        if (request.overrideLots() != null) {
            plan.setSizing(jsonUtil.toJson(Map.of(
                    "lots", request.overrideLots(), "lotSize", chosen.lotSize(),
                    "overridden", true)));
        } else {
            plan.setSizing(jsonUtil.toJson(Map.of(
                    "lots", chosen.lots(), "lotSize", chosen.lotSize(),
                    "riskPerLot", chosen.riskPerLot(), "riskTotal", chosen.riskTotal(),
                    "marginEstimate", chosen.marginEstimate())));
        }
        plan.setStatus(FuturePlanStatus.ARMED);
        plan.setApprovedAt(OffsetDateTime.now(clock));
        ledgerRepository.save(plan);

        log.info("futures.confirm.armed", kv("planId", plan.getId()), kv("planCode", plan.getPlanCode()),
                kv("selectedArm", chosenType), kv("lots", request.overrideLots() != null
                        ? request.overrideLots() : chosen.lots()));

        return toCard(plan, gatePassed(plan), arms);
    }

    // ------------------------------------------------------------------ list (screen 2)

    /** Accepted futures plans for today — dormant (ARMED/BREAK_DETECTED) and active (CONFIRMED/FILLED). */
    @Transactional(readOnly = true)
    public List<FuturesPlanCardDto> listActive() {
        LocalDate today = LocalDate.now(clock);
        List<FuturePlanStatus> statuses = List.of(
                FuturePlanStatus.ARMED, FuturePlanStatus.BREAK_DETECTED,
                FuturePlanStatus.CONFIRMED, FuturePlanStatus.FILLED);
        return ledgerRepository.findByTradeDateAndStatusInOrderByCreatedAtDesc(today, statuses).stream()
                .map(e -> toCard(e, gatePassed(e), readArms(e)))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private List<UpstoxCandle> fetchCompletedDailyCandles(LocalDate today) {
        try {
            return historicalClient.fetchNiftyDailyCandles(config.getCompressionLookbackDays()).stream()
                    .filter(c -> c.date().isBefore(today)) // drop today's forming candle
                    .sorted(Comparator.comparing(UpstoxCandle::date).reversed())
                    .toList();
        } catch (Exception e) {
            log.warn("futures.candles.error error={}", e.getMessage(), e);
            return List.of();
        }
    }

    private BigDecimal resolveOpenPx(BigDecimal fallbackClose) {
        try {
            MarketSnapshot snap = marketDataClient.fetchSnapshot();
            if (snap != null && snap.spot() != null) {
                return snap.spot();
            }
        } catch (Exception e) {
            log.warn("futures.openpx.snapshot_failed fallback=priorClose error={}", e.getMessage());
        }
        return fallbackClose;
    }

    private boolean killSwitchTripped(UUID userProfileId, LocalDate tradeDate) {
        long committed = ledgerRepository
                .countByUserProfileIdAndTradeDateAndStatusIn(userProfileId, tradeDate, COMMITTED);
        return committed >= config.getMaxTradesPerDay();
    }

    private int fetchLotSize() {
        return referenceDataRepository.findById(LOT_SIZE_KEY)
                .filter(ref -> !ref.isExpired())
                .map(ref -> jsonUtil.fromJson(ref.getValue(), Map.class))
                .map(map -> ((Number) map.get("lotSize")).intValue())
                .orElseThrow(() -> new IllegalStateException(
                        "Nifty lot size not found in reference_data. Run startup data loader first."));
    }

    private String buildPlanCode(LocalDate tradeDate) {
        long seq = ledgerRepository.countByTradeDate(tradeDate) + 1;
        return String.format("FUT-%s-%03d",
                tradeDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), seq);
    }

    private List<FuturesArmCardDto> toArmCards(FuturesPlanResult result) {
        List<FuturesArmCardDto> cards = new ArrayList<>();
        for (ArmPlan ap : result.arms()) {
            cards.add(new FuturesArmCardDto(
                    ap.arm().type(), FuturesLabels.label(ap.arm().type()), ap.arm().direction(),
                    ap.status(), ap.blockedReason(),
                    ap.arm().entry(), ap.arm().stop(), ap.arm().target(),
                    ap.rr().riskPoints(), ap.rr().rewardPoints(), ap.rr().rrGross(),
                    ap.rr().rrAfterCost(), ap.rr().costPoints(),
                    ap.probabilityPct(),
                    ap.sizing().lots(), ap.sizing().lotSize(), ap.sizing().riskPerLot(),
                    ap.sizing().riskTotal(), ap.margin().marginEstimate(), ap.margin().notional()));
        }
        return cards;
    }

    private FutureTradeLedgerEntity persist(FuturesRecommendRequestDto request, Agent1SignalDto signal,
                                            UserProfileEntity profile, int runPhase, LocalDate tradeDate,
                                            String instrumentKey, BigDecimal openPx, UpstoxCandle prior,
                                            FuturesPlanResult result, List<FuturesArmCardDto> armCards,
                                            FuturePlanStatus status, String noTradeReason) {
        FutureTradeLedgerEntity e = new FutureTradeLedgerEntity();
        e.setPlanCode(buildPlanCode(tradeDate));
        e.setAgent1SignalId(signal.id());
        e.setUserProfileId(profile.getId());
        e.setRunPhase(runPhase);
        e.setInstrumentKey(instrumentKey);
        e.setTradeDate(tradeDate);
        e.setBias(signal.bias());
        e.setConfidenceScore(signal.confidenceScore());
        e.setConfidenceLabel(signal.confidence());
        e.setOpenZone(result.openZone());
        e.setPriorOhlc(jsonUtil.toJson(new FuturesPriorOhlcDto(
                prior.date(), prior.open(), prior.high(), prior.low(), prior.close())));
        e.setOpenPx(openPx);
        e.setCamarilla(jsonUtil.toJson(new FuturesCamarillaDto(
                result.camarilla().range(), result.camarilla().pivot(), result.camarilla().h3(),
                result.camarilla().h4(), result.camarilla().l3(), result.camarilla().l4())));
        e.setFourArms(jsonUtil.toJson(armCards));
        e.setCompressionRci(result.compression().rci());
        e.setGateResults(jsonUtil.toJson(Map.of(
                "confidenceGatePassed", result.confidenceGate().passed(),
                "minConfidence", result.confidenceGate().minConfidence(),
                "compressed", result.compression().compressed(),
                "compressionThreshold", result.compression().threshold())));
        e.setStatus(status);
        e.setNoTradeReason(noTradeReason);

        result.primary().ifPresent(p -> {
            e.setPrimaryArm(p.arm().type());
            e.setEntryPrice(p.arm().entry());
            e.setStopPrice(p.arm().stop());
            e.setTargetPrice(p.arm().target());
            e.setRrPrimary(p.rr().rrGross());
            e.setRrAfterCost(p.rr().rrAfterCost());
            e.setSizing(jsonUtil.toJson(Map.of(
                    "lots", p.sizing().lots(), "lotSize", p.sizing().lotSize(),
                    "riskPerLot", p.sizing().riskPerLot(), "riskTotal", p.sizing().riskTotal(),
                    "marginEstimate", p.margin().marginEstimate())));
        });

        return ledgerRepository.save(e);
    }

    private List<FuturesArmCardDto> readArms(FutureTradeLedgerEntity plan) {
        return jsonUtil.fromJson(plan.getFourArms(), new TypeReference<List<FuturesArmCardDto>>() {});
    }

    private boolean gatePassed(FutureTradeLedgerEntity plan) {
        if (plan.getGateResults() == null) return false;
        Map<?, ?> gates = jsonUtil.fromJson(plan.getGateResults(), Map.class);
        return Boolean.TRUE.equals(gates.get("confidenceGatePassed"));
    }

    private FuturesPlanCardDto toCard(FutureTradeLedgerEntity e, boolean confidenceGatePassed,
                                      List<FuturesArmCardDto> arms) {
        FuturesCamarillaDto levels = jsonUtil.fromJson(e.getCamarilla(), FuturesCamarillaDto.class);
        FuturesPriorOhlcDto ohlc = jsonUtil.fromJson(e.getPriorOhlc(), FuturesPriorOhlcDto.class);
        Map<?, ?> gates = e.getGateResults() != null
                ? jsonUtil.fromJson(e.getGateResults(), Map.class) : Map.of();

        BigDecimal minConfidence = toBigDecimal(gates.get("minConfidence"));
        BigDecimal compressionThreshold = toBigDecimal(gates.get("compressionThreshold"));
        boolean compressed = Boolean.TRUE.equals(gates.get("compressed"));
        BigDecimal roundTripCost = representativeCost(arms, e.getPrimaryArm());

        return new FuturesPlanCardDto(
                e.getId(), e.getPlanCode(), e.getStatus(), e.getTradeDate(), e.getRunPhase(),
                e.getInstrumentKey(), e.getBias(), e.getConfidenceScore(), e.getConfidenceLabel(),
                e.getOpenZone(), levels, ohlc, e.getOpenPx(),
                confidenceGatePassed, minConfidence, e.getCompressionRci(), compressionThreshold, compressed,
                roundTripCost, e.getPrimaryArm(), e.getNoTradeReason(), arms, e.getCreatedAt());
    }

    private BigDecimal representativeCost(List<FuturesArmCardDto> arms, FutureArmType primary) {
        return arms.stream()
                .filter(a -> primary != null && a.armType() == primary)
                .map(FuturesArmCardDto::costPoints)
                .findFirst()
                .orElseGet(() -> arms.isEmpty() ? null : arms.get(0).costPoints());
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        return new BigDecimal(v.toString());
    }
}
