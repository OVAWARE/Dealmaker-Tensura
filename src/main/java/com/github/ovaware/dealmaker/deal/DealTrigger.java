package com.github.ovaware.dealmaker.deal;

/** When a compiled clause is eligible to run. */
public enum DealTrigger {
    ON_ACCEPTANCE,
    ON_RECURRING_DUE,
    /**
     * A non-punitive automation condition; it does not breach the deal.
     * The deal stays active unless an {@code END_DEAL} clause in the same occurrence completes it.
     */
    ON_CONDITION_MET,
    ON_BREACH
}
