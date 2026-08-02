package com.the3Cgrp.zupptrade.agent5.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Request to place the GTT for a CONFIRMED futures plan (called by Agent 3). */
public record FuturesGttRequest(@NotNull UUID planId) {}
