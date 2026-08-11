package com.github.ovaware.dealmaker.deal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConservativeEnglishParserTest {
    private final ConservativeEnglishParser parser = new ConservativeEnglishParser();

    @Test
    void rejectsSkillTransferExample() {
        ParseResult result = parser.parse("You will give me all your uniques, in return I will give you 5% of my strength stat");

        assertFalse(result.accepted());
    }

    @Test
    void rejectsSkillBackedHealthPercentExample() {
        ParseResult result = parser.parse("You will give me all your unqiue skills in return I will give you 50% of my health");

        assertFalse(result.accepted());
    }

    @Test
    void rejectsSkillBackedHealthAmountExample() {
        ParseResult result = parser.parse("You will give me all your unique skills in return I will give you 20 health");

        assertFalse(result.accepted());
    }

    @Test
    void parsesDailyPaymentWithSoulDefault() {
        ParseResult result = parser.parse("Every in game day you will pay 10 diamonds, if you do not have them your soul is forfeit");

        assertTrue(result.accepted(), () -> String.join(" ", result.errors()));
        assertEquals("minecraft:diamond", result.clauses().get(0).assetId());
        assertEquals(24_000L, result.clauses().get(0).periodTicks());
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
