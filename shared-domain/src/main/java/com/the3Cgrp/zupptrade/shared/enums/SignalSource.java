package com.the3Cgrp.zupptrade.shared.enums;

/**
 * Which UI channel an Agent 1 signal was generated for. Signals are stored and fetched per source
 * so the Trading and Futures tabs never override each other.
 *
 *   TRADING — options/trading tab; per-user (scoped to the acting user's profile).
 *   FUTURES — futures tab; global/shared (admin-driven, one signal for the day regardless of user).
 */
public enum SignalSource {
    TRADING,
    FUTURES
}
