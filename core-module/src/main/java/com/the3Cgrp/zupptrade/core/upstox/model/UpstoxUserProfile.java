package com.the3Cgrp.zupptrade.core.upstox.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxUserProfile(
        @JsonProperty("user_id")   String userId,
        @JsonProperty("user_name") String userName,
        String email,
        @JsonProperty("user_type") String userType,
        String broker,
        List<String> exchanges,
        List<String> products,
        @JsonProperty("order_types") List<String> orderTypes
) {}
