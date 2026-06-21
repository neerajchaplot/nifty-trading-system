package com.the3Cgrp.zupptrade.agent3.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.the3Cgrp.zupptrade.agent3.domain.entity.MonitoringEvaluationEntity;
import com.the3Cgrp.zupptrade.agent3.dto.EvaluationResponse;
import com.the3Cgrp.zupptrade.agent3.engine.MonitorStrategyFactory;
import com.the3Cgrp.zupptrade.agent3.model.EvaluationResult;
import com.the3Cgrp.zupptrade.agent3.model.LiveMarketSnapshot;
import com.the3Cgrp.zupptrade.agent3.model.MonitorEvaluationContext;
import com.the3Cgrp.zupptrade.agent3.model.TradeMonitorData;
import com.the3Cgrp.zupptrade.agent3.repository.MonitoringEvaluationRepository;
import com.the3Cgrp.zupptrade.agent3.util.JsonUtil;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.enums.MonitorAction;
import com.the3Cgrp.zupptrade.shared.enums.ThresholdHit;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Template Method Pattern — evaluation pipeline:
 *   1. Load trade from DB (via JdbcTemplate reader)
 *   2. Deserialize MonitorConfigDto
 *   3. Fetch live market snapshot from Upstox
 *   4. Look up previous evaluation VIX for spike detection
 *   5. Delegate to MonitorStrategy for decision
 *   6. Persist result to monitoring_evaluations
 *   7. Return response to caller (Orchestrator)
 *
 * TODO(orchestrator): Orchestrator polls this endpoint every 5 minutes per active trade.
 *   On READJUST: Orchestrator → Agent5.exit → Agent2.recommend → Agent5.execute
 *   On EXIT:     Orchestrator → Agent5.exitAll (market order)
 *   Agent 3 does NOT write to trade_ledger — that is Agent 5's responsibility after execution.
 *
 * TODO(notifications): Create notifications table; Orchestrator polls and delivers via
 *   configured channel (email / Telegram / Twilio). See task #19 follow-up.
 */
