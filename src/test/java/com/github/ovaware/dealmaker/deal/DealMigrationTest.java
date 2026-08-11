package com.github.ovaware.dealmaker.deal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DealMigrationTest {
    @Test
    void invalidatesLegacyPendingAndActiveDealsButPreservesHistory() {
        Deal active = legacy(DealStatus.ACTIVE);
        Deal complete = legacy(DealStatus.COMPLETED);

        assertEquals(DealStatus.INVALIDATED_LEGACY, active.migratedStatus().status());
        assertEquals(DealStatus.COMPLETED, complete.migratedStatus().status());
        assertEquals(Deal.CURRENT_DATA_VERSION, active.migratedStatus().dataVersion());
    }

    private static Deal legacy(DealStatus status) {
        return new Deal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "legacy", List.of(
                new DealClause(ClauseKind.KILL_PLAYER, Party.ACCEPTOR, Party.DEALMAKER, "", 0, 0)),
                status, 0L, 0L, List.of(), Map.of(), 1, Map.of());
    }
}
