package com.github.ovaware.dealmaker.deal;

/** Every executable operation must be represented by one of these allowlisted kinds. */
public enum ClauseKind {
    TRANSFER_ALL_SKILLS_IN_CATEGORY,
    /** Legacy category aliases retained only for saved-data decoding. */
    @Deprecated
    TRANSFER_ALL_UNIQUE_SKILLS,
    @Deprecated
    TRANSFER_ALL_ULTIMATE_SKILLS,
    @Deprecated
    TRANSFER_ALL_MAGICS,
    @Deprecated
    TRANSFER_ALL_BATTLEWILLS,
    TRANSFER_SKILL,
    SHARE_SKILL,
    TRANSFER_ATTRIBUTE_PERCENT,
    TRANSFER_ATTRIBUTE_AMOUNT,
    REVOKE_ATTRIBUTE_GRANTS,
    TRANSFER_ITEM_AMOUNT,
    TRANSFER_ALL_MATCHING_ITEMS,
    TRANSFER_INVENTORY_SLOT,
    TRANSFER_RESOURCE_AMOUNT,
    TRANSFER_RESOURCE_PERCENT,
    /** Removes a fixed amount of EP, magicule, or aura and gives it to the other signer. */
    DRAIN_RESOURCE_AMOUNT,
    /** Removes a percentage of current EP, magicule, or aura and gives it to the other signer. */
    DRAIN_RESOURCE_PERCENT,
    /** Removes a fixed amount of EP, magicule, or aura without crediting either signer. */
    DESTROY_RESOURCE_AMOUNT,
    /** Removes a percentage of current EP, magicule, or aura without crediting either signer. */
    DESTROY_RESOURCE_PERCENT,
    /** Legacy resource aliases retained only for saved-data decoding. */
    @Deprecated
    TRANSFER_EP_AMOUNT,
    @Deprecated
    TRANSFER_EP_PERCENT,
    @Deprecated
    TRANSFER_MAGICULE_AMOUNT,
    @Deprecated
    TRANSFER_MAGICULE_PERCENT,
    @Deprecated
    TRANSFER_AURA_AMOUNT,
    @Deprecated
    TRANSFER_AURA_PERCENT,
    /** Redirects only positive EP/magicule/aura deltas observed after acceptance. */
    REDIRECT_RESOURCE_GAIN_PERCENT,
    REDIRECT_DAMAGE_PERCENT,
    /** Legacy save compatibility: canonical form is recurring TRANSFER_ITEM_AMOUNT. */
    @Deprecated
    RECURRING_ITEM_PAYMENT,
    FORFEIT_SOUL,
    KILL_PLAYER,
    /** Applies direct generic damage to from after the deal's reversible mutations succeed. */
    DEAL_DAMAGE_AMOUNT,
    /** Sets from on fire for amount whole seconds after the deal's reversible mutations succeed. */
    SET_ON_FIRE_SECONDS,
    /**
     * Marks the deal {@code COMPLETED} after any sibling mutations in the same occurrence succeed.
     * Completed transfers and grants are not reversed; ongoing redirects and conditions stop.
     */
    END_DEAL
}
