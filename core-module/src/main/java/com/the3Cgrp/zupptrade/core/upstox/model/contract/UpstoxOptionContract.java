package com.the3Cgrp.zupptrade.core.upstox.model.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** Minimal projection of an Upstox /v2/option/contract record — only the fields we need. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxOptionContract(
        @JsonProperty("expiry") LocalDate expiry,
        @JsonProperty("weekly") boolean weekly
) {}
