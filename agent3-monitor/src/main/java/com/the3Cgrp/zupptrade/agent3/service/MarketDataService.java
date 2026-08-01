package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.model.LiveMarketSnapshot;
import com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxOptionChainRow;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Source of market data for a monitoring evaluation. Two implementations, one active per profile:
 *   - {@code LiveMarketDataService}      (@Profile("!simulation")) — fetches from Upstox.
 *   - {@code SimulatedMarketDataService} (@Profile("simulation"))  — reads the scenario folder
 *                                                                    at the virtual-clock time.
 * Injected by this interface type so the scheduler and evaluation service are profile-agnostic.
 */
public interface MarketDataService {

    LiveMarketSnapshot fetchSnapshot(TradeLegDto shortLeg, TradeLegDto longLeg, LocalDate expiryDate);

    Map<LocalDate, List<UpstoxOptionChainRow>> batchFetchChains(Set<LocalDate> expiryDates);

    LiveMarketSnapshot buildSnapshotFromChain(List<UpstoxOptionChainRow> chain, BigDecimal vix,
                                              TradeLegDto shortLeg, TradeLegDto longLeg);

    BigDecimal fetchVix();
}
