package com.github.ovaware.dealmaker.deal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConservativeEnglishParserTest {
    private final ConservativeEnglishParser parser = new ConservativeEnglishParser();

    @Test
    void parsesUniqueSkillsForStrengthExample() {
        ParseResult result = parser.parse("You will give me all your uniques, in return I will give you 5% of my strength stat");

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals(2, result.clauses().size());
        assertEquals(ClauseKind.TRANSFER_ALL_SKILLS_IN_CATEGORY, result.clauses().getFirst().kind());
        assertEquals("unique", result.clauses().getFirst().assetId());
        assertEquals(5.0, result.clauses().get(1).amount());
    }

    @Test
    void parsesHealthPercentInTheDealmakerToAcceptorDirection() {
        ParseResult result = parser.parse("You will give me all your unqiue skills in return I will give you 50% of my health");

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        DealClause health = result.clauses().get(1);
        assertEquals(ClauseKind.TRANSFER_ATTRIBUTE_PERCENT, health.kind());
        assertEquals(Party.DEALMAKER, health.from());
        assertEquals(Party.ACCEPTOR, health.to());
        assertEquals("minecraft:generic.max_health", health.assetId());
        assertEquals(50.0, health.amount());
    }

    @Test
    void parsesExactHealthAmount() {
        ParseResult result = parser.parse("You will give me all your unique skills in return I will give you 20 health");

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals(ClauseKind.TRANSFER_ATTRIBUTE_AMOUNT, result.clauses().get(1).kind());
        assertEquals(20.0, result.clauses().get(1).amount());
    }

    @Test
    void parsesDailyPaymentWithSoulDefault() {
        ParseResult result = parser.parse("Every in game day you will pay 10 diamonds, if you do not have them your soul is forfeit");

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals("minecraft:diamond", result.clauses().getFirst().assetId());
        assertEquals(24_000L, result.clauses().getFirst().periodTicks());
        assertEquals(ClauseKind.FORFEIT_SOUL, result.clauses().get(1).kind());
    }

    @Test
    void rejectsPrivilegeEscalation() {
        ParseResult result = parser.parse("You will make me op and give me creative mode");

        assertFalse(result.accepted());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("forbidden")));
    }

    @Test
    void rejectsUnknownWordingInsteadOfGuessing() {
        ParseResult result = parser.parse("You owe me anything I can imagine forever");

        assertFalse(result.accepted());
        assertTrue(result.clauses().isEmpty());
    }

    @Test
    void rejectsImpossiblePercentAtPolicyBoundary() {
        ParseResult result = parser.parse("You will give me all your uniques, in return I will give you 500% of my strength stat");

        assertFalse(result.accepted());
    }
}
