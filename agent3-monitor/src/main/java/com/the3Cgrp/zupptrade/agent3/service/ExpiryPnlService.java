package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.model.TradeMonitorData;
import com.the3Cgrp.zupptrade.agent3.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient;
import com.the3Cgrp.zupptrade.ledger.LedgerEventType;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.ledger.payload.TradeExpiredPayload;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Morning sweep (8:30 AM) that computes and records final P&L for trades
 * whose expiry_date has passed but are still ACTIVE in the DB.
 *
 * This handles the gap between the last 5-minute monitoring cycle (which stops
 * at 3:30 PM market close) and the actual expiry settlement at 3:30 PM.
 * Positions reconciliation the next morning also catches these trades, but
 * this service computes the accurate settled P&L from Nifty's closing price
 * on the expiry date rather than leaving actual_pnl null.
 *
 * P&L formula:
 *   At options expiry, each leg's value = its intrinsic value.
 *   PE intrinsic = max(0, strike - nifty_close)
 *   CE intrinsic = max(0, nifty_close - strike)
 *
 *   For 2-leg spreads: delegates to PnlCalculationService with intrinsic LTPs.
 *   For Iron Condor:  P&L = (actualNetPremium - totalCloseCost) × positionSize
 *     where totalCloseCost = PE_short_intrinsic - PE_long_intrinsic
 *                          + CE_short_intrinsic - CE_long_intrinsic
 */
