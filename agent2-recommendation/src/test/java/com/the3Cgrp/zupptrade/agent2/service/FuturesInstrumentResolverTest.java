package com.the3Cgrp.zupptrade.agent2.service;

import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxFuturesContractClient;
import com.the3Cgrp.zupptrade.core.upstox.model.contract.UpstoxFuturesContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Dynamic resolution of the current-month NIFTY futures instrument_key from the Upstox search
 * client. Clock fixed at 2026-08-15 IST, so the 27-Aug contract is the front month.
 */
class FuturesInstrumentResolverTest {

    private UpstoxFuturesContractClient client;
    private FuturesInstrumentResolver resolver;

    // 2026-08-15 09:00 IST
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-15T03:30:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        client = mock(UpstoxFuturesContractClient.class);
        resolver = new FuturesInstrumentResolver(client, clock);
    }

    private UpstoxFuturesContract fut(String key, String expiry, String underlying) {
        return new UpstoxFuturesContract(key, LocalDate.parse(expiry), underlying + " FUT", underlying, "FUT");
    }

    @Test
    void picksNearestNiftyExpiry_ignoresBankniftyAndFarMonths() {
        when(client.fetchNiftyFutures()).thenReturn(List.of(
                fut("NSE_FO|SEP", "2026-09-24", "NIFTY"),      // far month
                fut("NSE_FO|AUG", "2026-08-27", "NIFTY"),      // current month → expected
                fut("NSE_FO|BNF", "2026-08-27", "BANKNIFTY"),  // wrong underlying
                fut("NSE_FO|OCT", "2026-10-29", "NIFTY")));

        assertThat(resolver.resolveCurrentMonthFut()).isEqualTo("NSE_FO|AUG");
    }

    @Test
    void skipsAlreadyExpiredFrontMonth() {
        when(client.fetchNiftyFutures()).thenReturn(List.of(
                fut("NSE_FO|JUL", "2026-07-30", "NIFTY"),   // already expired (before 15-Aug)
                fut("NSE_FO|AUG", "2026-08-27", "NIFTY")));

        assertThat(resolver.resolveCurrentMonthFut()).isEqualTo("NSE_FO|AUG");
    }

    @Test
    void noNiftyContract_returnsNull() {
        when(client.fetchNiftyFutures()).thenReturn(List.of(
                fut("NSE_FO|BNF", "2026-08-27", "BANKNIFTY")));

        assertThat(resolver.resolveCurrentMonthFut()).isNull();
    }

    @Test
    void emptyResponse_returnsNull() {
        when(client.fetchNiftyFutures()).thenReturn(List.of());
        assertThat(resolver.resolveCurrentMonthFut()).isNull();
    }

    @Test
    void cachesWithinTheSameDay_singleApiCall() {
        when(client.fetchNiftyFutures()).thenReturn(List.of(fut("NSE_FO|AUG", "2026-08-27", "NIFTY")));

        resolver.resolveCurrentMonthFut();
        resolver.resolveCurrentMonthFut();

        verify(client, times(1)).fetchNiftyFutures();
    }
}
