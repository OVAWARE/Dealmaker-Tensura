package com.github.ovaware.dealmaker.ai;

import com.github.ovaware.dealmaker.deal.ClauseKind;
import com.github.ovaware.dealmaker.deal.DealTrigger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiContractProtocolTest {
    @Test
    void arbitraryWildcardAxisCombinationIsPreserved() {
        var decoded = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_WITHIN_COORDINATE_RADIUS","party":"ACCEPTOR","assetId":"","amount":0,"slot":"","dimension":"minecraft:overworld","x":0,"y":0,"z":0,"radius":100,"useX":true,"useY":true,"useZ":true,"negated":true,"logic":"ALL","additionalConditions":[]}}
                ]}
                """);
        var repaired = AiContractProtocol.repairBooleanSplit("if you leave 100 blocks of ~,~,0 you die", decoded);
        var condition = repaired.clauses().getFirst().condition();

        assertFalse(condition.useX());
        assertFalse(condition.useY());
        assertTrue(condition.useZ());
    }

    @Test
    void twoCoordinateWordingEnablesHorizontalOnlyMode() {
        var decoded = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_WITHIN_COORDINATE_RADIUS","party":"ACCEPTOR","assetId":"","amount":0,"slot":"","dimension":"minecraft:overworld","x":0,"y":0,"z":0,"radius":100,"useX":true,"useY":true,"useZ":true,"negated":true,"logic":"ALL","additionalConditions":[]}}
                ]}
                """);
        var repaired = AiContractProtocol.repairBooleanSplit("if you leave 100 blocks of 0,0 you die", decoded);

        assertTrue(repaired.accepted(), () -> String.join(" ", repaired.errors()));
        assertTrue(repaired.clauses().getFirst().condition().useX());
        assertFalse(repaired.clauses().getFirst().condition().useY());
        assertTrue(repaired.clauses().getFirst().condition().useZ());
        assertTrue(repaired.clauses().getFirst().condition().negated());
    }

    @Test
    void canonicalSchemaDoesNotAdvertiseComposableLegacyAliases() {
        String schema = AiContractProtocol.schema().toString();
        assertFalse(schema.contains("PARTY_NOT_IN_DIMENSION"));
        assertFalse(schema.contains("PARTY_LACKS_ITEM"));
        assertFalse(schema.contains("PARTY_OUTSIDE_COORDINATE_RADIUS"));
        assertFalse(schema.contains("RECURRING_ITEM_PAYMENT"));
        assertFalse(schema.contains("TRANSFER_EP_AMOUNT"));
        assertFalse(schema.contains("TRANSFER_ALL_UNIQUE_SKILLS"));
    }

    @Test
    void normalizesLegacyNegativeAndRecurringAliases() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"RECURRING_ITEM_PAYMENT","from":"ACCEPTOR","to":"DEALMAKER","assetId":"minecraft:diamond","amount":10,"periodTicks":24000,"trigger":"ON_RECURRING_DUE","condition":{"type":"PARTY_LACKS_ITEM","party":"ACCEPTOR","assetId":"minecraft:emerald","amount":1}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals(ClauseKind.TRANSFER_ITEM_AMOUNT, result.clauses().getFirst().kind());
        assertEquals(com.github.ovaware.dealmaker.deal.DealConditionType.PARTY_HAS_ITEM,
                result.clauses().getFirst().condition().type());
        assertTrue(result.clauses().getFirst().condition().negated());
    }

    @Test
    void combinesTheObservedCrouchAndChatSplitIntoOneAllCondition() {
        var decoded = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"CHAT_MESSAGE_CONTAINS","party":"DEALMAKER","assetId":"chrouch","amount":0}},
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_STAT_AT_LEAST","party":"ACCEPTOR","assetId":"minecraft:crouch_one_cm","amount":1}}
                ]}
                """);
        var repaired = AiContractProtocol.repairBooleanSplit("if your crouching AND i say chrouch you die", decoded);

        assertTrue(repaired.accepted(), () -> String.join(" ", repaired.errors()));
        assertEquals(1, repaired.clauses().size());
        var condition = repaired.clauses().getFirst().condition();
        assertEquals(com.github.ovaware.dealmaker.deal.ConditionLogic.ALL, condition.logic());
        assertEquals(2, condition.terms().size());
        assertTrue(condition.terms().stream().anyMatch(term ->
                term.type() == com.github.ovaware.dealmaker.deal.DealConditionType.PARTY_IS_CROUCHING));
        assertTrue(condition.terms().stream().anyMatch(term ->
                term.type() == com.github.ovaware.dealmaker.deal.DealConditionType.CHAT_MESSAGE_CONTAINS));
    }

    @Test
    void decodesOrAndNotConditionGroups() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_IS_CROUCHING","party":"ACCEPTOR","assetId":"","amount":0,"slot":"","dimension":"","x":0,"y":0,"z":0,"radius":0,"negated":true,"logic":"ANY","additionalConditions":[{"type":"CHAT_MESSAGE_CONTAINS","party":"DEALMAKER","assetId":"duck","amount":0,"slot":"","dimension":"","x":0,"y":0,"z":0,"radius":0,"negated":false}]}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals(com.github.ovaware.dealmaker.deal.ConditionLogic.ANY, result.clauses().getFirst().condition().logic());
        assertTrue(result.clauses().getFirst().condition().negated());
    }

    @Test
    void ignoresCopiedCoordinateAxisFlagsOnNonSpatialTerms() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_WITHIN_COORDINATE_RADIUS","party":"ACCEPTOR","assetId":"","amount":0,"slot":"","dimension":"minecraft:overworld","x":0,"y":0,"z":0,"radius":10,"useX":true,"useY":false,"useZ":true,"negated":true,"logic":"ALL","additionalConditions":[{"type":"PARTY_IS_CROUCHING","party":"ACCEPTOR","assetId":"","amount":0,"slot":"","dimension":"","x":0,"y":0,"z":0,"radius":0,"useX":true,"useY":false,"useZ":true,"negated":false}]}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        var terms = result.clauses().getFirst().condition().terms();
        assertFalse(terms.getFirst().useY());
        assertTrue(terms.get(1).useX());
        assertTrue(terms.get(1).useY());
        assertTrue(terms.get(1).useZ());
    }

    @Test
    void repairsAnIncompleteCoordinateTermFromClearContractText() {
        var decoded = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_WITHIN_COORDINATE_RADIUS","party":"ACCEPTOR","assetId":"","amount":0,"slot":"","dimension":"","x":0,"y":0,"z":0,"radius":0,"useX":true,"useY":true,"useZ":true,"negated":true,"logic":"ALL","additionalConditions":[{"type":"PARTY_IS_CROUCHING","party":"ACCEPTOR","assetId":"","amount":0,"slot":"","dimension":"","x":0,"y":0,"z":0,"radius":0,"useX":true,"useY":false,"useZ":true,"negated":false}]}}
                ]}
                """);
        var repaired = AiContractProtocol.repairBooleanSplit("if you leave 10 blocks of 0,0 and are chrouching you die", decoded);

        assertTrue(repaired.accepted(), () -> String.join(" ", repaired.errors()));
        var terms = repaired.clauses().getFirst().condition().terms();
        assertEquals(10.0, terms.getFirst().radius());
        assertTrue(terms.getFirst().useX());
        assertFalse(terms.getFirst().useY());
        assertTrue(terms.getFirst().useZ());
    }

    @Test
    void normalizesObservedGeminiSpatialPredicateAlias() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"SPATIAL_PREDICATE","party":"ACCEPTOR","assetId":"","amount":0,"dimension":"minecraft:overworld","x":0,"y":0,"z":0,"radius":500,"invert":true}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals(com.github.ovaware.dealmaker.deal.DealConditionType.PARTY_WITHIN_COORDINATE_RADIUS,
                result.clauses().getFirst().condition().type());
        assertTrue(result.clauses().getFirst().condition().negated());
    }

    @Test
    void repairsDimensionPredicateMislabelledAsPlayerDeath() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_DIES","party":"ACCEPTOR","assetId":"","amount":0,"dimension":"minecraft:the_end","x":0,"y":0,"z":0,"radius":0}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals(com.github.ovaware.dealmaker.deal.DealConditionType.PARTY_IN_DIMENSION,
                result.clauses().getFirst().condition().type());
    }

    @Test
    void repairsRecurringSlotPlacedOnCondition() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"TRANSFER_INVENTORY_SLOT","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":1200,"trigger":"ON_RECURRING_DUE","condition":{"type":"PARTY_HOLDS_ANY_ITEM","party":"ACCEPTOR","assetId":"","amount":0,"slot":"MAIN_HAND"}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals("MAIN_HAND", result.clauses().getFirst().assetId());
    }

    @Test
    void convertsToGeminiOpenApiSchema() {
        var schema = AiContractProtocol.googleResponseSchema();

        assertEquals("OBJECT", schema.get("type").getAsString());
        assertFalse(schema.has("additionalProperties"));
        var clauses = schema.getAsJsonObject("properties").getAsJsonObject("clauses");
        assertEquals("ARRAY", clauses.get("type").getAsString());
        assertEquals("OBJECT", clauses.getAsJsonObject("items").get("type").getAsString());
        assertTrue(schema.has("propertyOrdering"));
    }

    @Test
    void decodesAValidStrictResponse() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"RECURRING_ITEM_PAYMENT","from":"ACCEPTOR","to":"DEALMAKER","assetId":"minecraft:diamond","amount":10,"periodTicks":24000},
                  {"kind":"FORFEIT_SOUL","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals(ClauseKind.FORFEIT_SOUL, result.clauses().get(1).kind());
    }

    @Test
    void decodesMarkdownFencedJsonFromGeminiFallback() {
        var result = AiContractProtocol.decode("""
                Here is the compiled program:
                ```json
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"TRANSFER_ATTRIBUTE_AMOUNT","from":"DEALMAKER","to":"ACCEPTOR","assetId":"minecraft:generic.max_health","amount":20,"periodTicks":0,"trigger":"ON_ACCEPTANCE","condition":{"type":"ALWAYS","party":"ACCEPTOR","assetId":"","amount":0}}
                ]}
                ```
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals(ClauseKind.TRANSFER_ATTRIBUTE_AMOUNT, result.clauses().getFirst().kind());
    }

    @Test
    void acceptsAnAttributeTransferInEitherDirection() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"TRANSFER_ATTRIBUTE_PERCENT","from":"ACCEPTOR","to":"DEALMAKER","assetId":"minecraft:generic.attack_damage","amount":5,"periodTicks":0}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
    }

    @Test
    void acceptsStandaloneSoulForfeiture() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"FORFEIT_SOUL","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
    }

    @Test
    void acceptsUltimateMagiculeAndHarmBreachProgram() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"TRANSFER_ALL_ULTIMATE_SKILLS","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_ACCEPTANCE","condition":{"type":"ALWAYS","party":"ACCEPTOR","assetId":"","amount":0}},
                  {"kind":"TRANSFER_MAGICULE_AMOUNT","from":"DEALMAKER","to":"ACCEPTOR","assetId":"","amount":100,"periodTicks":0,"trigger":"ON_ACCEPTANCE","condition":{"type":"ALWAYS","party":"ACCEPTOR","assetId":"","amount":0}},
                  {"kind":"FORFEIT_SOUL","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_BREACH","condition":{"type":"PARTY_HARMED_PARTY","party":"ACCEPTOR","assetId":"DEALMAKER","amount":1}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals(ClauseKind.TRANSFER_ALL_SKILLS_IN_CATEGORY, result.clauses().getFirst().kind());
        assertEquals("ultimate", result.clauses().getFirst().assetId());
        assertEquals(ClauseKind.TRANSFER_RESOURCE_AMOUNT, result.clauses().get(1).kind());
        assertEquals("magicule", result.clauses().get(1).assetId());
    }

    @Test
    void acceptsGeminisShortStatConditionAlias() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"STAT_INCREASED","party":"ACCEPTOR","assetId":"minecraft:bell_ring","amount":1}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
    }

    @Test
    void acceptsAnInventoryAutomationThatTakesAllMatchingItems() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"TRANSFER_ALL_MATCHING_ITEMS","from":"ACCEPTOR","to":"DEALMAKER","assetId":"minecraft:diamond","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_HAS_ITEM","party":"ACCEPTOR","assetId":"minecraft:diamond","amount":1}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals(ClauseKind.TRANSFER_ALL_MATCHING_ITEMS, result.clauses().getFirst().kind());
    }

    @Test
    void acceptsADynamicallyNamedTaggedHeldItemDeathCondition() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_HAS_ITEM_IN_SLOT","party":"ACCEPTOR","assetId":"#modpack:runtime_category","amount":1,"slot":"MAIN_HAND"}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals("#modpack:runtime_category", result.clauses().getFirst().condition().assetId());
        assertEquals("MAIN_HAND", result.clauses().getFirst().condition().slot());
    }

    @Test
    void acceptsAnyPlayerChatTrigger() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"CHAT_MESSAGE_CONTAINS","party":"ANY_PLAYER","assetId":"die","amount":0}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
    }

    @Test
    void rejectsAChatPhraseIncorrectlyPlacedOnKillClause() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"u die","amount":1,"periodTicks":12,"trigger":"ON_CONDITION_MET","condition":{"type":"CHAT_MESSAGE_CONTAINS","party":"DEALMAKER","assetId":"u die","amount":0}}
                ]}
                """);

        assertFalse(result.accepted());
        assertTrue(result.clauses().isEmpty());
    }

    @Test
    void acceptsDeathConditionAndDamageRedirection() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_DIES","party":"DEALMAKER","assetId":"","amount":0}},
                  {"kind":"REDIRECT_DAMAGE_PERCENT","from":"DEALMAKER","to":"ACCEPTOR","assetId":"","amount":100,"periodTicks":0,"trigger":"ON_ACCEPTANCE","condition":{"type":"ALWAYS","party":"ACCEPTOR","assetId":"","amount":0}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
    }

    @Test
    void acceptsSpecificAndCategorySkillUseConditions() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_USES_SKILL","party":"ACCEPTOR","assetId":"tensura:great_sage","amount":0}},
                  {"kind":"KILL_PLAYER","from":"DEALMAKER","to":"ACCEPTOR","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_USES_MAGIC","party":"DEALMAKER","assetId":"","amount":0}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
    }

    @Test
    void acceptsASeparateSkillShareClause() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"SHARE_SKILL","from":"DEALMAKER","to":"ACCEPTOR","assetId":"tensura:sloth","amount":0,"periodTicks":0,"trigger":"ON_ACCEPTANCE","condition":{"type":"ALWAYS","party":"ACCEPTOR","assetId":"","amount":0}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
    }

    @Test
    void acceptsAuraPercentageTransfer() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"TRANSFER_AURA_PERCENT","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":50,"periodTicks":0,"trigger":"ON_ACCEPTANCE","condition":{"type":"ALWAYS","party":"ACCEPTOR","assetId":"","amount":0}}
                ]}
                """);
        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
    }

    @Test
    void honorsExplicitUnsupportedResponse() {
        var result = AiContractProtocol.decode("""
                {"supported":false,"rejectionReason":"Race transfer is not supported.","clauses":[]}
                """);

        assertFalse(result.accepted());
        assertEquals("Race transfer is not supported.", result.errors().getFirst());
    }

    @Test
    void decodesMpDrainAndLaterDealAcceptanceCondition() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"DRAIN_RESOURCE_PERCENT","from":"ACCEPTOR","to":"DEALMAKER","assetId":"mp","amount":50,"periodTicks":0,"trigger":"ON_ACCEPTANCE","condition":{"type":"ALWAYS","party":"ACCEPTOR","assetId":"","amount":0}},
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_BREACH","condition":{"type":"PARTY_ACCEPTED_OTHER_DEAL","party":"ACCEPTOR","assetId":"OTHER_THAN_CURRENT_DEALMAKER","amount":0}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals(ClauseKind.DRAIN_RESOURCE_PERCENT, result.clauses().getFirst().kind());
        assertEquals("magicule", result.clauses().getFirst().assetId());
        assertEquals(com.github.ovaware.dealmaker.deal.DealConditionType.PARTY_ACCEPTED_OTHER_DEAL,
                result.clauses().get(1).condition().type());
    }

    @Test
    void normalizesProviderWalkingAliasToTheMovementStat() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"TRANSFER_INVENTORY_SLOT","from":"ACCEPTOR","to":"DEALMAKER","assetId":"MAIN_HAND","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_IS_WALKING","party":"ACCEPTOR","assetId":"","amount":0,"slot":"","dimension":"","x":0,"y":0,"z":0,"radius":0,"useX":false,"useY":false,"useZ":false,"negated":false,"logic":"ALL","additionalConditions":[]}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        var condition = result.clauses().getFirst().condition();
        assertEquals(com.github.ovaware.dealmaker.deal.DealConditionType.PARTY_STAT_INCREASED, condition.type());
        assertEquals("minecraft:walk_one_cm", condition.assetId());
        assertEquals(1, condition.amount());
    }

    @Test
    void rejectsUnknownClauseKinds() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"RUN_SERVER_COMMAND","from":"ACCEPTOR","to":"DEALMAKER","assetId":"minecraft:op","amount":1,"periodTicks":0}
                ]}
                """);

        assertFalse(result.accepted());
        assertTrue(result.clauses().isEmpty());
    }

    @Test
    void acceptsHarmKillAndEndDealPair() {
        var result = AiContractProtocol.decode("""
                {"supported":true,"rejectionReason":"","clauses":[
                  {"kind":"KILL_PLAYER","from":"ACCEPTOR","to":"DEALMAKER","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_HARMED_PARTY","party":"ACCEPTOR","assetId":"DEALMAKER","amount":1,"slot":"","dimension":"","x":0,"y":0,"z":0,"radius":0,"useX":true,"useY":true,"useZ":true,"negated":false,"logic":"ALL","additionalConditions":[]}},
                  {"kind":"END_DEAL","from":"DEALMAKER","to":"ACCEPTOR","assetId":"","amount":0,"periodTicks":0,"trigger":"ON_CONDITION_MET","condition":{"type":"PARTY_HARMED_PARTY","party":"DEALMAKER","assetId":"ACCEPTOR","amount":1,"slot":"","dimension":"","x":0,"y":0,"z":0,"radius":0,"useX":true,"useY":true,"useZ":true,"negated":false,"logic":"ALL","additionalConditions":[]}}
                ]}
                """);

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals(2, result.clauses().size());
        assertEquals(ClauseKind.KILL_PLAYER, result.clauses().getFirst().kind());
        assertEquals(ClauseKind.END_DEAL, result.clauses().get(1).kind());
        assertEquals(DealTrigger.ON_CONDITION_MET, result.clauses().get(1).trigger());
        assertEquals(com.github.ovaware.dealmaker.deal.DealConditionType.PARTY_HARMED_PARTY,
                result.clauses().get(1).condition().type());
        assertEquals(com.github.ovaware.dealmaker.deal.Party.DEALMAKER, result.clauses().get(1).condition().party());
        assertEquals("ACCEPTOR", result.clauses().get(1).condition().assetId());
    }
}
