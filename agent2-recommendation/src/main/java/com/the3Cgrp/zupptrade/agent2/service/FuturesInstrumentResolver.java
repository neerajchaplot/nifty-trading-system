package com.the3Cgrp.zupptrade.agent2.service;

import com.the3Cgrp.zupptrade.agent2.config.FuturesConfig;
import com.the3Cgrp.zupptrade.agent2.repository.ReferenceDataRepository;
import com.the3Cgrp.zupptrade.agent2.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Resolves the current-month Nifty futures instrument_key from reference_data (the derived
 * value is cached there by the data loader / a monthly rollover job), key {@code nifty.fut.current}.
 *
 * The instrument key is only needed by Agent 5 to place the GTT — the Camarilla levels and
 * arms are all derived from the Nifty INDEX. So a missing key does NOT block the recommend
 * card; it is stored null and logged as a data gap, to be filled before execution.
 */
@Component
public class FuturesInstrumentResolver {

    private static final Logger log = LoggerFactory.getLogger(FuturesInstrumentResolver.class);

    private final ReferenceDataRepository referenceDataRepository;
    private final FuturesConfig config;
    private final JsonUtil jsonUtil;

    public FuturesInstrumentResolver(ReferenceDataRepository referenceDataRepository,
                                     FuturesConfig config, JsonUtil jsonUtil) {
        this.referenceDataRepository = referenceDataRepository;
        this.config = config;
        this.jsonUtil = jsonUtil;
    }

    /** @return the resolved instrument_key, or null if not yet available (logged as a gap). */
    public String resolveCurrentMonthFut() {
        return referenceDataRepository.findById(config.getFutInstrumentRefKey())
                .map(ref -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = jsonUtil.fromJson(ref.getValue(), Map.class);
                    Object key = map.get("instrumentKey");
                    return key != null ? key.toString() : null;
                })
                .orElseGet(() -> {
                    log.warn("futures.instrument.unresolved key={} — card built without a fut token; "
                            + "resolve before Agent 5 execution", config.getFutInstrumentRefKey());
                    return null;
                });
    }
}
