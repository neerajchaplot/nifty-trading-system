package com.the3Cgrp.zupptrade.shared.enums;

public enum VixRegime {
    LOW,     // VIX < 13    — IV cheap, avoid selling premium
    NORMAL,  // VIX 13–18   — standard regime
    HIGH,    // VIX 18–24   — elevated, favour selling premium
    EXTREME  // VIX > 24    — no auto trade, flag to user
}
