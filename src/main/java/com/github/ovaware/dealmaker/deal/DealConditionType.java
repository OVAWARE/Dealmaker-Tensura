package com.github.ovaware.dealmaker.deal;

public enum DealConditionType {
    ALWAYS,
    PARTY_HAS_ITEM,
    /** Legacy save compatibility: canonical form is negated PARTY_HAS_ITEM. */
    @Deprecated
    PARTY_LACKS_ITEM,
    /** An exact item or item tag is present in a selected held/equipment slot. */
    PARTY_HAS_ITEM_IN_SLOT,
    /** Any non-empty item is present in a selected held/equipment slot. */
    PARTY_HOLDS_ANY_ITEM,
    /** An exact item or item tag increased in the party's inventory since the last observation. */
    ITEM_ENTERED_INVENTORY,
    /** Current custom-stat total is at least amount. */
    PARTY_STAT_AT_LEAST,
    /** Custom-stat total increased after the deal was accepted or last observed. */
    PARTY_STAT_INCREASED,
    /** A case-insensitive literal substring typed by the selected chat speaker. */
    CHAT_MESSAGE_CONTAINS,
    /** The selected contract party died. */
    PARTY_DIES,
    PARTY_USES_SKILL_CATEGORY,
    /** Legacy category aliases retained only for saved-data decoding. */
    @Deprecated
    PARTY_USES_ANY_SKILL,
    PARTY_USES_SKILL,
    @Deprecated
    PARTY_USES_MAGIC,
    @Deprecated
    PARTY_USES_BATTLEWILL,
    PARTY_IS_CROUCHING,
    PARTY_IS_SPRINTING,
    PARTY_IS_SWIMMING,
    PARTY_IS_ON_GROUND,
    PARTY_WITHIN_DISTANCE_OF_PARTY,
    /** Legacy save compatibility: canonical form is negated PARTY_WITHIN_DISTANCE_OF_PARTY. */
    @Deprecated
    PARTY_OUTSIDE_DISTANCE_OF_PARTY,
    PARTY_IN_DIMENSION,
    /** Legacy save compatibility: canonical form is negated PARTY_IN_DIMENSION. */
    @Deprecated
    PARTY_NOT_IN_DIMENSION,
    PARTY_CHANGED_DIMENSION,
    PARTY_WITHIN_COORDINATE_RADIUS,
    /** Legacy save compatibility: canonical form is negated PARTY_WITHIN_COORDINATE_RADIUS. */
    @Deprecated
    PARTY_OUTSIDE_COORDINATE_RADIUS,
    /** Legacy alias for PARTY_WITHIN_COORDINATE_RADIUS. */
    @Deprecated
    PARTY_ENTERED_COORDINATE_RADIUS,
    /** Legacy alias for negated PARTY_WITHIN_COORDINATE_RADIUS. */
    @Deprecated
    PARTY_LEFT_COORDINATE_RADIUS,
    PARTY_RESOURCE_AT_LEAST,
    PARTY_RESOURCE_INCREASED,
    /** party harmed the party named in assetId (DEALMAKER or ACCEPTOR). */
    PARTY_HARMED_PARTY,
    /** The selected party is in a level whose current weather equals clear, rain, or thunder. */
    PARTY_WEATHER_IS,
    /** The selected party is in the named day phase: dawn, day, dusk, or night. */
    PARTY_TIME_OF_DAY_IS,
    /** The selected party is standing in combined block/sky light at or above amount (0-15). */
    PARTY_LIGHT_LEVEL_AT_LEAST,
    /**
     * The selected party accepted a later deal. assetId is CURRENT_DEALMAKER, OTHER_THAN_CURRENT_DEALMAKER,
     * CURRENT_ACCEPTOR, or an exact player UUID.
     */
    PARTY_ACCEPTED_OTHER_DEAL
}
