package com.the3Cgrp.zupptrade.shared.enums;

public enum Confidence {
    HIGH,   // all 3 scoring categories agree
    MEDIUM, // 2 of 3 categories agree
    LOW     // only 1 category agrees — Agent 2 uses most conservative strategy or skips
}
