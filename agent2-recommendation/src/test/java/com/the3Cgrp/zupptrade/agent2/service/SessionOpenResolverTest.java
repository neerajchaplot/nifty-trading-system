package com.the3Cgrp.zupptrade.agent2.service;

import com.the3Cgrp.zupptrade.agent2.config.FuturesConfig;
import com.the3Cgrp.zupptrade.agent2.exception.MarketDataUnavailableException;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxMarketQuoteClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SessionOpenResolver picks the opening reference by the IST clock across three windows:
 *   inside 09:15–15:30 → today's actual open; outside (pre-open or post-close) → GIFT, then last close.
 */
class SessionOpenResolverTest {

    private final UpstoxMarketQuoteClient marketQuote = mock(UpstoxMarketQuoteClient.class);
    private final FuturesConfig cfg = new FuturesConfig(); // window 09:15–15:30

    // 03:00Z=08:30 IST (pre-open); 04:00Z=09:30 IST (market); 10:30Z=16:00 IST (post-close).
    private static final Clock PRE_OPEN   = Clock.fixed(Instant.parse("2026-08-03T03:00:00Z"), ZoneOffset.UTC);
    private static final Clock MARKET     = Clock.fixed(Instant.parse("2026-08-03T04:00:00Z"), ZoneOffset.UTC);
    private static final Clock POST_CLOSE = Clock.fixed(Instant.parse("2026-08-03T10:30:00Z"), ZoneOffset.UTC);

    private SessionOpenResolver resolver(Clock clock) {
        return new SessionOpenResolver(marketQuote, cfg, clock);
    }

    @Test
    void marketHours_usesTodaysActualOpen_phase931() {
        SessionOpenResolver.SessionOpen so = resolver(MARKET).resolve(new BigDecimal("24333"), new BigDecimal("24000"));

        assertThat(so.price()).isEqualByComparingTo("24333");
        assertThat(so.runPhase()).isEqualTo(931);
        verifyNoInteractions(marketQuote); // inside market hours GIFT is never consulted
    }

    @Test
    void preOpen_usesGiftImpliedOpen_phase900() {
        when(marketQuote.fetchGiftNiftyLtp()).thenReturn(new BigDecimal("24280"));

        SessionOpenResolver.SessionOpen so = resolver(PRE_OPEN).resolve(null, new BigDecimal("24000"));

        assertThat(so.price()).isEqualByComparingTo("24280");
        assertThat(so.runPhase()).isEqualTo(900);
    }

    @Test
    void postClose_usesGiftImpliedOpen_phase900() {
        when(marketQuote.fetchGiftNiftyLtp()).thenReturn(new BigDecimal("24280"));

        SessionOpenResolver.SessionOpen so = resolver(POST_CLOSE).resolve(null, new BigDecimal("24000"));

        assertThat(so.price()).isEqualByComparingTo("24280");
        assertThat(so.runPhase()).isEqualTo(900);
    }

    @Test
    void outsideHours_giftUnavailable_fallsBackToLastClose() {
        when(marketQuote.fetchGiftNiftyLtp()).thenReturn(null);

        SessionOpenResolver.SessionOpen so = resolver(POST_CLOSE).resolve(null, new BigDecimal("24050"));

        assertThat(so.price()).isEqualByComparingTo("24050");
        assertThat(so.runPhase()).isEqualTo(900);
    }

    @Test
    void marketHours_candleMissing_fallsBackToGift() {
        when(marketQuote.fetchGiftNiftyLtp()).thenReturn(new BigDecimal("24280"));

        SessionOpenResolver.SessionOpen so = resolver(MARKET).resolve(null, new BigDecimal("24000"));

        assertThat(so.price()).isEqualByComparingTo("24280");
        assertThat(so.runPhase()).isEqualTo(900);
    }

    @Test
    void nothingAvailable_blocks() {
        when(marketQuote.fetchGiftNiftyLtp()).thenReturn(null);

        assertThatThrownBy(() -> resolver(POST_CLOSE).resolve(null, null))
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("session open");
    }
}