@Service
public class ExpiryPnlService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryPnlService.class);
    private static final String COMPUTED_BY = "AGENT3:EXPIRY_SWEEP";

    private final TradeMonitorReader        tradeReader;
    private final UpstoxHistoricalDataClient historicalClient;
    private final PnlCalculationService     pnlService;
    private final TradeLedgerService        ledger;
    private final AlertService              alertService;
    private final JdbcTemplate             jdbc;
    private final JsonUtil                  jsonUtil;
    private final MonitoringProperties      props;

    public ExpiryPnlService(TradeMonitorReader tradeReader,
                             UpstoxHistoricalDataClient historicalClient,
                             PnlCalculationService pnlService,
                             TradeLedgerService ledger,
                             AlertService alertService,
                             JdbcTemplate jdbc,
                             JsonUtil jsonUtil,
                             MonitoringProperties props) {
        this.tradeReader      = tradeReader;
        this.historicalClient = historicalClient;
        this.pnlService       = pnlService;
        this.ledger           = ledger;
        this.alertService     = alertService;
        this.jdbc             = jdbc;
        this.jsonUtil         = jsonUtil;
        this.props            = props;
    }

    @Scheduled(cron = "${agent3.monitoring.expiry-pnl-cron}")
    @SchedulerLock(name = "agent3_expiry_pnl_sweep",
                   lockAtMostFor = "PT10M",
                   lockAtLeastFor = "PT1M")
    public void sweepExpiredTrades() {
        List<TradeMonitorData> expired = tradeReader.findExpiredActiveBeforeToday();
        if (expired.isEmpty()) {
            log.debug("agent3.expiry_sweep.no_candidates");
            return;
        }

        log.info("agent3.expiry_sweep.start candidates={}", expired.size());

        int settled = 0;
        int skipped = 0;

        for (TradeMonitorData trade : expired) {
            try {
                boolean done = processTrade(trade);
                if (done) settled++; else skipped++;
            } catch (Exception e) {
                log.error("agent3.expiry_sweep.trade_error tradeId={} tradeCode={} error={}",
                        trade.tradeId(), trade.tradeCode(), e.getMessage(), e);
                skipped++;
            }
        }

        log.info("agent3.expiry_sweep.complete settled={} skipped={}", settled, skipped);
    }

    // ── Per-trade processing ──────────────────────────────────────────────────

    private boolean processTrade(TradeMonitorData trade) {
        LocalDate expiryDate = trade.expiryDate();

        // 1. Parse monitor_config — needed for strikes and net premium
        if (trade.monitorConfigJson() == null) {
            log.warn("agent3.expiry_sweep.no_monitor_config tradeId={} tradeCode={} — marking CLOSED without P&L",
                    trade.tradeId(), trade.tradeCode());
            markClosedNoPnl(trade, "EXPIRED_MISSING_CONFIG");
            alertService.warning(trade.tradeId(), "expiry_no_config",
                    "Trade " + trade.tradeCode() + " expired on " + expiryDate +
                    " but monitor_config was never set. Marked CLOSED — actual_pnl cannot be computed. " +
                    "Check entry fills manually on Upstox.");
            return true;
        }

        MonitorConfigDto config = jsonUtil.fromJson(trade.monitorConfigJson(), MonitorConfigDto.class);

        // 2. Fetch Nifty closing price on expiry_date from Upstox historical candle API
        Optional<BigDecimal> niftyClose = fetchNiftyClose(expiryDate);
        if (niftyClose.isEmpty()) {
            log.warn("agent3.expiry_sweep.candle_unavailable tradeId={} tradeCode={} expiryDate={} — will retry tomorrow",
                    trade.tradeId(), trade.tradeCode(), expiryDate);
            return false; // leave ACTIVE + actual_pnl null so tomorrow's sweep retries
        }

        // 3. Compute settled P&L from intrinsic values at expiry
        BigDecimal pnl = computeExpiryPnl(config, niftyClose.get());

        // 4. Persist result and write ledger event
        jdbc.update(
                "UPDATE trades SET status = 'CLOSED', closed_at = ?::TIMESTAMPTZ, " +
                "close_reason = 'EXPIRED', actual_pnl = ? WHERE id = ? AND status = 'ACTIVE'",
                expiryDate.toString() + " 15:30:00+05:30", pnl, trade.tradeId());

        ledger.record(trade.tradeId(), LedgerEventType.TRADE_EXPIRED,
                new TradeExpiredPayload(niftyClose.get(), pnl, COMPUTED_BY),
                COMPUTED_BY);

        String outcome = pnl.compareTo(BigDecimal.ZERO) >= 0 ? "PROFIT" : "LOSS";
        log.info("agent3.expiry_sweep.settled tradeId={} tradeCode={} expiryDate={} niftyClose={} pnl={} outcome={}",
                trade.tradeId(), trade.tradeCode(), expiryDate, niftyClose.get(), pnl, outcome);

        alertService.info(trade.tradeId(), "expiry_settled",
                "Trade " + trade.tradeCode() + " expired on " + expiryDate +
                ". Nifty close: " + niftyClose.get() + ". Settled P&L: ₹" + pnl + " (" + outcome + ").");
        return true;
    }

    // ── Nifty close fetch ─────────────────────────────────────────────────────

    private Optional<BigDecimal> fetchNiftyClose(LocalDate date) {
        List<UpstoxHistoricalDataClient.UpstoxCandle> candles =
                historicalClient.fetchDailyCandles(props.getNiftyInstrumentKey(), date, date);
        if (candles.isEmpty()) return Optional.empty();
        return Optional.of(candles.get(0).close());
    }

    // ── P&L calculation ───────────────────────────────────────────────────────

    /**
     * Computes settled P&L at expiry for any spread strategy.
     * Package-private for unit testing without a Spring context.
     */
    BigDecimal computeExpiryPnl(MonitorConfigDto config, BigDecimal niftyClose) {
        boolean isIronCondor = config.shortLeg2() != null && config.longLeg2() != null;
        if (isIronCondor) {
            return computeIronCondorExpiryPnl(config, niftyClose);
        }
        return computeTwoLegExpiryPnl(config, niftyClose);
    }

    private BigDecimal computeTwoLegExpiryPnl(MonitorConfigDto config, BigDecimal niftyClose) {
        BigDecimal shortLtp = intrinsicValue(config.shortLeg(), niftyClose);
        BigDecimal longLtp  = intrinsicValue(config.longLeg(),  niftyClose);
        // Reuse PnlCalculationService — passing intrinsic values as LTPs gives settled P&L
        return pnlService.calculateMtmPnl(config, shortLtp, longLtp);
    }

    private BigDecimal computeIronCondorExpiryPnl(MonitorConfigDto config, BigDecimal niftyClose) {
        // IC = PE credit spread (shortLeg/longLeg) + CE credit spread (shortLeg2/longLeg2)
        // Net close cost = (PE_short_intrinsic - PE_long_intrinsic) + (CE_short_intrinsic - CE_long_intrinsic)
        BigDecimal peShortIntrinsic = intrinsicValue(config.shortLeg(),  niftyClose);
        BigDecimal peLongIntrinsic  = intrinsicValue(config.longLeg(),   niftyClose);
        BigDecimal ceShortIntrinsic = intrinsicValue(config.shortLeg2(), niftyClose);
        BigDecimal ceLongIntrinsic  = intrinsicValue(config.longLeg2(),  niftyClose);

        BigDecimal closeCost = peShortIntrinsic.subtract(peLongIntrinsic)
                                               .add(ceShortIntrinsic)
                                               .subtract(ceLongIntrinsic);

        BigDecimal positionSize = BigDecimal.valueOf((long) config.lots() * config.lotSize());
        return config.actualNetPremiumPerUnit()
                     .subtract(closeCost)
                     .multiply(positionSize)
                     .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Intrinsic value of an option at expiry.
     *   PE: max(0, strike - niftyClose)
     *   CE: max(0, niftyClose - strike)
     * Package-private for unit testing.
     */
    BigDecimal intrinsicValue(TradeLegDto leg, BigDecimal niftyClose) {
        BigDecimal strike = BigDecimal.valueOf(leg.strike());
        return switch (leg.optionType()) {
            case PE -> strike.subtract(niftyClose).max(BigDecimal.ZERO);
            case CE -> niftyClose.subtract(strike).max(BigDecimal.ZERO);
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void markClosedNoPnl(TradeMonitorData trade, String reason) {
        jdbc.update(
                "UPDATE trades SET status = 'CLOSED', closed_at = NOW(), close_reason = ? " +
                "WHERE id = ? AND status = 'ACTIVE'",
                reason, trade.tradeId());
        ledger.record(trade.tradeId(), LedgerEventType.TRADE_EXPIRED,
                new TradeExpiredPayload(null, null, COMPUTED_BY), COMPUTED_BY);
    }
}
