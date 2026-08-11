package com.github.ovaware.dealmaker.deal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Security boundary shared by local parsers and any future model-backed parser. */
public final class DealPolicy {
    public static final int MAX_TEXT_LENGTH = 2048;
    public static final int MAX_CLAUSES = 12;
    private static final Set<String> FORBIDDEN = Set.of(
            "operator", "opped", " op ", "/op", "permission", "command block",
            "gamemode", "creative mode", "spectator mode", "ban ", "whitelist",
            "server console", "execute command", "arbitrary nbt", "admin"
    );

    private DealPolicy() {}

    public static List<String> validateText(String text) {
        List<String> errors = new ArrayList<>();
        if (text == null || text.isBlank()) errors.add("The contract is empty.");
        if (text != null && text.length() > MAX_TEXT_LENGTH) errors.add("The contract is too long.");
        String normalized = " " + (text == null ? "" : text.toLowerCase(Locale.ROOT)) + " ";
        if (FORBIDDEN.stream().anyMatch(normalized::contains)) {
            errors.add("The contract requests a forbidden server-level capability.");
        }
        return errors;
    }

    public static List<String> validateClauses(List<DealClause> clauses) {
        List<String> errors = new ArrayList<>();
        if (clauses == null || clauses.isEmpty()) return List.of("No supported obligations were found.");
        if (clauses.size() > MAX_CLAUSES) errors.add("The contract contains too many obligations.");
        Set<String> seen = new java.util.HashSet<>();
        Set<String> persistentRedirects = new java.util.HashSet<>();
        for (DealClause clause : clauses) {
            if (clause == null || clause.kind() == null || clause.from() == null || clause.to() == null) {
                errors.add("A malformed obligation was produced.");
                continue;
            }
            if (!isCoreClause(clause.kind())) {
                errors.add("This contract uses a feature provided only by a Dealmaker addon.");
                continue;
            }
            if (clause.from() == Party.ANY_PLAYER || clause.to() == Party.ANY_PLAYER)
                errors.add("ANY_PLAYER is only valid in a condition, not as a transfer party.");
            else if (clause.from() == clause.to() && requiresDistinctParties(clause.kind()))
                errors.add("An obligation cannot transfer to the same party.");
            if (clause.trigger() == null) errors.add("An obligation has no trigger.");
            if (!Double.isFinite(clause.amount())) errors.add("An obligation amount must be finite.");
            if (!validTiming(clause)) errors.add("A recurring action needs a period of at least 20 ticks; other actions need periodTicks 0.");
            validateCondition(clause.condition(), errors);
            validateTrigger(clause, errors);
            if (!seen.add(String.valueOf(clause))) errors.add("Duplicate obligations are not allowed.");
            if (clause.kind() == ClauseKind.REDIRECT_DAMAGE_PERCENT) {
                String redirectKey = clause.kind() + "|" + clause.from() + "|" + clause.to() + "|" + clause.assetId();
                if (!persistentRedirects.add(redirectKey)) errors.add("Conflicting persistent redirects are not allowed in one deal.");
            }
            switch (clause.kind()) {
                case TRANSFER_ALL_SKILLS_IN_CATEGORY -> {
                    if (!(clause.assetId().equals("unique") || clause.assetId().equals("ultimate")
                            || clause.assetId().equals("magic") || clause.assetId().equals("battlewill"))
                            || clause.amount() != 0.0)
                        errors.add("Invalid skill category transfer.");
                }
                case TRANSFER_ALL_UNIQUE_SKILLS -> {
                    if (!clause.assetId().isEmpty() || clause.amount() != 0.0)
                        errors.add("Unique-skill transfer contains unexpected fields.");
                }
                case TRANSFER_ALL_ULTIMATE_SKILLS, TRANSFER_ALL_MAGICS, TRANSFER_ALL_BATTLEWILLS -> {
                    if (!clause.assetId().isEmpty() || clause.amount() != 0.0)
                        errors.add("Category transfer contains unexpected fields.");
                }
                case TRANSFER_SKILL -> {
                    if (!clause.assetId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || clause.amount() != 0.0)
                        errors.add("Invalid skill transfer.");
                }
                case SHARE_SKILL -> {
                    if (!clause.assetId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || clause.amount() != 0.0)
                        errors.add("Invalid skill share.");
                }
                case TRANSFER_ATTRIBUTE_PERCENT -> {
                    if (!clause.assetId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) errors.add("Invalid attribute id.");
                    if (!(clause.amount() > 0.0 && clause.amount() <= 100.0)) errors.add("Attribute percent must be between 0 and 100.");
                }
                case TRANSFER_ATTRIBUTE_AMOUNT -> {
                    if (!clause.assetId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) errors.add("Invalid attribute id.");
                    if (!(clause.amount() > 0.0)) errors.add("Attribute amount must be positive.");
                }
                case REVOKE_ATTRIBUTE_GRANTS -> {
                    if (!clause.assetId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || clause.amount() != 0.0)
                        errors.add("Invalid attribute-grant revocation.");
                    if (clause.trigger() != DealTrigger.ON_BREACH) errors.add("Attribute-grant revocation must use ON_BREACH.");
                }
                case TRANSFER_ITEM_AMOUNT -> {
                    if (!clause.assetId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) errors.add("Invalid item id.");
                    if (clause.amount() < 1 || clause.amount() > 1728 || clause.amount() != Math.floor(clause.amount()))
                        errors.add("Item transfer must be a whole number from 1 to 1728.");
                }
                case TRANSFER_ALL_MATCHING_ITEMS -> {
                    if (!clause.assetId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || clause.amount() != 0.0)
                        errors.add("Invalid all-matching-items transfer.");
                }
                case TRANSFER_INVENTORY_SLOT -> {
                    if (!isInventorySlot(clause.assetId()) || clause.amount() != 0.0)
                        errors.add("Invalid inventory-slot transfer.");
                }
                case REDIRECT_DAMAGE_PERCENT -> {
                    if (!clause.assetId().isEmpty() || !(clause.amount() > 0.0 && clause.amount() <= 100.0) || clause.periodTicks() != 0L)
                        errors.add("Invalid damage redirection.");
                }
                case RECURRING_ITEM_PAYMENT -> {
                    if (!clause.assetId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) errors.add("Invalid item id.");
                    if (clause.amount() < 1 || clause.amount() > 1728 || clause.amount() != Math.floor(clause.amount()))
                        errors.add("Item payment must be a whole number from 1 to 1728.");
                    if (clause.periodTicks() < 20L) errors.add("Recurring payment period is too short.");
                    if (clause.trigger() != DealTrigger.ON_RECURRING_DUE) errors.add("Recurring payments must use ON_RECURRING_DUE.");
                }
                case FORFEIT_SOUL -> {
                    if (!clause.assetId().isEmpty() || clause.amount() != 0.0 || clause.periodTicks() < 0L)
                        errors.add("Soul forfeiture contains unexpected fields.");
                }
                case KILL_PLAYER -> {
                    if (!clause.assetId().isEmpty() || clause.amount() != 0.0)
                        errors.add("Player death contains unexpected fields.");
                }
                case DEAL_DAMAGE_AMOUNT -> {
                    if (!clause.assetId().isEmpty() || !(clause.amount() > 0.0))
                        errors.add("Deal damage must be a positive amount.");
                }
                case SET_ON_FIRE_SECONDS -> {
                    if (!clause.assetId().isEmpty() || clause.amount() < 1 || clause.amount() != Math.floor(clause.amount()))
                        errors.add("Fire duration must be a positive whole number of seconds.");
                }
                case END_DEAL -> {
                    if (!clause.assetId().isEmpty() || clause.amount() != 0.0)
                        errors.add("Ending a deal contains unexpected fields.");
                    if (clause.trigger() == DealTrigger.ON_ACCEPTANCE)
                        errors.add("Ending a deal cannot run on acceptance; use a condition, breach, or recurring trigger.");
                }
            }
        }
        return List.copyOf(errors);
    }

