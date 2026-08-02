package com.the3Cgrp.zupptrade.shared.enums;

/**
 * Lifecycle of a single futures plan row in trade_future_ledger (single evolving row).
 *
 * Happy path:
 *   PRIMED ──user approves──▶ ARMED ──entry FSM──▶ BREAK_DETECTED ──▶ CONFIRMED
 *          ──Agent5 GTT──▶ FILLED ──GTT OCO──▶ CLOSED
 *
 * Terminal-without-trade:
 *   NO_TRADE         — engine declined at recommend time (compression / R:R fail / bias-location conflict)
 *   REJECTED         — user rejected the primed plan
 *   INVALIDATED      — 5-min close back inside after a break (Agent 3 FSM)
 *   EXPIRED          — ~11:00 no confirmation (Agent 3 FSM)
 *   EXECUTION_FAILED — Agent 5 could not place the GTT (margin / instrument / broker error); user must act
 */
public enum FuturePlanStatus {
    PRIMED,
    ARMED,
    BREAK_DETECTED,
    CONFIRMED,
    FILLED,
    CLOSED,
    NO_TRADE,
    REJECTED,
    INVALIDATED,
    EXPIRED,
    EXECUTION_FAILED
}
