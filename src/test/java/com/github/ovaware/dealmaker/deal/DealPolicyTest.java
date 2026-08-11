package com.github.ovaware.dealmaker.deal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DealPolicyTest {
    @Test
    void rejectsNonFiniteAmountsAndTooFastRecurrence() {
        var errors = DealPolicy.validateClauses(List.of(
                new DealClause(ClauseKind.TRANSFER_EP_AMOUNT, Party.ACCEPTOR, Party.DEALMAKER,
                        "", Double.NaN, 0L, DealTrigger.ON_ACCEPTANCE, DealCondition.ALWAYS),
                new DealClause(ClauseKind.KILL_PLAYER, Party.ACCEPTOR, Party.DEALMAKER,
                        "", 0.0, 10L, DealTrigger.ON_RECURRING_DUE, DealCondition.ALWAYS)));

        assertTrue(errors.stream().anyMatch(error -> error.contains("finite")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("at least 20")));
    }

    @Test
    void allowsAnyTypedActionToBeRecurring() {
        var errors = DealPolicy.validateClauses(List.of(
                new DealClause(ClauseKind.KILL_PLAYER, Party.ACCEPTOR, Party.DEALMAKER,
                        "", 0.0, 6000L, DealTrigger.ON_RECURRING_DUE, DealCondition.ALWAYS),
                new DealClause(ClauseKind.TRANSFER_INVENTORY_SLOT, Party.ACCEPTOR, Party.DEALMAKER,
                        "MAIN_HAND", 0.0, 1200L, DealTrigger.ON_RECURRING_DUE, DealCondition.ALWAYS)));

        assertTrue(errors.isEmpty(), () -> String.join(" ", errors));
    }

    @Test
    void acceptsTypedSpatialAndResourceGainCapabilities() {
        DealCondition region = new DealCondition(DealConditionType.PARTY_ENTERED_COORDINATE_RADIUS,
                Party.ACCEPTOR, "", 0, "", "minecraft:overworld", 10, 64, -20, 8);
        var errors = DealPolicy.validateClauses(List.of(
                new DealClause(ClauseKind.KILL_PLAYER, Party.ACCEPTOR, Party.DEALMAKER,
                        "", 0.0, 0L, DealTrigger.ON_CONDITION_MET, region),
                new DealClause(ClauseKind.REDIRECT_RESOURCE_GAIN_PERCENT, Party.ACCEPTOR, Party.DEALMAKER,
                        "magicule", 25.0, 0L, DealTrigger.ON_ACCEPTANCE, DealCondition.ALWAYS)));

        assertTrue(errors.isEmpty(), () -> String.join(" ", errors));
    }

    @Test
    void allowsHealthTransferFromDealmakerToAcceptor() {
        var errors = DealPolicy.validateClauses(List.of(new DealClause(
                ClauseKind.TRANSFER_ATTRIBUTE_PERCENT, Party.DEALMAKER, Party.ACCEPTOR,
                "minecraft:generic.max_health", 25.0, 0L)));

        assertTrue(errors.isEmpty(), () -> String.join(" ", errors));
    }

    @Test
    void allowsImmediateVoluntarySoulTransfer() {
        var errors = DealPolicy.validateClauses(List.of(new DealClause(
                ClauseKind.FORFEIT_SOUL, Party.ACCEPTOR, Party.DEALMAKER, "", 0.0, 0L)));

        assertTrue(errors.isEmpty(), () -> String.join(" ", errors));
    }

    @Test
    void allowsImmediateItemEpAndSkillTransfers() {
        var errors = DealPolicy.validateClauses(List.of(
                new DealClause(ClauseKind.TRANSFER_ITEM_AMOUNT, Party.DEALMAKER, Party.ACCEPTOR,
                        "minecraft:diamond", 10.0, 0L),
                new DealClause(ClauseKind.TRANSFER_EP_AMOUNT, Party.ACCEPTOR, Party.DEALMAKER,
                        "", 500.0, 0L),
                new DealClause(ClauseKind.TRANSFER_SKILL, Party.DEALMAKER, Party.ACCEPTOR,
                        "tensura:great_sage", 0.0, 0L)));

        assertTrue(errors.isEmpty(), () -> String.join(" ", errors));
    }

    @Test
    void acceptsEnvironmentalAndLaterDealAcceptanceConditions() {
        DealCondition sunlight = new DealCondition(DealConditionType.PARTY_LIGHT_LEVEL_AT_LEAST,
                Party.ACCEPTOR, "", 15);
        DealCondition laterDeal = new DealCondition(DealConditionType.PARTY_ACCEPTED_OTHER_DEAL,
                Party.ACCEPTOR, "OTHER_THAN_CURRENT_DEALMAKER", 0);

        var errors = DealPolicy.validateClauses(List.of(
                new DealClause(ClauseKind.SET_ON_FIRE_SECONDS, Party.ACCEPTOR, Party.DEALMAKER,
                        "", 5.0, 0L, DealTrigger.ON_CONDITION_MET, sunlight),
                new DealClause(ClauseKind.KILL_PLAYER, Party.ACCEPTOR, Party.DEALMAKER,
                        "", 0.0, 0L, DealTrigger.ON_BREACH, laterDeal)));

        assertTrue(errors.isEmpty(), () -> String.join(" ", errors));
    }

    @Test
    void acceptsMagiculeDrainsAndRejectsInvalidLightLevels() {
        DealCondition invalidLight = new DealCondition(DealConditionType.PARTY_LIGHT_LEVEL_AT_LEAST,
                Party.ACCEPTOR, "", 16);
        var valid = DealPolicy.validateClauses(List.of(
                new DealClause(ClauseKind.DRAIN_RESOURCE_PERCENT, Party.ACCEPTOR, Party.DEALMAKER,
                        "magicule", 25.0, 0L, DealTrigger.ON_ACCEPTANCE, DealCondition.ALWAYS),
                new DealClause(ClauseKind.DESTROY_RESOURCE_AMOUNT, Party.ACCEPTOR, Party.DEALMAKER,
                        "magicule", 100.0, 0L, DealTrigger.ON_ACCEPTANCE, DealCondition.ALWAYS)));
        var invalid = DealPolicy.validateClauses(List.of(new DealClause(
                ClauseKind.KILL_PLAYER, Party.ACCEPTOR, Party.DEALMAKER,
                "", 0.0, 0L, DealTrigger.ON_CONDITION_MET, invalidLight)));

        assertTrue(valid.isEmpty(), () -> String.join(" ", valid));
        assertTrue(invalid.stream().anyMatch(error -> error.contains("light-level")));
    }

    @Test
    void permitsSelfTargetedConsequencesButNotSelfTransfers() {
        var effects = DealPolicy.validateClauses(List.of(
                new DealClause(ClauseKind.SET_ON_FIRE_SECONDS, Party.ACCEPTOR, Party.ACCEPTOR,
                        "", 5.0, 0L, DealTrigger.ON_CONDITION_MET, DealCondition.ALWAYS),
                new DealClause(ClauseKind.DEAL_DAMAGE_AMOUNT, Party.ACCEPTOR, Party.ACCEPTOR,
                        "", 2.0, 0L, DealTrigger.ON_CONDITION_MET, DealCondition.ALWAYS),
                new DealClause(ClauseKind.KILL_PLAYER, Party.ACCEPTOR, Party.ACCEPTOR,
                        "", 0.0, 0L, DealTrigger.ON_CONDITION_MET, DealCondition.ALWAYS)));
        var transfer = DealPolicy.validateClauses(List.of(new DealClause(
                ClauseKind.TRANSFER_RESOURCE_AMOUNT, Party.ACCEPTOR, Party.ACCEPTOR,
                "magicule", 1.0, 0L, DealTrigger.ON_ACCEPTANCE, DealCondition.ALWAYS)));

        assertTrue(effects.isEmpty(), () -> String.join(" ", effects));
        assertTrue(transfer.stream().anyMatch(error -> error.contains("same party")));
    }

    @Test
    void acceptsConditionalEndDealAndRejectsAcceptanceEnding() {
        DealCondition harm = new DealCondition(DealConditionType.PARTY_HARMED_PARTY,
                Party.DEALMAKER, "ACCEPTOR", 1);
        var valid = DealPolicy.validateClauses(List.of(
                new DealClause(ClauseKind.KILL_PLAYER, Party.ACCEPTOR, Party.DEALMAKER,
                        "", 0.0, 0L, DealTrigger.ON_CONDITION_MET,
                        new DealCondition(DealConditionType.PARTY_HARMED_PARTY, Party.ACCEPTOR, "DEALMAKER", 1)),
                new DealClause(ClauseKind.END_DEAL, Party.DEALMAKER, Party.ACCEPTOR,
                        "", 0.0, 0L, DealTrigger.ON_CONDITION_MET, harm)));
        var invalid = DealPolicy.validateClauses(List.of(new DealClause(
                ClauseKind.END_DEAL, Party.DEALMAKER, Party.ACCEPTOR,
                "", 0.0, 0L, DealTrigger.ON_ACCEPTANCE, DealCondition.ALWAYS)));

        assertTrue(valid.isEmpty(), () -> String.join(" ", valid));
        assertTrue(invalid.stream().anyMatch(error -> error.contains("acceptance")));
    }
}