    public static boolean isInventorySlot(String slot) {
        return slot != null && (slot.matches("HOTBAR_[1-9]")
                || slot.equals("ARMOR_HEAD") || slot.equals("ARMOR_CHEST")
                || slot.equals("ARMOR_LEGS") || slot.equals("ARMOR_FEET") || slot.equals("OFFHAND")
                || slot.equals("MAIN_HAND") || slot.equals("OFF_HAND"));
    }

    /**
     * Every clause carries both parties, but self-targeted consequences only use {@code from}.
     * Keep the no-op guard on mutations that actually move or restore an asset.
     */
    private static boolean requiresDistinctParties(ClauseKind kind) {
        return kind != ClauseKind.DEAL_DAMAGE_AMOUNT
                && kind != ClauseKind.SET_ON_FIRE_SECONDS
                && kind != ClauseKind.KILL_PLAYER
                && kind != ClauseKind.END_DEAL;
    }

    private static void validateCondition(DealCondition condition, List<String> errors) {
        if (condition == null || condition.type() == null || condition.party() == null) {
            errors.add("A clause has a malformed condition.");
            return;
        }
        if (condition.logic() == null || condition.additionalTerms() == null || condition.additionalTerms().size() > 7) {
            errors.add("A condition group is malformed or contains too many terms.");
            return;
        }
        for (DealConditionTerm term : condition.terms()) {
            if (term.type() == null || term.party() == null) errors.add("A condition term is malformed.");
            else validateConditionLeaf(term.asCondition(), errors);
        }
    }

