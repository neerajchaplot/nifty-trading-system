package com.the3Cgrp.zupptrade.shared.enums;

public enum IvRegime {
    RICH,   // IV/HV > 1.2  — favour selling premium
    FAIR,   // IV/HV 0.85–1.2
    CHEAP   // IV/HV < 0.85 — favour buying premium
}
