package com.the3Cgrp.zupptrade.agent2.service;

import com.the3Cgrp.zupptrade.agent2.config.FuturesConfig;
import com.the3Cgrp.zupptrade.agent2.exception.MarketDataUnavailableException;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxMarketQuoteClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Resolves the futures plan's <b>session open</b> — the fixed opening reference the open-zone
 * classifier buckets against (NOT the drifting live spot). Chosen by the real IST clock across three
 * windows:
 *
 * <ul>
 *   <li><b>Inside market hours [09:15–15:30]:</b> today's <b>actual opening print</b> — the open of
 *       today's daily candle (its 09:15 open, fixed for the session). Run phase 931 (the confirm run).</li>
 *   <li><b>Outside market hours (pre-open OR post-close):</b> the same reference the 09:00 pre-open
 *       plan uses — the <b>GIFT-implied open</b>, falling back to the <b>last close</b> if GIFT is
 *       unavailable. Run phase 900.</li>
 * </ul>
 *
 * During market hours, if today's candle is somehow missing it also falls back to GIFT → last close
 * rather than failing. Only when none of candle / GIFT / last close is available does it block.
 */
@Component
public class SessionOpenResolver {

    private static final Logger log = LoggerFactory.getLogger(SessionOpenResolver.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int PHASE_PRE_OPEN = 900;   // GIFT / last-close plan (outside market hours)
    private static final int PHASE_CONFIRM = 931;    // live candle plan (inside market hours)

    private final UpstoxMarketQuoteClient marketQuoteClient;
    private final FuturesConfig config;
    private final Clock clock;

    public SessionOpenResolver(UpstoxMarketQuoteClient marketQuoteClient,
                               FuturesConfig config,
                               Clock clock) {
        this.marketQuoteClient = marketQuoteClient;
        this.config = config;
        this.clock = clock;
    }

    /** The resolved session-open reference plus the run phase it implies (900 outside hours, 931 live). */
    public record SessionOpen(BigDecimal price, int runPhase) {}

    /**
     * @param todaysActualOpen open of today's daily candle (present during market hours), or null
     * @param lastClose        the last completed session's close (prior-day close), or null
     */
    public SessionOpen resolve(BigDecimal todaysActualOpen, BigDecimal lastClose) {
        LocalTime nowIst = OffsetDateTime.now(clock).atZoneSameInstant(IST).toLocalTime();
        boolean marketHours = !nowIst.isBefore(config.getMarketOpenTime())
                && !nowIst.isAfter(config.getMarketCloseTime());

        // Inside market hours: today's actual opening print.
        if (marketHours && todaysActualOpen != null) {
            log.info("futures.session_open.intraday", kv("open", todaysActualOpen), kv("runPhase", PHASE_CONFIRM));
            return new SessionOpen(todaysActualOpen, PHASE_CONFIRM);
        }

        // Outside market hours (pre-open / post-close), or today's candle missing intraday:
        // the 09:00-plan reference — GIFT-implied open, then last close.
        BigDecimal gift = marketQuoteClient.fetchGiftNiftyLtp();
        if (gift != null) {
            log.info("futures.session_open.gift", kv("giftLtp", gift), kv("marketHours", marketHours),
                    kv("runPhase", PHASE_PRE_OPEN));
            return new SessionOpen(gift, PHASE_PRE_OPEN);
        }
        if (lastClose != null) {
            log.info("futures.session_open.last_close", kv("lastClose", lastClose),
                    kv("marketHours", marketHours), kv("runPhase", PHASE_PRE_OPEN));
            return new SessionOpen(lastClose, PHASE_PRE_OPEN);
        }

        throw new MarketDataUnavailableException(
                "Cannot derive the session open — no opening candle, GIFT Nifty, or last close available.");
    }
}
