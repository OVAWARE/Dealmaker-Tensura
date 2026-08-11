package com.github.ovaware.dealmaker.deal;

public enum DealStatus {
    PENDING,
    ACTIVE,
    BREACHED,
    SEVERED,
    COMPLETED,
    REJECTED,
    /** Pre-v2 pending/active deals are never executed under the hardened runtime. */
    INVALIDATED_LEGACY
}
