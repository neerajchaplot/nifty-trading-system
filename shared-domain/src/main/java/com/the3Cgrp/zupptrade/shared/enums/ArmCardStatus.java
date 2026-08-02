package com.the3Cgrp.zupptrade.shared.enums;

/**
 * Per-arm status on the futures trade card, so the end user can choose which trade to arm.
 *   RECOMMENDED — the engine's primary (bias + open-location survivor); or both fade arms in range mode
 *   ALLOWED     — a valid alternative the user may select
 *   BLOCKED     — not selectable; {@code blockedReason} explains why (weak signal, coiled market)
 */
public enum ArmCardStatus {
    RECOMMENDED,
    ALLOWED,
    BLOCKED
}
