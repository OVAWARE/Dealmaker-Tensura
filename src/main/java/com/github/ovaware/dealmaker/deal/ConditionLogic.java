package com.github.ovaware.dealmaker.deal;

/** Flat boolean groups. Negation is stored per term; ALL/ANY can express AND, OR, NAND, NOR, and De Morgan forms. */
public enum ConditionLogic {
    ALL,
    ANY
}
