package com.github.ovaware.dealmaker.deal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DealConditionTest {
    @Test
    void spatialConditionFiresWhenAlreadyTrueOnFirstObservationThenRearmer() {
        assertTrue(ConditionEdge.shouldFire(false, true));
        assertFalse(ConditionEdge.shouldFire(true, true));
        assertFalse(ConditionEdge.shouldFire(true, false));
        assertTrue(ConditionEdge.shouldFire(false, true));
    }

    @Test
    void negatedHorizontalRadiusFiresOnlyAfterLeavingAndIgnoresHeight() {
        boolean insideAtAnyHeight = SpatialDistance.within(50, 5000, 0, 100, true, false, true);
        boolean leaveConditionInside = !insideAtAnyHeight;
        assertFalse(ConditionEdge.shouldFire(false, leaveConditionInside));

        boolean outsideAtAnyHeight = SpatialDistance.within(101, -5000, 0, 100, true, false, true);
        boolean leaveConditionOutside = !outsideAtAnyHeight;
        assertTrue(ConditionEdge.shouldFire(false, leaveConditionOutside));
        assertFalse(SpatialDistance.within(50, 5000, 0, 100, true, true, true));
    }

    @Test
    void everyCoordinateAxisCanBeIgnoredIndependently() {
        assertTrue(SpatialDistance.within(9999, 3, 4, 5, false, true, true)); // ~,0,0
        assertTrue(SpatialDistance.within(3, 9999, 4, 5, true, false, true)); // 0,~,0
        assertTrue(SpatialDistance.within(3, 4, 9999, 5, true, true, false)); // 0,0,~
        assertTrue(SpatialDistance.within(9999, 9999, 5, 5, false, false, true)); // ~,~,0
        assertTrue(SpatialDistance.within(9999, 9999, 9999, 0, false, false, false)); // ~,~,~
    }

    @Test
    void allowsItemPredicatesAndBreachRevocation() {
        DealCondition diamondHeld = new DealCondition(DealConditionType.PARTY_HAS_ITEM,
                Party.ACCEPTOR, "minecraft:diamond", 1);
        var errors = DealPolicy.validateClauses(List.of(
                new DealClause(ClauseKind.REVOKE_ATTRIBUTE_GRANTS, Party.ACCEPTOR, Party.DEALMAKER,
                        "minecraft:generic.max_health", 0.0, 0L, DealTrigger.ON_BREACH, diamondHeld),
                new DealClause(ClauseKind.FORFEIT_SOUL, Party.ACCEPTOR, Party.DEALMAKER,
                        "", 0.0, 0L, DealTrigger.ON_BREACH, diamondHeld)));

        assertTrue(errors.isEmpty(), () -> String.join(" ", errors));
    }

    @Test
    void allowsDynamicallyNamedTaggedHeldItemCategories() {
        DealCondition holdingCategoryInMainHand = new DealCondition(DealConditionType.PARTY_HAS_ITEM_IN_SLOT,
                Party.ACCEPTOR, "#modpack:runtime_category", 1, "MAIN_HAND");
        DealCondition holdingCategoryInOffHand = new DealCondition(DealConditionType.PARTY_HAS_ITEM_IN_SLOT,
                Party.ACCEPTOR, "#modpack:another_runtime_category", 1, "OFF_HAND");

        var errors = DealPolicy.validateClauses(List.of(
                new DealClause(ClauseKind.KILL_PLAYER, Party.ACCEPTOR, Party.DEALMAKER,
                        "", 0.0, 0L, DealTrigger.ON_CONDITION_MET, holdingCategoryInMainHand),
                new DealClause(ClauseKind.KILL_PLAYER, Party.ACCEPTOR, Party.DEALMAKER,
                        "", 0.0, 0L, DealTrigger.ON_CONDITION_MET, holdingCategoryInOffHand)));

        assertTrue(errors.isEmpty(), () -> String.join(" ", errors));
    }
}