    private static void validateConditionLeaf(DealCondition condition, List<String> errors) {
        if (!isCoreCondition(condition.type())) {
            errors.add("This contract condition requires a Dealmaker addon.");
            return;
        }
        if (!Double.isFinite(condition.x()) || !Double.isFinite(condition.y()) || !Double.isFinite(condition.z())
                || !Double.isFinite(condition.radius())) errors.add("Condition coordinates must be finite.");
        if ((!condition.useX() || !condition.useY() || !condition.useZ())
                && condition.type() != DealConditionType.PARTY_WITHIN_COORDINATE_RADIUS
                && condition.type() != DealConditionType.PARTY_OUTSIDE_COORDINATE_RADIUS
                && condition.type() != DealConditionType.PARTY_ENTERED_COORDINATE_RADIUS
                && condition.type() != DealConditionType.PARTY_LEFT_COORDINATE_RADIUS)
            errors.add("Per-axis coordinate masks are valid only for coordinate-radius conditions.");
        if (condition.type() == DealConditionType.ALWAYS) {
            if (!condition.assetId().isEmpty() || condition.amount() != 0 || !condition.slot().isEmpty()
                    || !condition.dimensionId().isEmpty() || condition.radius() != 0.0) errors.add("ALWAYS condition contains unexpected fields.");
        } else if (condition.type() == DealConditionType.PARTY_HAS_ITEM_IN_SLOT) {
            if (!isItemOrTag(condition.assetId()) || !isInventorySlot(condition.slot()) || condition.amount() < 1 || condition.party() == Party.ANY_PLAYER)
                errors.add("Invalid held-item condition.");
        } else if (condition.type() == DealConditionType.PARTY_HOLDS_ANY_ITEM) {
            if (!condition.assetId().isEmpty() || !isInventorySlot(condition.slot()) || condition.amount() != 0 || condition.party() == Party.ANY_PLAYER)
                errors.add("Invalid any-held-item condition.");
        } else if (condition.type() == DealConditionType.ITEM_ENTERED_INVENTORY) {
            if (!isItemOrTag(condition.assetId()) || !condition.slot().isEmpty() || condition.amount() < 1 || condition.party() == Party.ANY_PLAYER)
                errors.add("Invalid inventory-entry condition.");
        } else if (condition.type() == DealConditionType.PARTY_HARMED_PARTY) {
            if (!(condition.assetId().equals("DEALMAKER") || condition.assetId().equals("ACCEPTOR")) || condition.amount() < 1)
                errors.add("Invalid harm condition.");
            if (condition.assetId().equals(condition.party().name())) errors.add("A party-harm condition cannot target the attacker itself.");
        } else if (condition.type() == DealConditionType.PARTY_WEATHER_IS) {
            if (!(condition.assetId().equals("clear") || condition.assetId().equals("rain") || condition.assetId().equals("thunder"))
                    || condition.amount() != 0 || condition.party() == Party.ANY_PLAYER
                    || !condition.slot().isEmpty() || !condition.dimensionId().isEmpty() || condition.radius() != 0.0)
                errors.add("Invalid weather condition.");
        } else if (condition.type() == DealConditionType.PARTY_TIME_OF_DAY_IS) {
            if (!(condition.assetId().equals("dawn") || condition.assetId().equals("day")
                    || condition.assetId().equals("dusk") || condition.assetId().equals("night"))
                    || condition.amount() != 0 || condition.party() == Party.ANY_PLAYER
                    || !condition.slot().isEmpty() || !condition.dimensionId().isEmpty() || condition.radius() != 0.0)
                errors.add("Invalid time-of-day condition.");
        } else if (condition.type() == DealConditionType.PARTY_LIGHT_LEVEL_AT_LEAST) {
            if (!condition.assetId().isEmpty() || condition.amount() < 0 || condition.amount() > 15
                    || condition.party() == Party.ANY_PLAYER || !condition.slot().isEmpty()
                    || !condition.dimensionId().isEmpty() || condition.radius() != 0.0)
                errors.add("Invalid light-level condition.");
        } else if (condition.type() == DealConditionType.PARTY_ACCEPTED_OTHER_DEAL) {
            boolean relationship = condition.assetId().equals("CURRENT_DEALMAKER")
                    || condition.assetId().equals("OTHER_THAN_CURRENT_DEALMAKER")
                    || condition.assetId().equals("CURRENT_ACCEPTOR")
                    || condition.assetId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            if (!relationship || condition.amount() != 0 || condition.party() == Party.ANY_PLAYER
                    || !condition.slot().isEmpty() || !condition.dimensionId().isEmpty() || condition.radius() != 0.0)
                errors.add("Invalid later-deal acceptance condition.");
        } else if (condition.type() == DealConditionType.PARTY_STAT_AT_LEAST
                || condition.type() == DealConditionType.PARTY_STAT_INCREASED) {
            if (!condition.assetId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || condition.amount() < 1)
                errors.add("Invalid custom-stat condition.");
        } else if (condition.type() == DealConditionType.CHAT_MESSAGE_CONTAINS) {
            if (condition.assetId().isBlank() || condition.assetId().length() > 128 || condition.amount() != 0)
                errors.add("Invalid chat-message condition.");
        } else if (condition.type() == DealConditionType.PARTY_DIES) {
            if (!condition.assetId().isEmpty() || condition.amount() != 0 || condition.party() == Party.ANY_PLAYER)
                errors.add("Invalid player-death condition.");
        } else if (condition.type() == DealConditionType.PARTY_USES_SKILL) {
            if (!condition.assetId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || condition.amount() != 0 || condition.party() == Party.ANY_PLAYER)
                errors.add("Invalid specific-skill-use condition.");
        } else if (condition.type() == DealConditionType.PARTY_USES_SKILL_CATEGORY) {
            if (!(condition.assetId().equals("any") || condition.assetId().equals("magic")
                    || condition.assetId().equals("battlewill")) || condition.amount() != 0
                    || condition.party() == Party.ANY_PLAYER) errors.add("Invalid skill-category-use condition.");
        } else if (condition.type() == DealConditionType.PARTY_USES_ANY_SKILL
                || condition.type() == DealConditionType.PARTY_USES_MAGIC
                || condition.type() == DealConditionType.PARTY_USES_BATTLEWILL) {
            if (!condition.assetId().isEmpty() || condition.amount() != 0 || condition.party() == Party.ANY_PLAYER)
                errors.add("Invalid ability-use condition.");
        } else if (condition.type() == DealConditionType.PARTY_IS_CROUCHING
                || condition.type() == DealConditionType.PARTY_IS_SPRINTING
                || condition.type() == DealConditionType.PARTY_IS_SWIMMING
                || condition.type() == DealConditionType.PARTY_IS_ON_GROUND) {
            if (!condition.assetId().isEmpty() || condition.amount() != 0 || condition.party() == Party.ANY_PLAYER
                    || !condition.slot().isEmpty() || !condition.dimensionId().isEmpty() || condition.radius() != 0.0)
                errors.add("Invalid player-state condition.");
        } else if (condition.type() == DealConditionType.PARTY_WITHIN_DISTANCE_OF_PARTY
                || condition.type() == DealConditionType.PARTY_OUTSIDE_DISTANCE_OF_PARTY) {
            if (!(condition.assetId().equals("DEALMAKER") || condition.assetId().equals("ACCEPTOR")) || condition.amount() < 1)
                errors.add("Invalid proximity condition.");
            if (condition.assetId().equals(condition.party().name())) errors.add("A party-proximity condition cannot target itself.");
        } else if (condition.type() == DealConditionType.PARTY_IN_DIMENSION
                || condition.type() == DealConditionType.PARTY_NOT_IN_DIMENSION) {
            if (!condition.dimensionId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || condition.party() == Party.ANY_PLAYER
                    || !condition.assetId().isEmpty() || condition.amount() != 0 || condition.radius() != 0.0)
                errors.add("Invalid dimension condition.");
        } else if (condition.type() == DealConditionType.PARTY_CHANGED_DIMENSION) {
            if ((!condition.dimensionId().isEmpty() && !condition.dimensionId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                    || condition.party() == Party.ANY_PLAYER || !condition.assetId().isEmpty()
                    || condition.amount() != 0 || condition.radius() != 0.0)
                errors.add("Invalid dimension-change condition.");
        } else if (condition.type() == DealConditionType.PARTY_WITHIN_COORDINATE_RADIUS
                || condition.type() == DealConditionType.PARTY_OUTSIDE_COORDINATE_RADIUS
                || condition.type() == DealConditionType.PARTY_ENTERED_COORDINATE_RADIUS
                || condition.type() == DealConditionType.PARTY_LEFT_COORDINATE_RADIUS) {
            if ((!condition.dimensionId().isEmpty() && !condition.dimensionId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) || !(condition.radius() > 0.0 && condition.radius() <= 30_000_000.0)
                    || condition.party() == Party.ANY_PLAYER || !condition.assetId().isEmpty() || condition.amount() != 0)
                errors.add("Invalid coordinate-radius condition.");
        } else {
            if (!isItemOrTag(condition.assetId()) || condition.amount() < 1 || !condition.slot().isEmpty())
                errors.add("Invalid item condition.");
        }
    }

    private static void validateTrigger(DealClause clause, List<String> errors) {
        if (clause.trigger() == null || clause.condition() == null || clause.condition().type() == null) return;
        if (clause.kind() == ClauseKind.RECURRING_ITEM_PAYMENT && clause.trigger() != DealTrigger.ON_RECURRING_DUE)
            errors.add("Recurring payment has the wrong trigger.");
        if (clause.kind() == ClauseKind.REDIRECT_DAMAGE_PERCENT && clause.trigger() != DealTrigger.ON_ACCEPTANCE)
            errors.add("Damage redirection must be established on acceptance.");
        if (clause.kind() == ClauseKind.FORFEIT_SOUL && clause.periodTicks() > 0L
                && clause.trigger() == DealTrigger.ON_ACCEPTANCE
                && clause.condition().type() != DealConditionType.ALWAYS)
            errors.add("A recurring default soul clause must be an unconditional companion established on acceptance.");
    }

    private static boolean validTiming(DealClause clause) {
        if (clause.trigger() == DealTrigger.ON_RECURRING_DUE) return clause.periodTicks() >= 20L;
        // Positive-period soul forfeitures are legacy typed companions for explicit payment defaults.
        if (clause.kind() == ClauseKind.FORFEIT_SOUL && clause.trigger() == DealTrigger.ON_ACCEPTANCE)
            return clause.periodTicks() >= 0L;
        return clause.periodTicks() == 0L;
    }

    /** An item condition accepts either an exact registry id or a server data-pack item tag (#namespace:path). */
    private static boolean isItemOrTag(String id) {
        return id != null && id.matches("#?[a-z0-9_.-]+:[a-z0-9_./-]+");
    }

    private static boolean isCoreClause(ClauseKind kind) {
        return switch (kind) {
            case TRANSFER_ATTRIBUTE_PERCENT, TRANSFER_ATTRIBUTE_AMOUNT, REVOKE_ATTRIBUTE_GRANTS,
                    TRANSFER_ITEM_AMOUNT, TRANSFER_ALL_MATCHING_ITEMS, TRANSFER_INVENTORY_SLOT,
                    REDIRECT_DAMAGE_PERCENT, RECURRING_ITEM_PAYMENT, FORFEIT_SOUL, KILL_PLAYER,
                    DEAL_DAMAGE_AMOUNT, SET_ON_FIRE_SECONDS, END_DEAL -> true;
            default -> false;
        };
    }

    private static boolean isCoreCondition(DealConditionType type) {
        return switch (type) {
            case ALWAYS, PARTY_HAS_ITEM, PARTY_LACKS_ITEM, PARTY_HAS_ITEM_IN_SLOT, PARTY_HOLDS_ANY_ITEM,
                    ITEM_ENTERED_INVENTORY, PARTY_STAT_AT_LEAST, PARTY_STAT_INCREASED, CHAT_MESSAGE_CONTAINS,
                    PARTY_DIES, PARTY_IS_CROUCHING, PARTY_IS_SPRINTING, PARTY_IS_SWIMMING, PARTY_IS_ON_GROUND,
                    PARTY_WITHIN_DISTANCE_OF_PARTY, PARTY_OUTSIDE_DISTANCE_OF_PARTY, PARTY_IN_DIMENSION,
                    PARTY_NOT_IN_DIMENSION, PARTY_CHANGED_DIMENSION, PARTY_WITHIN_COORDINATE_RADIUS,
                    PARTY_OUTSIDE_COORDINATE_RADIUS, PARTY_ENTERED_COORDINATE_RADIUS, PARTY_LEFT_COORDINATE_RADIUS,
                    PARTY_HARMED_PARTY, PARTY_WEATHER_IS, PARTY_TIME_OF_DAY_IS, PARTY_LIGHT_LEVEL_AT_LEAST,
                    PARTY_ACCEPTED_OTHER_DEAL -> true;
            default -> false;
        };
    }
}
