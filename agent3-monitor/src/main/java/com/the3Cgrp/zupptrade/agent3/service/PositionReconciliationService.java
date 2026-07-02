package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.model.TradeMonitorData;
import com.the3Cgrp.zupptrade.agent3.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.ledger.LedgerEventType;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.ledger.payload.TradeCorruptedManuallyPayload;
import com.the3Cgrp.zupptrade.ledger.payload.TradeExternallyClosedPayload;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Detects two abnormal external position states each monitoring cycle:
 *
 *   1. FULLY CLOSED externally — all legs show net qty = 0 on Upstox.
 *      The user closed the entire position manually. Mark CLOSED + alert.
 *
 *   2. PARTIALLY CLOSED (corrupted) — one or more legs show qty = 0 but
 *      other legs still have open positions. The spread is broken; we cannot
 *      monitor or compute P&L reliably. Mark CORRUPTED_MANUALLY + critical alert.
 *      Agent 4 shows these as separate line items, excluded from aggregations.
 *
 * Called once per monitoring cycle BEFORE the evaluation loop. If Upstox positions
 * API is unavailable (empty map returned), reconciliation is skipped entirely —
 * no trade is ever marked closed/corrupted without confirmed position data from Upstox.
 *
 * Returns the set of trade IDs that should be skipped in the evaluation loop
 * (includes both EXTERNALLY_CLOSED and CORRUPTED_MANUALLY trades).
 */
@Service
public class PositionReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PositionReconciliationService.class);

    private final JdbcTemplate       jdbc;
    private final JsonUtil           jsonUtil;
    private final AlertService       alertService;
    private final TradeLedgerService ledger;

    public PositionReconciliationService(JdbcTemplate jdbc, JsonUtil jsonUtil,
                                          AlertService alertService,
                                          TradeLedgerService ledger) {
        this.jdbc         = jdbc;
        this.jsonUtil     = jsonUtil;
        this.alertService = alertService;
        this.ledger       = ledger;
    }

    /**
     * Returns the set of trade IDs that were detected as externally closed.
     * Callers should exclude these from the evaluation loop for the current cycle.
     *
     * @param activeTrades  trades currently in ACTIVE or EXIT_FAILED status
     * @param positions     instrument key → net quantity from Upstox (empty = skip)
     */
    public Set<UUID> reconcile(List<TradeMonitorData> activeTrades, Map<String, Integer> positions) {
        Set<UUID> externallyClosedIds = new HashSet<>();

        if (positions.isEmpty()) {
            log.debug("agent3.reconcile.skipped — positions map empty (API unavailable or market closed)");
            return externallyClosedIds;
        }

        for (TradeMonitorData trade : activeTrades) {
            if (trade.monitorConfigJson() == null) continue;
            try {
                MonitorConfigDto config = jsonUtil.fromJson(trade.monitorConfigJson(), MonitorConfigDto.class);

                // Collect all instrument keys for this trade (2 for spreads, 4 for IC)
                List<String> allKeys = new ArrayList<>();
                if (config.shortLeg() != null && config.shortLeg().instrumentKey() != null)
                    allKeys.add(config.shortLeg().instrumentKey());
                if (config.longLeg()  != null && config.longLeg().instrumentKey()  != null)
                    allKeys.add(config.longLeg().instrumentKey());
                if (config.shortLeg2() != null && config.shortLeg2().instrumentKey() != null)
                    allKeys.add(config.shortLeg2().instrumentKey());
                if (config.longLeg2()  != null && config.longLeg2().instrumentKey()  != null)
                    allKeys.add(config.longLeg2().instrumentKey());

                if (allKeys.size() < 2) continue; // monitor_config incomplete — skip

                // If any leg is absent from the positions map, we cannot determine state
                List<String> flatLegs = new ArrayList<>();
                List<String> openLegs = new ArrayList<>();
                boolean anyAbsent = false;

                for (String key : allKeys) {
                    Integer qty = positions.get(key);
                    if (qty == null) { anyAbsent = true; break; }
                    if (qty == 0) flatLegs.add(key);
                    else          openLegs.add(key);
                }

                if (anyAbsent) continue; // can't conclude — skip this cycle

                if (flatLegs.size() == allKeys.size()) {
                    // ── All legs flat → fully externally closed ───────────────────
                    log.warn("agent3.reconcile.external_close tradeId={} tradeCode={} — all {} legs flat on Upstox",
                            trade.tradeId(), trade.tradeCode(), allKeys.size());

                    jdbc.update(
                            "UPDATE trades SET status = 'CLOSED', closed_at = NOW(), " +
                            "close_reason = 'EXTERNALLY_CLOSED' WHERE id = ? AND status IN ('ACTIVE','EXIT_FAILED')",
                            trade.tradeId());

                    ledger.record(trade.tradeId(), LedgerEventType.TRADE_EXTERNALLY_CLOSED,
                            new TradeExternallyClosedPayload(allKeys.get(0), allKeys.get(1), "AGENT3:SCHEDULER"),
                            "AGENT3:SCHEDULER");

                    alertService.info(trade.tradeId(), "external_close",
                            "Trade " + trade.tradeCode() + " all legs flat on Upstox — closed manually. " +
                            "Trade marked CLOSED automatically.");

                    externallyClosedIds.add(trade.tradeId());

                } else if (!flatLegs.isEmpty()) {
                    // ── Some legs flat, others open → corrupted partial close ─────
                    log.error("agent3.reconcile.partial_close tradeId={} tradeCode={} — " +
                              "{} leg(s) flat={}, {} leg(s) still open={}",
                            trade.tradeId(), trade.tradeCode(),
                            flatLegs.size(), flatLegs, openLegs.size(), openLegs);

                    String closeReason = "PARTIAL_CLOSE: flat=" + flatLegs + " open=" + openLegs;
                    jdbc.update(
                            "UPDATE trades SET status = 'CORRUPTED_MANUALLY', closed_at = NOW(), " +
                            "close_reason = ? WHERE id = ? AND status IN ('ACTIVE','EXIT_FAILED')",
                            closeReason, trade.tradeId());

                    ledger.record(trade.tradeId(), LedgerEventType.TRADE_CORRUPTED_MANUALLY,
                            new TradeCorruptedManuallyPayload(flatLegs, openLegs, "AGENT3:SCHEDULER"),
                            "AGENT3:SCHEDULER");

                    alertService.critical(trade.tradeId(), "partial_close_corrupted",
                            "CORRUPTED: Trade " + trade.tradeCode() + " has " + flatLegs.size() +
                            " leg(s) closed on Upstox (" + flatLegs + ") but " + openLegs.size() +
                            " leg(s) still open (" + openLegs + "). The spread is broken. " +
                            "Monitoring stopped. MANUAL INTERVENTION REQUIRED — close the open legs on Upstox.");

                    externallyClosedIds.add(trade.tradeId());
                }
                // else: all legs still open → normal, no action

            } catch (Exception e) {
                log.warn("agent3.reconcile.error tradeId={} error={}", trade.tradeId(), e.getMessage());
            }
        }

        if (!externallyClosedIds.isEmpty()) {
            log.info("agent3.reconcile.cycle_result skipped={}", externallyClosedIds.size());
        }
        return externallyClosedIds;
    }
}
