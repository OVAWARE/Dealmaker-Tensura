package com.github.ovaware.dealmaker.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/** A plain written-book template stored in the Devil Bargen ledger. */
public record LedgerBook(String title, String author, List<String> pages) {
    public static final int MAX_ENTRIES = 16;
    public static final int MAX_TITLE_LENGTH = 32;
    public static final int MAX_AUTHOR_LENGTH = 32;
    public static final int MAX_PAGES = 50;
    public static final int MAX_PAGE_LENGTH = 1024;

    public static Codec<LedgerBook> codec() {
        return CodecHolder.CODEC;
    }

    private static final class CodecHolder {
        private static final Codec<LedgerBook> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("title").forGetter(LedgerBook::title),
                Codec.STRING.fieldOf("author").forGetter(LedgerBook::author),
                Codec.STRING.listOf().fieldOf("pages").forGetter(LedgerBook::pages)
        ).apply(instance, LedgerBook::sanitized));
    }

    public static LedgerBook sanitized(String title, String author, List<String> pages) {
        String cleanTitle = clamp(title == null || title.isBlank() ? "Contract" : title, MAX_TITLE_LENGTH);
        String cleanAuthor = clamp(author == null || author.isBlank() ? "Devil Bargen" : author, MAX_AUTHOR_LENGTH);
        List<String> cleanPages = pages == null ? List.of() : pages.stream()
                .filter(page -> page != null && !page.isBlank())
                .map(page -> clamp(page, MAX_PAGE_LENGTH))
                .limit(MAX_PAGES)
                .toList();
        if (cleanPages.isEmpty()) cleanPages = List.of(" ");
        return new LedgerBook(cleanTitle, cleanAuthor, List.copyOf(cleanPages));
    }

    private static String clamp(String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
