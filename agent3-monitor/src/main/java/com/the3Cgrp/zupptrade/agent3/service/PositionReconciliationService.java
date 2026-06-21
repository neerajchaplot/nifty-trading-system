package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.model.TradeMonitorData;
import com.the3Cgrp.zupptrade.agent3.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.ledger.LedgerEventType;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.ledger.payload.TradeExternallyClosedPayload;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Detects trades that were closed externally on Upstox (e.g. user manually exited
 * via the Upstox mobile app) and marks them CLOSED in our DB.
 *
 * Called once per monitoring cycle BEFORE the evaluation loop. If Upstox positions
 * API is unavailable (empty map returned), the entire reconciliation is skipped —
 * no trade is ever marked closed unless we have confirmed position data from Upstox.
 *
 * Reconciliation logic:
 *   For each ACTIVE trade, fetch its short and long leg instrument keys.
 *   If BOTH legs show net quantity == 0 in Upstox positions → position is flat.
 *   Since we only monitor ACTIVE trades (which had fills confirmed by Agent 5),
 *   a flat position means it was closed externally — mark CLOSED + alert user.
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
                String shortKey = config.shortLeg() != null ? config.shortLeg().instrumentKey() : null;
                String longKey  = config.longLeg()  != null ? config.longLeg().instrumentKey()  : null;

                if (shortKey == null || longKey == null) continue;

                // Upstox normalises ':' in responses; UpstoxPositionClient converts back to '|'
                Integer shortQty = positions.get(shortKey);
                Integer longQty  = positions.get(longKey);

                // If either leg is not in the positions map, we can't conclude the position is flat
                // (could mean the position was opened before today and isn't showing, etc.)
                if (shortQty == null || longQty == null) continue;

                if (shortQty == 0 && longQty == 0) {
                    log.warn("agent3.reconcile.external_close tradeId={} tradeCode={} — both legs flat on Upstox",
                            trade.tradeId(), trade.tradeCode());

                    jdbc.update(
                            "UPDATE trades SET status = 'CLOSED', closed_at = NOW(), " +
                            "close_reason = 'EXTERNALLY_CLOSED' WHERE id = ? AND status IN ('ACTIVE','EXIT_FAILED')",
                            trade.tradeId());

                    ledger.record(trade.tradeId(), LedgerEventType.TRADE_EXTERNALLY_CLOSED,
                            new TradeExternallyClosedPayload(shortKey, longKey, "AGENT3:SCHEDULER"),
                            "AGENT3:SCHEDULER");

                    alertService.info(trade.tradeId(), "external_close",
                            "Trade " + trade.tradeCode() + " position is flat on Upstox — it appears to have " +
                            "been closed manually. Trade marked CLOSED automatically.");

                    externallyClosedIds.add(trade.tradeId());
                }
            } catch (Exception e) {
                log.warn("agent3.reconcile.error tradeId={} error={}", trade.tradeId(), e.getMessage());
            }
        }

        if (!externallyClosedIds.isEmpty()) {
            log.info("agent3.reconcile.closed_external count={}", externallyClosedIds.size());
        }
        return externallyClosedIds;
    }
}
