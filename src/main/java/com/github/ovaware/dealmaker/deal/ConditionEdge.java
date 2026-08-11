package com.github.ovaware.dealmaker.deal;

/** Dependency-free transition rule shared by persisted edge-triggered conditions and unit tests. */
final class ConditionEdge {
    private ConditionEdge() {}

    static boolean shouldFire(boolean previous, boolean current) {
        return current && !previous;
    }
}