@Service
public class MonitorEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(MonitorEvaluationService.class);

    private final TradeMonitorReader tradeReader;
    private final LiveMarketDataService marketDataService;
    private final MonitorStrategyFactory strategyFactory;
    private final MonitoringEvaluationRepository evaluationRepository;
    private final JsonUtil jsonUtil;

    public MonitorEvaluationService(TradeMonitorReader tradeReader,
                                     LiveMarketDataService marketDataService,
                                     MonitorStrategyFactory strategyFactory,
                                     MonitoringEvaluationRepository evaluationRepository,
                                     JsonUtil jsonUtil) {
        this.tradeReader = tradeReader;
        this.marketDataService = marketDataService;
        this.strategyFactory = strategyFactory;
        this.evaluationRepository = evaluationRepository;
        this.jsonUtil = jsonUtil;
    }

    /**
     * REST endpoint path — loads the trade, fetches its own live snapshot, then evaluates.
     * Throws ResponseStatusException on not-found or invalid state (HTTP-appropriate).
     */
    @Transactional
    public EvaluationResponse evaluate(UUID tradeId) {
        TradeMonitorData trade = loadAndValidate(tradeId);
        MonitorConfigDto config = deserializeConfig(trade);
        LiveMarketSnapshot liveData = marketDataService.fetchSnapshot(
                config.shortLeg(), config.longLeg(), config.expiryDate());
        return doEvaluate(trade, config, liveData);
    }

    /**
     * Scheduler path — trade data and snapshot already prepared by the monitoring cycle.
     * No DB load, no Upstox call — uses the pre-fetched batch data for the current cycle.
     */
    @Transactional
    public EvaluationResponse evaluate(TradeMonitorData trade, MonitorConfigDto config,
                                        LiveMarketSnapshot snapshot) {
        return doEvaluate(trade, config, snapshot);
    }

    /** Core evaluation logic shared by both paths. */
    private EvaluationResponse doEvaluate(TradeMonitorData trade, MonitorConfigDto config,
                                            LiveMarketSnapshot liveData) {
        UUID tradeId = trade.tradeId();

        BigDecimal previousVix = evaluationRepository.findLatestByTradeId(tradeId)
                .map(MonitoringEvaluationEntity::getVixLevel)
                .orElse(null);
        BigDecimal entryVix = extractEntryVix(trade.marketContextJson());
        int currentDte = (int) ChronoUnit.DAYS.between(LocalDate.now(), config.expiryDate());

        MonitorEvaluationContext ctx = new MonitorEvaluationContext(
                config, liveData, currentDte, previousVix, entryVix);

        EvaluationResult result = strategyFactory.resolve(config.strategy()).evaluate(ctx);

        log.info("agent3.evaluation.complete tradeId={} tradeCode={} action={} threshold={} pop={} pnl={}",
                tradeId, trade.tradeCode(), result.action(), result.thresholdHit(),
                result.livePop(), result.markToMarketPnl());

        MonitoringEvaluationEntity entity = persist(tradeId, liveData, result);
        return toResponse(entity.getId(), tradeId, liveData, result);
    }

    private TradeMonitorData loadAndValidate(UUID tradeId) {
        TradeMonitorData trade = tradeReader.findById(tradeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Trade not found: " + tradeId));
        if (!isEligibleForMonitoring(trade.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Trade " + trade.tradeCode() + " is not in a monitorable state: " + trade.status());
        }
        if (trade.monitorConfigJson() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Trade " + trade.tradeCode() + " has no monitor_config — Agent 5 may not have confirmed fills yet.");
        }
        return trade;
    }

    private MonitorConfigDto deserializeConfig(TradeMonitorData trade) {
        return jsonUtil.fromJson(trade.monitorConfigJson(), MonitorConfigDto.class);
    }

    private boolean isEligibleForMonitoring(TradeStatus status) {
        // EXIT_FAILED: previous exit attempt failed — re-evaluate and retry exit
        return status == TradeStatus.ACTIVE
                || status == TradeStatus.CONFIRMED
                || status == TradeStatus.EXIT_FAILED;
    }

    /** Extract VIX at trade entry from market_context JSONB. */
    private BigDecimal extractEntryVix(String marketContextJson) {
        if (marketContextJson == null) return null;
        try {
            MarketContextSnapshot snap = jsonUtil.fromJson(marketContextJson, MarketContextSnapshot.class);
            return snap.vix();
        } catch (Exception e) {
            log.warn("agent3.market_context.parse_error error={}", e.getMessage());
            return null;
        }
    }

    @Transactional
    protected MonitoringEvaluationEntity persist(UUID tradeId,
                                                  LiveMarketSnapshot liveData,
                                                  EvaluationResult result) {
        MonitoringEvaluationEntity entity = new MonitoringEvaluationEntity();
        entity.setTradeId(tradeId);
        entity.setEvaluatedAt(Instant.now());
        entity.setAction(result.action());
        entity.setThresholdHit(result.thresholdHit() != null ? result.thresholdHit() : ThresholdHit.NONE);
        entity.setReason(result.reason());
        entity.setSpotPrice(liveData.spot());
        entity.setVixLevel(liveData.vix());
        entity.setCurrentPop(result.livePop());
        entity.setCurrentNetPremium(result.currentNetPremium());
        entity.setMarkToMarketPnl(result.markToMarketPnl());
        entity.setShortLegLtp(liveData.shortLegLtp());
        entity.setLongLegLtp(liveData.longLegLtp());
        if (result.detail() != null) {
            entity.setEvaluationDetail(jsonUtil.toJson(result.detail()));
        }
        return evaluationRepository.save(entity);
    }

    private EvaluationResponse toResponse(UUID evalId, UUID tradeId,
                                           LiveMarketSnapshot liveData,
                                           EvaluationResult result) {
        return new EvaluationResponse(
                evalId,
                tradeId,
                result.action(),
                result.thresholdHit(),
                result.reason(),
                liveData.spot(),
                liveData.vix(),
                result.livePop(),
                result.markToMarketPnl(),
                result.currentNetPremium(),
                liveData.shortLegLtp(),
                liveData.longLegLtp(),
                Instant.now()
        );
    }

    /** Minimal projection to extract VIX from market_context JSONB. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MarketContextSnapshot(
            @JsonProperty("vix") BigDecimal vix
    ) {}
}
