package com.the3Cgrp.zupptrade.core.upstox.model.candle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Wrapper for historical candle data.
 * Each candle is an array: [timestamp, open, high, low, close, volume, oi]
 * Jackson maps heterogeneous arrays to List<Object>; we parse manually in UpstoxHistoricalDataClient.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxCandleResponse(List<List<Object>> candles) {}
