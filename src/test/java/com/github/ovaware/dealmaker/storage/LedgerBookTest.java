package com.github.ovaware.dealmaker.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LedgerBookTest {
    @Test
    void sanitizesBlankAndOversizedFields() {
        LedgerBook book = LedgerBook.sanitized("  ", "", List.of("", "  hello  ", "x".repeat(2000)));
        assertEquals("Contract", book.title());
        assertEquals("Dealmaker", book.author());
        assertEquals(2, book.pages().size());
        assertEquals("hello", book.pages().get(0));
        assertEquals(LedgerBook.MAX_PAGE_LENGTH, book.pages().get(1).length());
    }

    @Test
    void keepsProvidedTitleAndAuthor() {
        LedgerBook book = LedgerBook.sanitized("Pact", "Rimuru", List.of("Give me your soul."));
        assertEquals("Pact", book.title());
        assertEquals("Rimuru", book.author());
        assertEquals(List.of("Give me your soul."), book.pages());
    }
}
