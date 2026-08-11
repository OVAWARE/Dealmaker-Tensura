package com.github.ovaware.dealmaker.deal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deliberately small phase-one parser. A future AI adapter must return the same clauses and
 * pass DealPolicy; model output is never treated as code, a command, or a registry lookup.
 */
public final class ConservativeEnglishParser implements DealParser {
    private static final Pattern UNIQUES_FOR_ATTRIBUTE_PERCENT = Pattern.compile(
            "you\\s+will\\s+give\\s+me\\s+all\\s+(?:of\\s+)?your\\s+(?:unique|unqiue)(?:s| skills)?\\s*,?\\s*(?:and|in return)\\s+(?:in return\\s+)?i\\s+will\\s+give\\s+you\\s+(\\d+(?:\\.\\d+)?)%\\s+of\\s+my\\s+(strength|health)(?:\\s+stat)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIQUES_FOR_ATTRIBUTE_AMOUNT = Pattern.compile(
            "you\\s+will\\s+give\\s+me\\s+all\\s+(?:of\\s+)?your\\s+(?:unique|unqiue)(?:s| skills)?\\s*,?\\s*(?:and|in return)\\s+(?:in return\\s+)?i\\s+will\\s+give\\s+you\\s+(\\d+(?:\\.\\d+)?)\\s+(strength|health)(?:\\s+stat)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DAILY_PAYMENT = Pattern.compile(
            "every\\s+(?:in[- ]game\\s+)?day\\s+you\\s+will\\s+pay\\s+(?:me\\s+)?(\\d+)\\s+([a-z0-9_.:/ -]+?)(?:\\s*[,.]|\\s+if\\s+|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SOUL_DEFAULT = Pattern.compile(
            "if\\s+you\\s+do\\s+not\\s+have\\s+(?:them|the\\s+items?|the\\s+[a-z0-9_.:/ -]+)\\s*,?\\s*your\\s+soul\\s+is\\s+forfeit",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> ITEMS = Map.of(
            "diamond", "minecraft:diamond", "diamonds", "minecraft:diamond",
            "gold ingot", "minecraft:gold_ingot", "gold ingots", "minecraft:gold_ingot",
            "emerald", "minecraft:emerald", "emeralds", "minecraft:emerald"
    );

    @Override
    public ParseResult parse(String text) {
        List<String> errors = new ArrayList<>(DealPolicy.validateText(text));
        if (!errors.isEmpty()) return new ParseResult(List.of(), errors);

        List<DealClause> clauses = new ArrayList<>();
        Matcher exchange = UNIQUES_FOR_ATTRIBUTE_PERCENT.matcher(text);
        if (exchange.find()) {
            double percent = Double.parseDouble(exchange.group(1));
            clauses.add(new DealClause(ClauseKind.TRANSFER_ALL_SKILLS_IN_CATEGORY,
                    Party.ACCEPTOR, Party.DEALMAKER, "unique", 0.0, 0L));
            clauses.add(new DealClause(ClauseKind.TRANSFER_ATTRIBUTE_PERCENT,
                    Party.DEALMAKER, Party.ACCEPTOR, attributeId(exchange.group(2)), percent, 0L));
        } else {
            Matcher amountExchange = UNIQUES_FOR_ATTRIBUTE_AMOUNT.matcher(text);
            if (amountExchange.find()) {
                clauses.add(new DealClause(ClauseKind.TRANSFER_ALL_SKILLS_IN_CATEGORY,
                        Party.ACCEPTOR, Party.DEALMAKER, "unique", 0.0, 0L));
                clauses.add(new DealClause(ClauseKind.TRANSFER_ATTRIBUTE_AMOUNT,
                        Party.DEALMAKER, Party.ACCEPTOR, attributeId(amountExchange.group(2)),
                        Double.parseDouble(amountExchange.group(1)), 0L));
            }
        }

        Matcher payment = DAILY_PAYMENT.matcher(text);
        if (payment.find()) {
            String rawItem = payment.group(2).trim().toLowerCase(Locale.ROOT);
            String itemId = rawItem.contains(":") ? rawItem : ITEMS.get(rawItem);
            if (itemId == null) errors.add("That item name is not in the phase-one allowlist; use a namespaced item id.");
            else clauses.add(new DealClause(ClauseKind.TRANSFER_ITEM_AMOUNT,
                    Party.ACCEPTOR, Party.DEALMAKER, itemId, Double.parseDouble(payment.group(1)), 24_000L,
                    DealTrigger.ON_RECURRING_DUE, DealCondition.ALWAYS));
        }
        if (SOUL_DEFAULT.matcher(text).find()) {
            clauses.add(new DealClause(ClauseKind.FORFEIT_SOUL,
                    Party.ACCEPTOR, Party.DEALMAKER, "", 0.0, 24_000L));
        }

        if (clauses.isEmpty() && errors.isEmpty()) {
            errors.add("This wording is not supported yet; no deal was created.");
        }
        errors.addAll(DealPolicy.validateClauses(clauses));
        return new ParseResult(List.copyOf(clauses), List.copyOf(errors));
    }

    private static String attributeId(String name) {
        return "health".equalsIgnoreCase(name) ? "minecraft:generic.max_health" : "minecraft:generic.attack_damage";
    }
}
