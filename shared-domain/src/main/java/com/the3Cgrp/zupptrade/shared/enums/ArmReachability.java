package com.the3Cgrp.zupptrade.shared.enums;

/**
 * Whether a futures arm's entry is still catchable at the current Nifty level.
 * Computed live on read (never persisted): an arm is MISSED once the live level sits outside
 * its stop↔target band — the move already ran past target, or price broke past the stop.
 */
public enum ArmReachability {
    REACHABLE,
    MISSED
}
