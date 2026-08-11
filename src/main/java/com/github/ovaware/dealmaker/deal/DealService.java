package com.github.ovaware.dealmaker.deal;

import com.github.ovaware.dealmaker.DealmakerMod;
import com.github.ovaware.dealmaker.ai.DealParserRouter;
import com.github.ovaware.dealmaker.config.DealmakerConfigs;
import com.github.ovaware.dealmaker.item.ClaimedSoulItem;
import com.github.ovaware.dealmaker.registry.DealmakerAttachments;
import com.github.ovaware.dealmaker.registry.DealmakerItems;
import com.github.ovaware.dealmaker.registry.DealmakerSkills;
import com.github.ovaware.dealmaker.storage.LedgerBook;
import com.github.ovaware.dealmaker.storage.SoulStorageContainer;
import com.github.ovaware.dealmaker.storage.SoulBoundInventoryContainer;
import io.github.manasmods.manascore.skill.api.ManasSkillInstance;
import io.github.manasmods.manascore.skill.api.SkillAPI;
import io.github.manasmods.manascore.skill.impl.SkillStorage;
import io.github.manasmods.tensura.ability.skill.Skill;
import io.github.manasmods.tensura.ability.magic.Magic;
import io.github.manasmods.tensura.ability.battlewill.Battlewill;
import io.github.manasmods.tensura.storage.TensuraStorages;
import io.github.manasmods.tensura.storage.ep.IExistence;
import io.github.manasmods.tensura.storage.unique.ITrulyUnique;
import io.github.manasmods.tensura.world.TensuraGameRules;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.server.network.Filterable;
import net.minecraft.stats.Stats;
import com.mojang.authlib.GameProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DealService {
    public static final String SHARED_SKILL_TAG = "DevilBargenSharedSkill";
    private static final UUID OPEN_ACCEPTOR = new UUID(0L, 0L);
    private static final Set<UUID> PARSING = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<UUID, Long> LAST_REMOTE_REQUEST = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> SUPPRESSED_RESOURCE_GAINS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, HeldSoulSlot> LAST_HELD_SOUL_SLOTS = new ConcurrentHashMap<>();
    private static final Set<UUID> PROCESSING_DEAL_ACCEPTANCE = ConcurrentHashMap.newKeySet();
    private static boolean APPLYING_REDIRECTED_DAMAGE;

    private DealService() {}

    public static void proposeBookAsync(ServerPlayer dealmaker, ItemStack book, WrittenBookContent content, String text) {
        if (!isDealmaker(dealmaker)) {
            dealmaker.sendSystemMessage(Component.literal("You are not marked as a Dealmaker.").withStyle(ChatFormatting.RED));
            return;
        }
        if (ViewerBook.isViewer(book)) {
            dealmaker.sendSystemMessage(Component.literal("That book is a Devil Bargen viewer, not a contract.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (DealBook.isManaged(book)) {
            dealmaker.sendSystemMessage(Component.literal("This book is already a Devil Bargen contract.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (!PARSING.add(dealmaker.getUUID())) {
            dealmaker.sendSystemMessage(Component.literal("Your previous contract is still being parsed.").withStyle(ChatFormatting.RED));
            return;
        }
        if (DealParserRouter.isRemote()) {
            long now = System.currentTimeMillis();
            long cooldown = Math.clamp(DealmakerConfigs.server().requestCooldownSeconds, 1, 60) * 1000L;
            Long previous = LAST_REMOTE_REQUEST.put(dealmaker.getUUID(), now);
            if (previous != null && now - previous < cooldown) {
                PARSING.remove(dealmaker.getUUID());
                dealmaker.sendSystemMessage(Component.literal("Wait before submitting another AI contract.").withStyle(ChatFormatting.RED));
                return;
            }
        }
        UUID pendingId = DealBook.markPending(book);
        dealmaker.sendSystemMessage(Component.literal("Parsing the signed contract...").withStyle(ChatFormatting.GRAY));
        UUID dealmakerId = dealmaker.getUUID();
        MinecraftServer server = dealmaker.server;
        String serverContext = aiContext(dealmaker);
        DealParserRouter.parse(text, serverContext).whenComplete((parsed, failure) -> server.execute(() -> {
            PARSING.remove(dealmakerId);
            ServerPlayer currentDealmaker = server.getPlayerList().getPlayer(dealmakerId);
            if (currentDealmaker == null) return;
            ItemStack currentBook = DealBook.findPending(currentDealmaker, pendingId).orElse(null);
            if (currentBook == null) {
                currentDealmaker.sendSystemMessage(Component.literal("The signed book was moved; no deal was created.").withStyle(ChatFormatting.RED));
                return;
            }
            if (failure != null || parsed == null) {
                DealBook.clearPending(currentBook);
                currentDealmaker.sendSystemMessage(Component.literal("Contract parsing failed or timed out.").withStyle(ChatFormatting.RED));
                return;
            }
            if (!parsed.accepted()) {
                DealBook.clearPending(currentBook);
                DealmakerMod.LOGGER.warn("Devil Bargen contract rejected after parsing: dealmaker={} ({}), errors={}",
                        currentDealmaker.getGameProfile().getName(), currentDealmaker.getUUID(), parsed.errors());
                currentDealmaker.sendSystemMessage(Component.literal(String.join(" ", parsed.errors())).withStyle(ChatFormatting.RED));
                return;
            }
            createBookProposal(currentDealmaker, currentBook, content, text, parsed);
        }));
    }

    private static void createBookProposal(ServerPlayer dealmaker, ItemStack book, WrittenBookContent content,
                                           String text, ParseResult parsed) {

        long now = dealmaker.serverLevel().getGameTime();
        Deal deal = new Deal(UUID.randomUUID(), dealmaker.getUUID(), OPEN_ACCEPTOR, text,
                parsed.clauses(), DealStatus.PENDING, now, firstDueAt(parsed.clauses(), now));
        dealmaker.getData(DealmakerAttachments.PLAYER_DATA).deals().add(deal);

        List<Filterable<Component>> pages = new ArrayList<>(content.pages());
        pages.add(Filterable.passThrough(Component.literal("Devil Bargen Contract\n\n")
                .withStyle(ChatFormatting.DARK_RED)
                .append(Component.literal("[Accept this contract]").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/devilbargen accept " + deal.id()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Accept this exact server-validated contract")))))));
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(content.title(), content.author(), content.generation(), List.copyOf(pages), false));
        DealBook.bind(book, deal);
        logProposedDeal(deal, dealmaker);

        dealmaker.sendSystemMessage(Component.literal("Contract appended to the signed book. Give the book to another player to accept it.")
                .withStyle(ChatFormatting.GREEN));
    }

    private static String aiContext(ServerPlayer dealmaker) {
        List<String> skills = SkillAPI.getSkillsFrom(dealmaker).getLearnedSkills().stream()
                .map(instance -> instance.getSkillId().toString()).sorted().limit(256).toList();
        List<String> stats = net.minecraft.core.registries.BuiltInRegistries.CUSTOM_STAT.keySet().stream()
                .map(ResourceLocation::toString).sorted().limit(256).toList();
        List<String> attributes = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.keySet().stream()
                .map(ResourceLocation::toString).sorted().limit(256).toList();
        return "\n<server_context trusted=\"true\">\n"
                + "The dealmaker currently owns these exact registered skill ids: " + String.join(", ", skills) + ".\n"
                + "Skill categories use TRANSFER_ALL_SKILLS_IN_CATEGORY with asset unique, ultimate, magic, or battlewill.\n"
                + "Known Minecraft custom-stat ids (use these exact ids for stat clauses): " + String.join(", ", stats) + ".\n"
                + "Known registered attribute ids: " + String.join(", ", attributes) + ".\n"
                + "Current transferable Tensura resources are EP, magicule, and aura. Item ids may be written as a display name; the server resolves an unambiguous item name at acceptance.\n"
                + "</server_context>";
    }

    public static String accept(ServerPlayer acceptor, UUID dealId) {
        LocatedDeal located = find(acceptor.server, dealId).orElse(null);
        if (located == null || located.deal().status() != DealStatus.PENDING) return "That pending deal does not exist.";
        Deal deal = located.deal();
        if (deal.dealmakerId().equals(acceptor.getUUID())) return "You cannot accept your own deal.";
        if (deal.hasAcceptor() && !deal.acceptorId().equals(acceptor.getUUID())) return "This contract has already been claimed.";
        ItemStack contractBook = DealBook.findDeal(acceptor, deal.id(), deal.dealmakerId()).orElse(null);
        if (contractBook == null) {
            return "You must possess this contract book to accept it.";
        }
        ServerPlayer dealmaker = acceptor.server.getPlayerList().getPlayer(deal.dealmakerId());
        if (dealmaker == null) return "The dealmaker must be online for asset validation.";

        List<String> errors = new ArrayList<>(DealPolicy.validateClauses(deal.clauses()));
        for (DealClause clause : deal.clauses()) {
            if (clause.kind() == ClauseKind.RECURRING_ITEM_PAYMENT || clause.kind() == ClauseKind.TRANSFER_ITEM_AMOUNT
                    || clause.kind() == ClauseKind.TRANSFER_ALL_MATCHING_ITEMS) {
                if (resolveItem(clause.assetId()) == null) {
                    errors.add("Unknown item " + clause.assetId() + ".");
                }
            }
            DealCondition condition = clause.condition();
            if (isItemCondition(condition.type()) && !isKnownItemPredicate(condition.assetId())) {
                errors.add("Unknown item or item tag " + condition.assetId() + ".");
            }
            if (isDimensionCondition(condition.type()) && !condition.dimensionId().isEmpty()
                    && !isKnownDimension(acceptor.server, condition.dimensionId())) {
                errors.add("Unknown dimension " + condition.dimensionId() + ".");
            }
            if ((condition.type() == DealConditionType.PARTY_STAT_AT_LEAST
                    || condition.type() == DealConditionType.PARTY_STAT_INCREASED)
                    && !isKnownCustomStat(condition.assetId())) {
                errors.add("Unknown Minecraft custom statistic " + condition.assetId() + ".");
            }
        }
        TransferPlan plan = TransferPlan.validate(deal, dealmaker, acceptor, errors, DealTrigger.ON_ACCEPTANCE);
        if (!errors.isEmpty()) return "Deal rejected: " + String.join(" ", errors);
        if (!plan.execute()) return "Deal rejected because an asset changed during execution.";

        contractBook.shrink(1);

        boolean ongoing = deal.clauses().stream().anyMatch(c -> c.kind() == ClauseKind.REDIRECT_DAMAGE_PERCENT || c.trigger() == DealTrigger.ON_RECURRING_DUE
                || c.kind() == ClauseKind.REDIRECT_RESOURCE_GAIN_PERCENT
                || c.trigger() == DealTrigger.ON_CONDITION_MET || c.trigger() == DealTrigger.ON_BREACH);
        Deal acceptedDeal = deal.withAcceptor(acceptor.getUUID()).withAttributeGrants(plan.attributeGrants())
                .withConditionValues(initialConditionValues(deal, dealmaker, acceptor))
                .withRuntimeValues(initialRuntimeValues(deal, dealmaker, acceptor))
                .withStatus(ongoing ? DealStatus.ACTIVE : DealStatus.COMPLETED);
        located.replace(acceptedDeal);
        logAcceptedDeal(acceptedDeal, dealmaker, acceptor);
        onDealAccepted(dealmaker, acceptor, acceptedDeal.id());
        dealmaker.sendSystemMessage(Component.literal(acceptor.getName().getString() + " accepted the contract.").withStyle(ChatFormatting.GOLD));
        return "Contract accepted.";
    }

    /**
     * Forces the held pending contract book onto a soul-owned player.
     * Ongoing clauses (recurring, conditions, redirects, breach) remain active after acceptance.
     */
    public static String forceDealSoul(ServerPlayer holder, ServerPlayer soulOwner) {
        if (holder == soulOwner) return "You cannot force a deal on yourself.";
        if (!hasCustody(holder, soulOwner.getUUID())) return "You do not possess that player's unique soul.";
        HeldPendingDeal pending = resolveHeldPendingDeal(holder);
        if (pending.error() != null) return pending.error();
        return executeForcedDeal(holder, soulOwner, pending.located(), pending.book());
    }

    /**
     * Forces the held pending contract onto every online player whose soul the holder uniquely possesses.
     * The first successful target consumes the book; later successful targets receive forked copies.
     */
    public static String forceDealSoulAll(ServerPlayer holder) {
        HeldPendingDeal pending = resolveHeldPendingDeal(holder);
        if (pending.error() != null) return pending.error();
        List<ServerPlayer> targets = ownedOnlineSouls(holder);
        if (targets.isEmpty()) return "You do not possess any online player's soul.";

        Deal template = pending.located().deal();
        UUID templateId = template.id();
        ItemStack book = pending.book();
        boolean useOriginal = true;
        int succeeded = 0;
        List<String> failures = new ArrayList<>();
        for (ServerPlayer soulOwner : targets) {
            LocatedDeal located;
            ItemStack consumeBook = null;
            if (useOriginal) {
                located = find(holder.server, templateId).orElse(null);
                if (located != null && located.deal().status() == DealStatus.PENDING) {
                    consumeBook = book;
                } else {
                    located = forkPendingDeal(holder, template);
                    if (located == null) {
                        failures.add(soulOwner.getName().getString() + ": could not fork the contract.");
                        continue;
                    }
                }
            } else {
                located = forkPendingDeal(holder, template);
                if (located == null) {
                    failures.add(soulOwner.getName().getString() + ": could not fork the contract.");
                    continue;
                }
            }
            String result = executeForcedDeal(holder, soulOwner, located, consumeBook);
            if (result.startsWith("Forced deal onto ")) {
                succeeded++;
                if (consumeBook != null) useOriginal = false;
            } else {
                failures.add(soulOwner.getName().getString() + ": " + result);
            }
        }
        if (succeeded == 0) {
            return "Forced deal failed for every owned soul. " + String.join(" ", failures);
        }
        String summary = "Forced deal onto " + succeeded + " of " + targets.size() + " owned soul(s).";
        if (!failures.isEmpty()) summary += " Failures: " + String.join(" ", failures);
        return summary;
    }

    private static HeldPendingDeal resolveHeldPendingDeal(ServerPlayer holder) {
        ItemStack book = DealBook.findHeldDeal(holder).orElse(null);
        if (book == null) return HeldPendingDeal.error("Hold a server-bound contract book you created.");
        UUID dealId = DealBook.dealId(book).orElse(null);
        if (dealId == null) return HeldPendingDeal.error("That book is not bound to a pending contract.");
        LocatedDeal located = find(holder.server, dealId).orElse(null);
        if (located == null || located.deal().status() != DealStatus.PENDING
                || !located.deal().dealmakerId().equals(holder.getUUID())) {
            return HeldPendingDeal.error("That pending deal is not yours.");
        }
        return new HeldPendingDeal(located, book, null);
    }

    private static List<ServerPlayer> ownedOnlineSouls(ServerPlayer holder) {
        List<ServerPlayer> owned = new ArrayList<>();
        for (ServerPlayer player : holder.server.getPlayerList().getPlayers()) {
            if (player != holder && hasCustody(holder, player.getUUID())) owned.add(player);
        }
        return owned;
    }

    private static LocatedDeal forkPendingDeal(ServerPlayer holder, Deal template) {
        long now = holder.serverLevel().getGameTime();
        Deal fork = new Deal(UUID.randomUUID(), holder.getUUID(), OPEN_ACCEPTOR, template.originalText(),
                template.clauses(), DealStatus.PENDING, now, firstDueAt(template.clauses(), now));
        holder.getData(DealmakerAttachments.PLAYER_DATA).deals().add(fork);
        return find(holder.server, fork.id()).orElse(null);
    }

    private static String executeForcedDeal(ServerPlayer holder, ServerPlayer soulOwner,
                                            LocatedDeal located, ItemStack contractBook) {
        if (located == null || located.deal().status() != DealStatus.PENDING
                || !located.deal().dealmakerId().equals(holder.getUUID())) {
            return "That pending deal is not yours.";
        }
        if (holder == soulOwner || !hasCustody(holder, soulOwner.getUUID())) {
            return "You do not possess that player's unique soul.";
        }
        Deal deal = located.deal();
        if (contractBook != null) {
            UUID bookDealId = DealBook.dealId(contractBook).orElse(null);
            if (bookDealId == null || !bookDealId.equals(deal.id())) {
                return "Hold the exact server-bound contract book for this deal.";
            }
        }

        List<String> errors = new ArrayList<>(DealPolicy.validateClauses(deal.clauses()));
        for (DealClause clause : deal.clauses()) {
            if (clause.kind() == ClauseKind.RECURRING_ITEM_PAYMENT || clause.kind() == ClauseKind.TRANSFER_ITEM_AMOUNT
                    || clause.kind() == ClauseKind.TRANSFER_ALL_MATCHING_ITEMS) {
                if (resolveItem(clause.assetId()) == null) {
                    errors.add("Unknown item " + clause.assetId() + ".");
                }
            }
            DealCondition condition = clause.condition();
            if (isItemCondition(condition.type()) && !isKnownItemPredicate(condition.assetId())) {
                errors.add("Unknown item or item tag " + condition.assetId() + ".");
            }
            if (isDimensionCondition(condition.type()) && !condition.dimensionId().isEmpty()
                    && !isKnownDimension(holder.server, condition.dimensionId())) {
                errors.add("Unknown dimension " + condition.dimensionId() + ".");
            }
            if ((condition.type() == DealConditionType.PARTY_STAT_AT_LEAST
                    || condition.type() == DealConditionType.PARTY_STAT_INCREASED)
                    && !isKnownCustomStat(condition.assetId())) {
                errors.add("Unknown Minecraft custom statistic " + condition.assetId() + ".");
            }
        }
        TransferPlan plan = TransferPlan.validate(deal, holder, soulOwner, errors, DealTrigger.ON_ACCEPTANCE);
        if (!errors.isEmpty()) return "Forced deal rejected: " + String.join(" ", errors);
        if (!plan.execute()) return "Forced deal changed during execution and was rolled back.";

        if (contractBook != null) contractBook.shrink(1);

        boolean ongoing = deal.clauses().stream().anyMatch(c -> c.kind() == ClauseKind.REDIRECT_DAMAGE_PERCENT
                || c.trigger() == DealTrigger.ON_RECURRING_DUE
                || c.kind() == ClauseKind.REDIRECT_RESOURCE_GAIN_PERCENT
                || c.trigger() == DealTrigger.ON_CONDITION_MET || c.trigger() == DealTrigger.ON_BREACH);
        Deal acceptedDeal = deal.withAcceptor(soulOwner.getUUID()).withAttributeGrants(plan.attributeGrants())
                .withConditionValues(initialConditionValues(deal, holder, soulOwner))
                .withRuntimeValues(initialRuntimeValues(deal, holder, soulOwner))
                .withStatus(ongoing ? DealStatus.ACTIVE : DealStatus.COMPLETED);
        located.replace(acceptedDeal);
        logAcceptedDeal(acceptedDeal, holder, soulOwner);
        onDealAccepted(holder, soulOwner, acceptedDeal.id());
        DealmakerMod.LOGGER.warn("Forced soul deal executed: deal={}, holder={}, soulOwner={}, status={}",
                deal.id(), holder.getUUID(), soulOwner.getUUID(), acceptedDeal.status());
        soulOwner.sendSystemMessage(Component.literal(holder.getName().getString()
                        + " forced a Devil Bargen contract onto you through soul authority.")
                .withStyle(ChatFormatting.DARK_RED));
        return "Forced deal onto " + soulOwner.getName().getString() + ".";
    }

    private record HeldPendingDeal(LocatedDeal located, ItemStack book, String error) {
        static HeldPendingDeal error(String message) {
            return new HeldPendingDeal(null, null, message);
        }
    }

    /**
     * Logs the committed, typed program rather than the AI response. This makes it possible to
     * compare a signed book with exactly what the server will enforce without exposing API keys.
     */
    private static void logAcceptedDeal(Deal deal, ServerPlayer dealmaker, ServerPlayer acceptor) {
        DealmakerMod.LOGGER.info("Devil Bargen deal accepted: id={}, status={}, dealmaker={} ({}), acceptor={} ({}), source={}",
                deal.id(), deal.status(), dealmaker.getGameProfile().getName(), dealmaker.getUUID(),
                acceptor.getGameProfile().getName(), acceptor.getUUID(), quoteForLog(deal.originalText()));
        if (deal.clauses().isEmpty()) {
            DealmakerMod.LOGGER.warn("Devil Bargen deal {} committed with no clauses.", deal.id());
            return;
        }
        for (int index = 0; index < deal.clauses().size(); index++) {
            DealClause clause = deal.clauses().get(index);
            DealmakerMod.LOGGER.info("Devil Bargen deal {} clause #{}: kind={}, from={}, to={}, asset={}, amount={}, periodTicks={}, trigger={}, condition={}",
                    deal.id(), index + 1, clause.kind(), clause.from(), clause.to(),
                    emptyAsNone(clause.assetId()), clause.amount(), clause.periodTicks(), clause.trigger(),
                    describeCondition(clause.condition()));
        }
        for (AttributeGrant grant : deal.attributeGrants()) {
            DealmakerMod.LOGGER.info("Devil Bargen deal {} recorded attribute grant: attribute={}, source={}, recipient={}, amount={}",
                    deal.id(), grant.attributeId(), grant.sourceId(), grant.recipientId(), grant.amount());
        }
        for (Map.Entry<String, Integer> statBaseline : deal.conditionValues().entrySet()) {
            DealmakerMod.LOGGER.info("Devil Bargen deal {} stat baseline: key={}, value={}",
                    deal.id(), statBaseline.getKey(), statBaseline.getValue());
        }
    }

    /** Logs before a book leaves the dealmaker, so parser output is visible even before acceptance. */
    private static void logProposedDeal(Deal deal, ServerPlayer dealmaker) {
        DealmakerMod.LOGGER.info("Devil Bargen deal proposed: id={}, dealmaker={} ({}), source={}",
                deal.id(), dealmaker.getGameProfile().getName(), dealmaker.getUUID(), quoteForLog(deal.originalText()));
        for (int index = 0; index < deal.clauses().size(); index++) {
            DealClause clause = deal.clauses().get(index);
            DealmakerMod.LOGGER.info("Devil Bargen proposed deal {} clause #{}: kind={}, from={}, to={}, asset={}, amount={}, periodTicks={}, trigger={}, condition={}",
                    deal.id(), index + 1, clause.kind(), clause.from(), clause.to(), emptyAsNone(clause.assetId()),
                    clause.amount(), clause.periodTicks(), clause.trigger(), describeCondition(clause.condition()));
        }
    }

    private static String describeCondition(DealCondition condition) {
        if (condition == null || (condition.type() == DealConditionType.ALWAYS
                && condition.additionalTerms().isEmpty() && !condition.negated())) return "ALWAYS";
        String primary = (condition.negated() ? "NOT " : "") + "type=" + condition.type() + ", party=" + condition.party()
                + ", asset=" + emptyAsNone(condition.assetId()) + ", amount=" + condition.amount()
                + ", slot=" + emptyAsNone(condition.slot()) + ", dimension=" + emptyAsNone(condition.dimensionId())
                + ", position=" + (condition.useX() ? condition.x() : "~") + ","
                + (condition.useY() ? condition.y() : "~") + "," + (condition.useZ() ? condition.z() : "~")
                + ", radius=" + condition.radius() + ", axes="
                + (condition.useX() ? "X" : "") + (condition.useY() ? "Y" : "") + (condition.useZ() ? "Z" : "");
        if (condition.additionalTerms().isEmpty()) return primary;
        return "logic=" + condition.logic() + ", terms=[" + primary + "; "
                + condition.additionalTerms().stream().map(term -> (term.negated() ? "NOT " : "") + term.type()
                + " party=" + term.party() + " asset=" + emptyAsNone(term.assetId()) + " amount=" + term.amount()
                + " slot=" + emptyAsNone(term.slot())).collect(java.util.stream.Collectors.joining("; ")) + "]";
    }

    private static String emptyAsNone(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    private static String quoteForLog(String text) {
        if (text == null) return "<none>";
        String flattened = text.replace('\n', ' ').replace('\r', ' ').trim();
        return '"' + (flattened.length() > 1_000 ? flattened.substring(0, 1_000) + "…" : flattened) + '"';
    }

    public static String sever(ServerPlayer dealmaker, UUID dealId) {
        LocatedDeal located = find(dealmaker.server, dealId).orElse(null);
        if (located == null || !located.deal().dealmakerId().equals(dealmaker.getUUID())) return "You do not own that deal.";
        if (located.deal().status() != DealStatus.ACTIVE) return "Only an active deal can be severed.";
        located.replace(located.deal().withStatus(DealStatus.SEVERED));
        refreshHeldViewerBooks(dealmaker);
        ServerPlayer acceptor = dealmaker.server.getPlayerList().getPlayer(located.deal().acceptorId());
        if (acceptor != null) refreshHeldViewerBooks(acceptor);
        return "Deal severed. Completed transfers are not reversed.";
    }

    public static void showDeals(ServerPlayer player) {
        openDealsUi(player);
    }

    /** Opens a written-book UI of deals involving the player, with party names and sever actions. */
    public static void openDealsUi(ServerPlayer player) {
        ItemStack book = obtainViewerBook(player, ViewerBook.Kind.DEALS);
        refreshViewerBook(player, book, ViewerBook.Kind.DEALS, true);
        openViewerBook(player, book);
        sendDealsChatSummary(player);
    }

    /** Hold a signed contract book to save it; otherwise open/update the ledger viewer book. */
    public static void ledger(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        WrittenBookContent content = held.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content != null && !ViewerBook.isViewer(held) && !DealBook.isManaged(held)) {
            saveBookToLedger(player, content);
            return;
        }
        if (content != null && DealBook.isManaged(held)) {
            // Allow saving the readable contract text of a bound deal book as a template.
            saveBookToLedger(player, content);
            return;
        }
        openLedgerUi(player);
    }

    public static String saveBookToLedger(ServerPlayer player, WrittenBookContent content) {
        var ledger = player.getData(DealmakerAttachments.PLAYER_DATA).ledger();
        if (ledger.size() >= LedgerBook.MAX_ENTRIES) {
            String message = "Ledger is full (" + LedgerBook.MAX_ENTRIES + " books). Delete one first.";
            player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
            return message;
        }
        List<String> pages = content.pages().stream()
                .map(Filterable::raw)
                .map(Component::getString)
                .toList();
        LedgerBook entry = LedgerBook.sanitized(content.title().raw(), content.author(), pages);
        ledger.add(entry);
        String message = "Saved \"" + entry.title() + "\" to the ledger (" + ledger.size() + "/" + LedgerBook.MAX_ENTRIES + ").";
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GOLD));
        refreshHeldViewerBooks(player);
        return message;
    }

    public static void openLedgerUi(ServerPlayer player) {
        ItemStack book = obtainViewerBook(player, ViewerBook.Kind.LEDGER);
        refreshViewerBook(player, book, ViewerBook.Kind.LEDGER, true);
        openViewerBook(player, book);
    }

    public static String createLedgerBook(ServerPlayer player, int index) {
        if (!isDealmaker(player)) return "Only a Devil Bargen user can create ledger copies.";
        List<LedgerBook> ledger = player.getData(DealmakerAttachments.PLAYER_DATA).ledger();
        if (index < 0 || index >= ledger.size()) return "Unknown ledger entry.";
        LedgerBook entry = ledger.get(index);
        ItemStack book = toWrittenBook(entry);
        if (!player.getInventory().add(book)) player.drop(book, false);
        String message = "Created a copy of \"" + entry.title() + "\".";
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GOLD));
        return message;
    }

    public static String deleteLedgerBook(ServerPlayer player, int index) {
        if (!isDealmaker(player)) return "Only a Devil Bargen user can edit the ledger.";
        List<LedgerBook> ledger = player.getData(DealmakerAttachments.PLAYER_DATA).ledger();
        if (index < 0 || index >= ledger.size()) return "Unknown ledger entry.";
        LedgerBook removed = ledger.remove(index);
        String message = "Removed \"" + removed.title() + "\" from the ledger.";
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GRAY));
        refreshHeldViewerBooks(player);
        return message;
    }

    /** Keep ledger/deals viewer books synced to the current holder. */
    public static void refreshViewerBooks(ServerPlayer player) {
        if (player.tickCount % 10 != 0) return;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            ViewerBook.kind(stack).ifPresent(kind -> refreshViewerBook(player, stack, kind, false));
        }
    }

    private static void refreshHeldViewerBooks(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            ViewerBook.kind(stack).ifPresent(kind -> refreshViewerBook(player, stack, kind, true));
        }
    }

    private static void sendDealsChatSummary(ServerPlayer player) {
        List<Deal> active = collectInvolvedDeals(player).stream()
                .filter(deal -> deal.status() == DealStatus.ACTIVE).toList();
        player.sendSystemMessage(Component.literal("Active Devil Bargen deals:").withStyle(ChatFormatting.DARK_RED));
        if (active.isEmpty()) {
            player.sendSystemMessage(Component.literal("No active deals.").withStyle(ChatFormatting.GRAY));
            return;
        }
        for (Deal deal : active) {
            Component line = Component.literal("")
                    .append(Component.literal(partyName(player.server, deal.dealmakerId())).withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(" ↔ ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(partyName(player.server, deal.acceptorId())).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" [" + deal.id().toString().substring(0, 8) + "] ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(shorten(deal.originalText())).withStyle(ChatFormatting.GRAY));
            if (deal.dealmakerId().equals(player.getUUID())) {
                line = line.copy().append(Component.literal(" [Sever]").withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/devilbargen sever " + deal.id()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Sever this active deal")))));
            }
            player.sendSystemMessage(line);
        }
    }

    public static void debugDeal(ServerPlayer viewer, UUID dealId) {
        LocatedDeal located = find(viewer.server, dealId).orElse(null);
        if (located == null || (!located.deal().dealmakerId().equals(viewer.getUUID())
                && !located.deal().acceptorId().equals(viewer.getUUID()))) {
            viewer.sendSystemMessage(Component.literal("You are not a party to that deal.").withStyle(ChatFormatting.RED));
            return;
        }
        Deal deal = located.deal();
        viewer.sendSystemMessage(Component.literal("Deal " + deal.id() + " [" + deal.status() + "] v" + deal.dataVersion())
                .withStyle(ChatFormatting.GOLD));
        for (int index = 0; index < deal.clauses().size(); index++) {
            viewer.sendSystemMessage(Component.literal("#" + (index + 1) + " " + describe(List.of(deal.clauses().get(index))))
                    .withStyle(ChatFormatting.GRAY));
        }
        deal.runtimeValues().forEach((key, value) -> viewer.sendSystemMessage(Component.literal(key + " = " + value)
                .withStyle(ChatFormatting.DARK_GRAY)));
    }

    public static void openSoulStorage(ServerPlayer player) {
        SoulStorageContainer souls = new SoulStorageContainer(player);
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> ChestMenu.threeRows(id, inventory, souls),
                Component.literal("Claimed Souls")));
    }

    public static boolean openSoulInventory(ServerPlayer holder, ServerPlayer soulOwner) {
        if (!hasCustody(holder, soulOwner.getUUID())) return false;
        SoulBoundInventoryContainer guarded = new SoulBoundInventoryContainer(holder, soulOwner);
        holder.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> ChestMenu.threeRows(id, inventory, guarded),
                Component.literal(soulOwner.getName().getString() + "'s Soulbound Inventory")));
        return true;
    }

    public static boolean hasCustody(ServerPlayer holder, UUID soulOwner) {
        boolean local = holder.getData(DealmakerAttachments.PLAYER_DATA).storedSouls().contains(soulOwner);
        for (int i = 0; i < holder.getInventory().getContainerSize(); i++) {
            if (ClaimedSoulItem.owner(holder.getInventory().getItem(i)).filter(soulOwner::equals).isPresent()) local = true;
        }
        if (!local) return false;
        int copies = custodyCopies(holder.server, soulOwner);
        if (copies != 1) {
            DealmakerMod.LOGGER.error("Soul custody rejected because {} live copies exist for owner {}", copies, soulOwner);
            return false;
        }
        return true;
    }

    /** Whether a player still possesses their own soul rather than having it held by another player. */
    public static boolean hasSoul(ServerPlayer player) {
        return !player.getData(DealmakerAttachments.PLAYER_DATA).soulClaimed();
    }

    private static int custodyCopies(MinecraftServer server, UUID soulOwner) {
        int copies = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getData(DealmakerAttachments.PLAYER_DATA).storedSouls().contains(soulOwner)) copies++;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                if (ClaimedSoulItem.owner(player.getInventory().getItem(slot)).filter(soulOwner::equals).isPresent()) copies++;
            }
        }
        return copies;
    }

    public static void removeSoul(ServerPlayer holder, UUID soulOwner) {
        holder.getData(DealmakerAttachments.PLAYER_DATA).storedSouls().removeIf(soulOwner::equals);
        for (int i = 0; i < holder.getInventory().getContainerSize(); i++) {
            ItemStack stack = holder.getInventory().getItem(i);
            if (ClaimedSoulItem.owner(stack).filter(soulOwner::equals).isPresent()) holder.getInventory().setItem(i, ItemStack.EMPTY);
        }
    }

    /** Revokes every loaded representation of a soul, returning it to its owner. */
    public static void forceReturnSoul(MinecraftServer server, UUID soulOwner) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) removeSoul(player, soulOwner);
        ServerPlayer owner = server.getPlayerList().getPlayer(soulOwner);
        if (owner != null) {
            owner.getData(DealmakerAttachments.PLAYER_DATA).setSoulClaimed(false);
            owner.sendSystemMessage(Component.literal("Your soul has been returned to you.").withStyle(ChatFormatting.GREEN));
        }
        DealmakerMod.LOGGER.info("Soul returned to its owner: {}", soulOwner);
    }

    /** Removes current custody and grants the soul directly to the selected holder. */
    public static boolean forceStealSoul(ServerPlayer holder, ServerPlayer soulOwner) {
        if (holder == soulOwner) return false;
        for (ServerPlayer player : holder.server.getPlayerList().getPlayers()) removeSoul(player, soulOwner.getUUID());
        if (!grantSoul(holder, soulOwner)) {
            holder.sendSystemMessage(Component.literal("Could not store the stolen soul: your inventory and Devil Bargen storage are full.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        soulOwner.sendSystemMessage(Component.literal("Your soul has been forcefully stolen by " + holder.getName().getString() + ".")
                .withStyle(ChatFormatting.DARK_RED));
        DealmakerMod.LOGGER.warn("Administrator forcefully assigned soul: holder={} ({}), owner={} ({})",
                holder.getGameProfile().getName(), holder.getUUID(), soulOwner.getGameProfile().getName(), soulOwner.getUUID());
        return true;
    }

    /** Removes souls placed in any menu-backed storage other than Devil Bargen storage. */
    public static void returnSoulsInIllegalContainers(ServerPlayer player) {
        returnSoulsInIllegalContainer(player, player.containerMenu);
        rememberHeldSoulSlots(player);
    }

    /** Also used on close, so a same-tick click-and-close cannot leave a soul in storage. */
    public static void returnSoulsInIllegalContainer(ServerPlayer player, AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            removeSoulsFromNestedBundles(player, slot.getItem());
            if (slot.container == player.getInventory() || slot.container instanceof SoulStorageContainer) continue;
            ClaimedSoulItem.owner(slot.getItem()).ifPresent(owner -> {
                ItemStack soul = slot.getItem().copy();
                slot.set(ItemStack.EMPTY);
                slot.setChanged();
                restoreSoulToInventory(player, owner, soul);
                player.sendSystemMessage(Component.literal("A soul cannot be placed in that storage and was returned to your inventory.")
                        .withStyle(ChatFormatting.RED));
            });
        }
    }

    /** Cleans up souls inserted before the hard item-level container restriction was installed. */
    private static void removeSoulsFromNestedBundles(ServerPlayer player, ItemStack container) {
        BundleContents contents = container.get(DataComponents.BUNDLE_CONTENTS);
        if (contents == null) return;
        List<ItemStack> retained = new ArrayList<>();
        List<ItemStack> releasedSouls = new ArrayList<>();
        boolean changed = false;
        for (ItemStack child : contents.items()) {
            ItemStack copy = child.copy();
            if (ClaimedSoulItem.owner(copy).isPresent()) {
                releasedSouls.add(copy);
                changed = true;
                continue;
            }
            removeSoulsFromNestedBundles(player, copy);
            retained.add(copy);
            changed |= !ItemStack.matches(child, copy);
        }
        if (!changed) return;
        container.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(retained));
        for (ItemStack soul : releasedSouls) {
            ClaimedSoulItem.owner(soul).ifPresent(owner -> restoreSoulToInventory(player, owner, soul));
        }
        player.sendSystemMessage(Component.literal("A soul cannot be kept inside a bundle and was returned to your inventory.")
                .withStyle(ChatFormatting.RED));
    }

    private static void rememberHeldSoulSlots(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            int inventorySlot = slot;
            ItemStack stack = player.getInventory().getItem(slot);
            ClaimedSoulItem.owner(stack).ifPresent(owner -> LAST_HELD_SOUL_SLOTS.put(owner,
                    new HeldSoulSlot(player.getUUID(), inventorySlot)));
        }
    }

    private static void restoreSoulToInventory(ServerPlayer player, UUID soulOwner, ItemStack soul) {
        HeldSoulSlot previous = LAST_HELD_SOUL_SLOTS.get(soulOwner);
        removeSoul(player, soulOwner);
        if (previous != null && previous.holderId().equals(player.getUUID())
                && player.getInventory().getItem(previous.slot()).isEmpty()) {
            player.getInventory().setItem(previous.slot(), soul);
        } else if (!player.getInventory().add(soul)) {
            player.getData(DealmakerAttachments.PLAYER_DATA).storedSouls().add(soulOwner);
        }
        LAST_HELD_SOUL_SLOTS.put(soulOwner, new HeldSoulSlot(player.getUUID(),
                previous != null && previous.holderId().equals(player.getUUID()) ? previous.slot() : -1));
    }

    public static void tick(ServerPlayer dealmaker) {
        if (dealmaker.tickCount % 20 != 0) return;
        long now = dealmaker.serverLevel().getGameTime();
        List<Deal> deals = dealmaker.getData(DealmakerAttachments.PLAYER_DATA).deals();
        for (int i = 0; i < deals.size(); i++) {
            Deal deal = deals.get(i);
            if (deal.status() != DealStatus.ACTIVE) continue;
            ServerPlayer acceptor = dealmaker.server.getPlayerList().getPlayer(deal.acceptorId());
            if (acceptor == null) continue;
            // Breach clauses use the same event/state evaluator as ordinary automation.  In
            // particular, PARTY_STAT_INCREASED is deliberately false in the timeless predicate
            // evaluator and must be compared with its acceptance baseline here.
            if (deal.clauses().stream().anyMatch(clause -> clause.trigger() == DealTrigger.ON_BREACH
                    && breachConditionMatches(clause.condition(), deal, dealmaker, acceptor))) {
                if (applyBreach(deal, dealmaker, acceptor, null)) {
                    deals.set(i, deal.withStatus(DealStatus.BREACHED));
                    dealmaker.sendSystemMessage(Component.literal("A Devil Bargen contract was breached.").withStyle(ChatFormatting.DARK_RED));
                    acceptor.sendSystemMessage(Component.literal("You breached a Devil Bargen contract.").withStyle(ChatFormatting.DARK_RED));
                }
                continue;
            }
            Deal conditionUpdated = runResourceGainRedirects(deal, dealmaker, acceptor);
            conditionUpdated = runAutomationConditions(conditionUpdated, dealmaker, acceptor);
            if (conditionUpdated != deal) {
                deals.set(i, conditionUpdated);
            }
            Deal recurringUpdated = runRecurringActions(conditionUpdated, dealmaker, acceptor, now);
            if (recurringUpdated != conditionUpdated) deals.set(i, recurringUpdated);
        }
    }

    /** Called after damage is confirmed, so invulnerability/nullification does not create a false breach. */
    public static void onPlayerHarmed(ServerPlayer victim, ServerPlayer attacker) {
        if (victim == attacker) return;
        for (ServerPlayer dealmaker : victim.server.getPlayerList().getPlayers()) {
            List<Deal> deals = dealmaker.getData(DealmakerAttachments.PLAYER_DATA).deals();
            for (int i = 0; i < deals.size(); i++) {
                Deal deal = deals.get(i);
                if (deal.status() != DealStatus.ACTIVE || !deal.dealmakerId().equals(dealmaker.getUUID())) continue;
                ServerPlayer acceptor = victim.server.getPlayerList().getPlayer(deal.acceptorId());
                if (acceptor == null) continue;
                for (DealClause clause : deal.clauses()) {
                    DealCondition condition = clause.condition();
                    if ((clause.trigger() != DealTrigger.ON_BREACH && clause.trigger() != DealTrigger.ON_CONDITION_MET)
                            || !harmMatches(condition, dealmaker, acceptor, attacker, victim)) continue;
                    if (clause.trigger() == DealTrigger.ON_BREACH) {
                        if (applyBreach(deal, dealmaker, acceptor, condition)) {
                            deals.set(i, deal.withStatus(DealStatus.BREACHED));
                            dealmaker.sendSystemMessage(Component.literal("A Devil Bargen contract was breached by harm.").withStyle(ChatFormatting.DARK_RED));
                            attacker.sendSystemMessage(Component.literal("You breached a Devil Bargen contract by harming "
                                    + victim.getName().getString() + ".").withStyle(ChatFormatting.DARK_RED));
                        }
                    } else {
                        List<String> errors = new ArrayList<>();
                        TransferPlan plan = TransferPlan.validate(deal, dealmaker, acceptor, errors,
                                DealTrigger.ON_CONDITION_MET, condition);
                        if (errors.isEmpty() && plan.execute()) {
                            if (plan.endsDeal()) {
                                deals.set(i, deal.withStatus(DealStatus.COMPLETED));
                                notifyDealEnded(dealmaker, acceptor, deal);
                                DealmakerMod.LOGGER.info("Devil Bargen harm automation ended deal: deal={}, attacker={} ({}), victim={} ({}), condition={}",
                                        deal.id(), attacker.getGameProfile().getName(), attacker.getUUID(),
                                        victim.getGameProfile().getName(), victim.getUUID(), describeCondition(condition));
                            } else {
                                DealmakerMod.LOGGER.info("Devil Bargen harm automation executed without ending deal: deal={}, attacker={} ({}), victim={} ({}), condition={}",
                                        deal.id(), attacker.getGameProfile().getName(), attacker.getUUID(),
                                        victim.getGameProfile().getName(), victim.getUUID(), describeCondition(condition));
                            }
                        } else {
                            DealmakerMod.LOGGER.warn("Devil Bargen harm automation could not execute: deal={}, errors={}",
                                    deal.id(), errors);
                        }
                    }
                    break;
                }
            }
        }
    }

    /** Executes clauses bound to a party's death, including reciprocal-death contracts. */
    public static void onPlayerDied(ServerPlayer dead) {
        for (ServerPlayer dealmaker : dead.server.getPlayerList().getPlayers()) {
            List<Deal> deals = dealmaker.getData(DealmakerAttachments.PLAYER_DATA).deals();
            for (int index = 0; index < deals.size(); index++) {
                Deal deal = deals.get(index);
                if (deal.status() != DealStatus.ACTIVE) continue;
                ServerPlayer acceptor = dead.server.getPlayerList().getPlayer(deal.acceptorId());
                if (acceptor == null) continue;
                for (DealClause clause : deal.clauses()) {
                    DealCondition condition = clause.condition();
                    boolean matches = deathGroupMatches(condition, dead, deal, dealmaker, acceptor);
                    if (!matches || (clause.trigger() != DealTrigger.ON_CONDITION_MET && clause.trigger() != DealTrigger.ON_BREACH)) continue;
                    if (clause.trigger() == DealTrigger.ON_BREACH) {
                        if (applyBreach(deal, dealmaker, acceptor, condition)) deals.set(index, deal.withStatus(DealStatus.BREACHED));
                    } else {
                        List<String> errors = new ArrayList<>();
                        TransferPlan plan = TransferPlan.validate(deal, dealmaker, acceptor, errors,
                                DealTrigger.ON_CONDITION_MET, condition);
                        if (errors.isEmpty() && plan.execute()) {
                            if (plan.endsDeal()) {
                                deals.set(index, deal.withStatus(DealStatus.COMPLETED));
                                notifyDealEnded(dealmaker, acceptor, deal);
                                DealmakerMod.LOGGER.info("Devil Bargen death automation ended deal: deal={}, dead={} ({}), condition={}",
                                        deal.id(), dead.getGameProfile().getName(), dead.getUUID(), describeCondition(condition));
                            } else {
                                DealmakerMod.LOGGER.info("Devil Bargen death automation executed without ending deal: deal={}, dead={} ({}), condition={}",
                                        deal.id(), dead.getGameProfile().getName(), dead.getUUID(), describeCondition(condition));
                            }
                        }
                    }
                    break;
                }
            }
        }
    }

    /** Returns the remaining damage for the original victim after active redirect clauses run. */
    public static float redirectIncomingDamage(ServerPlayer victim, net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (APPLYING_REDIRECTED_DAMAGE || amount <= 0.0F) return amount;
        float remaining = amount;
        for (ServerPlayer dealmaker : victim.server.getPlayerList().getPlayers()) {
            for (Deal deal : dealmaker.getData(DealmakerAttachments.PLAYER_DATA).deals()) {
                if (deal.status() != DealStatus.ACTIVE) continue;
                ServerPlayer acceptor = victim.server.getPlayerList().getPlayer(deal.acceptorId());
                if (acceptor == null) continue;
                for (DealClause clause : deal.clauses()) {
                    if (clause.kind() != ClauseKind.REDIRECT_DAMAGE_PERCENT || !conditionMatches(clause.condition(), deal, dealmaker, acceptor)) continue;
                    ServerPlayer protectedPlayer = party(deal, dealmaker, acceptor, clause.from());
                    ServerPlayer recipient = party(deal, dealmaker, acceptor, clause.to());
                    if (protectedPlayer != victim || !recipient.isAlive()) continue;
                    float redirected = remaining * (float) (clause.amount() / 100.0D);
                    if (redirected <= 0.0F) continue;
                    APPLYING_REDIRECTED_DAMAGE = true;
                    try {
                        recipient.hurt(source, redirected);
                    } finally {
                        APPLYING_REDIRECTED_DAMAGE = false;
                    }
                    remaining = Math.max(0.0F, remaining - redirected);
                    DealmakerMod.LOGGER.info("Devil Bargen redirected {} damage from {} to {} for deal {} ({}%); remaining={}",
                            redirected, victim.getGameProfile().getName(), recipient.getGameProfile().getName(), deal.id(), clause.amount(), remaining);
                    if (remaining <= 0.0F) return 0.0F;
                }
            }
        }
        return remaining;
    }

    /** Runs chat conditions after a player actually submits a chat message; no commands are interpreted. */
    public static void onPlayerChat(ServerPlayer sender, String message) {
        String normalized = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.isBlank()) return;
        for (ServerPlayer dealmaker : sender.server.getPlayerList().getPlayers()) {
            List<Deal> deals = dealmaker.getData(DealmakerAttachments.PLAYER_DATA).deals();
            for (int index = 0; index < deals.size(); index++) {
                Deal deal = deals.get(index);
                if (deal.status() != DealStatus.ACTIVE) continue;
                ServerPlayer acceptor = sender.server.getPlayerList().getPlayer(deal.acceptorId());
                if (acceptor == null) continue;
                for (DealClause clause : deal.clauses()) {
                    DealCondition condition = clause.condition();
                    if (clause.trigger() != DealTrigger.ON_CONDITION_MET
                            || !chatGroupMatches(condition, sender, normalized, deal, dealmaker, acceptor)) continue;
                    List<String> errors = new ArrayList<>();
                    TransferPlan plan = TransferPlan.validate(deal, dealmaker, acceptor, errors,
                            DealTrigger.ON_CONDITION_MET, condition);
                    if (!errors.isEmpty() || !plan.execute()) continue;
                    if (plan.endsDeal()) {
                        deals.set(index, deal.withStatus(DealStatus.COMPLETED));
                        notifyDealEnded(dealmaker, acceptor, deal);
                    }
                    DealmakerMod.LOGGER.info("Devil Bargen chat automation executed: deal={}, sender={} ({}), phrase={}, ended={}",
                            deal.id(), sender.getGameProfile().getName(), sender.getUUID(), quoteForLog(condition.assetId()),
                            plan.endsDeal());
                    break;
                }
            }
        }
    }

    /** Called from ManasCore's server activation callback for actual skill use. */
    public static void onSkillUsed(ServerPlayer user, ManasSkillInstance used) {
        for (ServerPlayer dealmaker : user.server.getPlayerList().getPlayers()) {
            List<Deal> deals = dealmaker.getData(DealmakerAttachments.PLAYER_DATA).deals();
            for (int index = 0; index < deals.size(); index++) {
                Deal deal = deals.get(index);
                if (deal.status() != DealStatus.ACTIVE) continue;
                ServerPlayer acceptor = user.server.getPlayerList().getPlayer(deal.acceptorId());
                if (acceptor == null) continue;
                for (DealClause clause : deal.clauses()) {
                    DealCondition condition = clause.condition();
                    if (clause.trigger() != DealTrigger.ON_CONDITION_MET
                            || !skillUseMatches(condition, user, deal, dealmaker, acceptor, used)) continue;
                    List<String> errors = new ArrayList<>();
                    TransferPlan plan = TransferPlan.validate(deal, dealmaker, acceptor, errors,
                            DealTrigger.ON_CONDITION_MET, condition);
                    if (errors.isEmpty() && plan.execute()) {
                        if (plan.endsDeal()) {
                            deals.set(index, deal.withStatus(DealStatus.COMPLETED));
                            notifyDealEnded(dealmaker, acceptor, deal);
                        }
                        DealmakerMod.LOGGER.info("Devil Bargen skill-use automation executed: deal={}, user={} ({}), skill={}, condition={}, ended={}",
                                deal.id(), user.getGameProfile().getName(), user.getUUID(), used.getSkillId(),
                                describeCondition(condition), plan.endsDeal());
                    }
                    break;
                }
            }
        }
    }

    /**
     * Evaluates active contracts after a different contract has been successfully accepted. This is
     * intentionally post-commit: rejected/pending books never count, and the newly accepted deal
     * cannot trigger itself.
     */
    private static void onDealAccepted(ServerPlayer newDealmaker, ServerPlayer newAcceptor, UUID acceptedDealId) {
        if (!PROCESSING_DEAL_ACCEPTANCE.add(acceptedDealId)) return;
        try {
            for (ServerPlayer dealmaker : newAcceptor.server.getPlayerList().getPlayers()) {
                List<Deal> deals = dealmaker.getData(DealmakerAttachments.PLAYER_DATA).deals();
                for (int index = 0; index < deals.size(); index++) {
                    Deal deal = deals.get(index);
                    if (deal.id().equals(acceptedDealId) || deal.status() != DealStatus.ACTIVE) continue;
                    ServerPlayer acceptor = newAcceptor.server.getPlayerList().getPlayer(deal.acceptorId());
                    if (acceptor == null) continue;
                    for (DealClause clause : deal.clauses()) {
                        DealCondition condition = clause.condition();
                        if ((clause.trigger() != DealTrigger.ON_CONDITION_MET && clause.trigger() != DealTrigger.ON_BREACH)
                                || !dealAcceptanceMatches(condition, newDealmaker, newAcceptor, deal, dealmaker, acceptor)) continue;
                        if (clause.trigger() == DealTrigger.ON_BREACH) {
                            if (applyBreach(deal, dealmaker, acceptor, condition)) {
                                deals.set(index, deal.withStatus(DealStatus.BREACHED));
                            }
                        } else {
                            List<String> errors = new ArrayList<>();
                            TransferPlan plan = TransferPlan.validate(deal, dealmaker, acceptor, errors,
                                    DealTrigger.ON_CONDITION_MET, condition);
                            if (errors.isEmpty() && plan.execute() && plan.endsDeal()) {
                                deals.set(index, deal.withStatus(DealStatus.COMPLETED));
                                notifyDealEnded(dealmaker, acceptor, deal);
                            }
                        }
                        break;
                    }
                }
            }
        } finally {
            PROCESSING_DEAL_ACCEPTANCE.remove(acceptedDealId);
        }
    }

    private static boolean skillUseMatches(DealCondition condition, ServerPlayer user, Deal deal,
                                           ServerPlayer dealmaker, ServerPlayer acceptor, ManasSkillInstance used) {
        boolean hasSkillTerm = condition.terms().stream().anyMatch(term -> isSkillCondition(term.type()));
        return hasSkillTerm && groupMatches(condition, leaf -> {
            if (!isSkillCondition(leaf.type())) return conditionMatchesLeaf(leaf, deal, dealmaker, acceptor);
            if (leaf.party() == Party.ANY_PLAYER || party(deal, dealmaker, acceptor, leaf.party()) != user) return false;
            return switch (leaf.type()) {
                case PARTY_USES_SKILL_CATEGORY -> switch (leaf.assetId()) {
                    case "any" -> true;
                    case "magic" -> used.getSkill() instanceof Magic;
                    case "battlewill" -> used.getSkill() instanceof Battlewill;
                    default -> false;
                };
                case PARTY_USES_ANY_SKILL -> true;
                case PARTY_USES_SKILL -> used.getSkillId().toString().equals(leaf.assetId());
                case PARTY_USES_MAGIC -> used.getSkill() instanceof Magic;
                case PARTY_USES_BATTLEWILL -> used.getSkill() instanceof Battlewill;
                default -> false;
            };
        });
    }

    private static boolean isSkillCondition(DealConditionType type) {
        return type == DealConditionType.PARTY_USES_SKILL_CATEGORY
                || type == DealConditionType.PARTY_USES_ANY_SKILL || type == DealConditionType.PARTY_USES_SKILL
                || type == DealConditionType.PARTY_USES_MAGIC || type == DealConditionType.PARTY_USES_BATTLEWILL;
    }

    private static boolean chatGroupMatches(DealCondition condition, ServerPlayer sender, String message, Deal deal,
                                            ServerPlayer dealmaker, ServerPlayer acceptor) {
        boolean hasChat = condition.terms().stream().anyMatch(term -> term.type() == DealConditionType.CHAT_MESSAGE_CONTAINS);
        return hasChat && groupMatches(condition, leaf -> leaf.type() == DealConditionType.CHAT_MESSAGE_CONTAINS
                ? chatSpeakerMatches(leaf.party(), sender, dealmaker, acceptor)
                    && message.contains(leaf.assetId().toLowerCase(java.util.Locale.ROOT))
                : conditionMatchesLeaf(leaf, deal, dealmaker, acceptor));
    }

    private static boolean deathGroupMatches(DealCondition condition, ServerPlayer dead, Deal deal,
                                             ServerPlayer dealmaker, ServerPlayer acceptor) {
        boolean hasDeath = condition.terms().stream().anyMatch(term -> term.type() == DealConditionType.PARTY_DIES);
        return hasDeath && groupMatches(condition, leaf -> leaf.type() == DealConditionType.PARTY_DIES
                ? party(deal, dealmaker, acceptor, leaf.party()) == dead
                : conditionMatchesLeaf(leaf, deal, dealmaker, acceptor));
    }

    private static boolean chatSpeakerMatches(Party selected, ServerPlayer sender, ServerPlayer dealmaker, ServerPlayer acceptor) {
        return selected == Party.ANY_PLAYER || (selected == Party.DEALMAKER && sender == dealmaker)
                || (selected == Party.ACCEPTOR && sender == acceptor);
    }

    private static boolean harmMatches(DealCondition condition, ServerPlayer dealmaker, ServerPlayer acceptor,
                                       ServerPlayer attacker, ServerPlayer victim) {
        boolean hasHarm = condition.terms().stream().anyMatch(term -> term.type() == DealConditionType.PARTY_HARMED_PARTY);
        return hasHarm && groupMatches(condition, leaf -> {
            if (leaf.type() != DealConditionType.PARTY_HARMED_PARTY)
                return conditionMatchesLeaf(leaf, null, dealmaker, acceptor);
            ServerPlayer expectedAttacker = leaf.party() == Party.DEALMAKER ? dealmaker : acceptor;
            ServerPlayer expectedVictim = "DEALMAKER".equals(leaf.assetId()) ? dealmaker : acceptor;
            return expectedAttacker == attacker && expectedVictim == victim && leaf.amount() >= 1;
        });
    }

    private static boolean dealAcceptanceMatches(DealCondition condition, ServerPlayer newDealmaker,
                                                 ServerPlayer newAcceptor, Deal deal, ServerPlayer dealmaker,
                                                 ServerPlayer acceptor) {
        boolean hasAcceptance = condition.terms().stream()
                .anyMatch(term -> term.type() == DealConditionType.PARTY_ACCEPTED_OTHER_DEAL);
        return hasAcceptance && groupMatches(condition, leaf -> {
            if (leaf.type() != DealConditionType.PARTY_ACCEPTED_OTHER_DEAL) {
                return conditionMatchesLeaf(leaf, deal, dealmaker, acceptor);
            }
            if (party(deal, dealmaker, acceptor, leaf.party()) != newAcceptor) return false;
            return switch (leaf.assetId()) {
                case "CURRENT_DEALMAKER" -> newDealmaker.getUUID().equals(deal.dealmakerId());
                case "OTHER_THAN_CURRENT_DEALMAKER" -> !newDealmaker.getUUID().equals(deal.dealmakerId());
                case "CURRENT_ACCEPTOR" -> newDealmaker.getUUID().equals(deal.acceptorId());
                default -> newDealmaker.getUUID().toString().equals(leaf.assetId());
            };
        });
    }

    private static boolean applyBreach(Deal deal, ServerPlayer dealmaker, ServerPlayer acceptor, DealCondition forcedCondition) {
        List<String> errors = new ArrayList<>();
        TransferPlan plan = TransferPlan.validate(deal, dealmaker, acceptor, errors, DealTrigger.ON_BREACH, forcedCondition);
        List<RevocationMutation> revocations = new ArrayList<>();
        for (DealClause clause : deal.clauses()) {
            if (clause.kind() != ClauseKind.REVOKE_ATTRIBUTE_GRANTS || clause.trigger() != DealTrigger.ON_BREACH
                    || !(forcedCondition != null ? clause.condition().equals(forcedCondition)
                    : conditionMatches(clause.condition(), deal, dealmaker, acceptor))) continue;
            ServerPlayer loser = party(deal, dealmaker, acceptor, clause.from());
            ServerPlayer restored = party(deal, dealmaker, acceptor, clause.to());
            ResourceLocation id = ResourceLocation.tryParse(clause.assetId());
            Optional<Holder.Reference<Attribute>> attribute = id == null ? Optional.empty()
                    : net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getHolder(id);
            if (attribute.isEmpty()) { errors.add("Unknown revocation attribute " + clause.assetId() + "."); continue; }
            AttributeInstance loserAttribute = loser.getAttribute(attribute.get());
            AttributeInstance restoredAttribute = restored.getAttribute(attribute.get());
            if (loserAttribute == null || restoredAttribute == null) { errors.add("Revocation attribute is unavailable."); continue; }
            for (AttributeGrant grant : deal.attributeGrants()) {
                if (!grant.attributeId().equals(clause.assetId()) || !grant.sourceId().equals(restored.getUUID())
                        || !grant.recipientId().equals(loser.getUUID())) continue;
                double before = loserAttribute.getBaseValue();
                double reduced = loserAttribute.getAttribute().value().sanitizeValue(before - grant.amount());
                double revoked = before - reduced;
                double restoredValue = restoredAttribute.getAttribute().value().sanitizeValue(restoredAttribute.getBaseValue() + revoked);
                revocations.add(new RevocationMutation(loserAttribute, reduced, restoredAttribute, restoredValue));
            }
        }
        if (!errors.isEmpty() || !plan.execute()) return false;
        for (RevocationMutation mutation : revocations) {
            mutation.loser().setBaseValue(mutation.loserValue());
            mutation.restored().setBaseValue(mutation.restoredValue());
        }
        return true;
    }

    private static boolean grantSoul(ServerPlayer holder, ServerPlayer owner) {
        if (custodyCopies(holder.server, owner.getUUID()) > 0) {
            boolean present = hasCustody(holder, owner.getUUID());
            if (present) owner.getData(DealmakerAttachments.PLAYER_DATA).setSoulClaimed(true);
            return present;
        }
        if (hasCustody(holder, owner.getUUID())) {
            owner.getData(DealmakerAttachments.PLAYER_DATA).setSoulClaimed(true);
            return true;
        }
        ItemStack soul = ClaimedSoulItem.create(DealmakerItems.CLAIMED_SOUL.get(), owner.getUUID(), owner.getName().getString());
        if (holder.getInventory().add(soul)) {
            owner.getData(DealmakerAttachments.PLAYER_DATA).setSoulClaimed(true);
            return true;
        }
        else {
            var stored = holder.getData(DealmakerAttachments.PLAYER_DATA).storedSouls();
            if (stored.size() < 27) {
                stored.add(owner.getUUID());
                owner.getData(DealmakerAttachments.PLAYER_DATA).setSoulClaimed(true);
                return true;
            }
        }
        return false;
    }

    private record HeldSoulSlot(UUID holderId, int slot) {}

    private static Optional<LocatedDeal> find(MinecraftServer server, UUID id) {
        for (ServerPlayer owner : server.getPlayerList().getPlayers()) {
            List<Deal> deals = owner.getData(DealmakerAttachments.PLAYER_DATA).deals();
            for (int i = 0; i < deals.size(); i++) if (deals.get(i).id().equals(id)) return Optional.of(new LocatedDeal(deals, i));
        }
        return Optional.empty();
    }

    private static long firstDueAt(List<DealClause> clauses, long now) {
        return clauses.stream().filter(c -> c.kind() == ClauseKind.RECURRING_ITEM_PAYMENT)
                .mapToLong(DealClause::periodTicks).min().stream().map(period -> now + period).findFirst().orElse(0L);
    }

    private static int countItems(ServerPlayer player, Item item) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (player.getInventory().getItem(i).is(item)) count += player.getInventory().getItem(i).getCount();
        return count;
    }

    /** Supports exact registry items and item-category tags from vanilla or any loaded mod. */
    private static int countMatchingItems(ServerPlayer player, String itemOrTag) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (matchesItemPredicate(stack, itemOrTag)) count += stack.getCount();
        }
        return count;
    }

    private static boolean matchesItemPredicate(ItemStack stack, String itemOrTag) {
        if (stack.isEmpty() || itemOrTag == null || itemOrTag.isBlank()) return false;
        if (!itemOrTag.startsWith("#")) {
            Item item = resolveItem(itemOrTag);
            return item != null && stack.is(item);
        }
        ResourceLocation tagId = ResourceLocation.tryParse(itemOrTag.substring(1));
        return tagId != null && stack.is(TagKey.create(Registries.ITEM, tagId));
    }

    private static boolean isKnownItemPredicate(String itemOrTag) {
        if (itemOrTag == null || itemOrTag.isBlank()) return false;
        if (!itemOrTag.startsWith("#")) return resolveItem(itemOrTag) != null;
        ResourceLocation id = ResourceLocation.tryParse(itemOrTag.substring(1));
        return id != null && net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getTag(TagKey.create(Registries.ITEM, id)).isPresent();
    }

    private static boolean isItemCondition(DealConditionType type) {
        return type == DealConditionType.PARTY_HAS_ITEM || type == DealConditionType.PARTY_LACKS_ITEM
                || type == DealConditionType.PARTY_HAS_ITEM_IN_SLOT || type == DealConditionType.ITEM_ENTERED_INVENTORY;
    }

    private static boolean isDimensionCondition(DealConditionType type) {
        return type == DealConditionType.PARTY_IN_DIMENSION || type == DealConditionType.PARTY_NOT_IN_DIMENSION
                || type == DealConditionType.PARTY_CHANGED_DIMENSION
                || type == DealConditionType.PARTY_WITHIN_COORDINATE_RADIUS
                || type == DealConditionType.PARTY_OUTSIDE_COORDINATE_RADIUS
                || type == DealConditionType.PARTY_ENTERED_COORDINATE_RADIUS
                || type == DealConditionType.PARTY_LEFT_COORDINATE_RADIUS;
    }

    private static boolean isKnownDimension(MinecraftServer server, String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location != null && server.getLevel(net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, location)) != null;
    }

    /** Accept legal ids and player-facing names such as "Stellar Gold Coin", case-insensitively. */
    private static Item resolveItem(String requested) {
        ResourceLocation id = ResourceLocation.tryParse(requested);
        if (id != null) {
            Item exact = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (exact != null) return exact;
        }
        String normalized = normalizeItemName(requested);
        int separator = requested == null ? -1 : requested.indexOf(':');
        String pathOnly = separator >= 0 ? normalizeItemName(requested.substring(separator + 1)) : normalized;
        Item found = null;
        for (var entry : net.minecraft.core.registries.BuiltInRegistries.ITEM.entrySet()) {
            String path = normalizeItemName(entry.getKey().location().getPath());
            String display = normalizeItemName(entry.getValue().getDescription().getString());
            if (!normalized.equals(path) && !normalized.equals(display) && !pathOnly.equals(path) && !pathOnly.equals(display)) continue;
            if (found != null && found != entry.getValue()) return null; // never guess an ambiguous item.
            found = entry.getValue();
        }
        return found;
    }

    private static String normalizeItemName(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Map<String, Integer> initialConditionValues(Deal deal, ServerPlayer dealmaker, ServerPlayer acceptor) {
        Map<String, Integer> values = new HashMap<>();
        for (DealClause clause : deal.clauses()) {
            for (DealConditionTerm term : clause.condition().terms()) {
                DealCondition condition = term.asCondition();
                if (condition.type() == DealConditionType.PARTY_STAT_INCREASED) {
                    values.put(conditionKey(condition), customStat(party(deal, dealmaker, acceptor, condition.party()), condition.assetId()));
                } else if (condition.type() == DealConditionType.ITEM_ENTERED_INVENTORY) {
                    values.put(conditionKey(condition), countMatchingItems(party(deal, dealmaker, acceptor, condition.party()), condition.assetId()));
                } else if (condition.type() == DealConditionType.PARTY_RESOURCE_INCREASED) {
                    values.put(conditionKey(condition), saturatedInt(resourceValue(
                            party(deal, dealmaker, acceptor, condition.party()), condition.assetId())));
                }
            }
        }
        return values;
    }

    private static Deal runRecurringActions(Deal deal, ServerPlayer dealmaker, ServerPlayer acceptor, long now) {
        Map<String, String> runtime = new HashMap<>(deal.runtimeValues());
        boolean changed = false;
        for (int index = 0; index < deal.clauses().size(); index++) {
            DealClause payment = deal.clauses().get(index);
            if (payment.trigger() != DealTrigger.ON_RECURRING_DUE) continue;
            String key = "due|" + index;
            long due;
            try { due = Long.parseLong(runtime.getOrDefault(key, Long.toString(deal.createdAt() + payment.periodTicks()))); }
            catch (NumberFormatException ignored) { due = deal.createdAt() + payment.periodTicks(); }
            if (due > now) continue;
            if (payment.kind() == ClauseKind.RECURRING_ITEM_PAYMENT) {
                ServerPlayer payer = party(deal, dealmaker, acceptor, payment.from());
                ServerPlayer payee = party(deal, dealmaker, acceptor, payment.to());
                Item item = resolveItem(payment.assetId());
                int count = (int) payment.amount();
                if (item != null && countItems(payer, item) >= count && canFitTransferredItems(payer, payee, item, count)) {
                    if (moveItemsPreservingComponents(payer, payee, item, count)) {
                        runtime.put(key, Long.toString(now + payment.periodTicks()));
                        changed = true;
                        continue;
                    }
                }
                if (item != null && countItems(payer, item) >= count) {
                    runtime.put(key, Long.toString(now + 20L));
                    changed = true;
                    continue;
                }
                DealClause forfeit = deal.clauses().stream()
                        .filter(c -> c.kind() == ClauseKind.FORFEIT_SOUL && c.periodTicks() == payment.periodTicks())
                        .findFirst().orElse(null);
                if (forfeit != null && grantSoul(party(deal, dealmaker, acceptor, forfeit.to()),
                        party(deal, dealmaker, acceptor, forfeit.from()))) {
                    payer.sendSystemMessage(Component.literal("Payment defaulted. The explicitly stated soul was forfeited.")
                            .withStyle(ChatFormatting.DARK_RED));
                    return deal.withRuntimeValues(runtime).withStatus(DealStatus.COMPLETED);
                }
            } else {
                if (payment.kind() == ClauseKind.TRANSFER_ITEM_AMOUNT) {
                    Item item = resolveItem(payment.assetId());
                    ServerPlayer payer = party(deal, dealmaker, acceptor, payment.from());
                    if (item != null && countItems(payer, item) < (int) payment.amount()) {
                        DealClause forfeit = deal.clauses().stream()
                                .filter(c -> c.kind() == ClauseKind.FORFEIT_SOUL && c.periodTicks() == payment.periodTicks())
                                .findFirst().orElse(null);
                        if (forfeit != null && grantSoul(party(deal, dealmaker, acceptor, forfeit.to()),
                                party(deal, dealmaker, acceptor, forfeit.from()))) {
                            payer.sendSystemMessage(Component.literal("Payment defaulted. The explicitly stated soul was forfeited.")
                                    .withStyle(ChatFormatting.DARK_RED));
                            return deal.withRuntimeValues(runtime).withStatus(DealStatus.COMPLETED);
                        }
                    }
                }
                Deal single = new Deal(deal.id(), deal.dealmakerId(), deal.acceptorId(), deal.originalText(),
                        List.of(payment), DealStatus.ACTIVE, deal.createdAt(), due);
                List<String> errors = new ArrayList<>();
                TransferPlan plan = TransferPlan.validate(single, dealmaker, acceptor, errors, DealTrigger.ON_RECURRING_DUE);
                if (errors.isEmpty() && plan.execute()) {
                    DealmakerMod.LOGGER.info("Devil Bargen recurring action executed: deal={}, clause={}, kind={}",
                            deal.id(), index + 1, payment.kind());
                    if (plan.endsDeal()) {
                        Deal completed = deal.withRuntimeValues(runtime).withStatus(DealStatus.COMPLETED);
                        notifyDealEnded(dealmaker, acceptor, completed);
                        return completed;
                    }
                } else {
                    DealmakerMod.LOGGER.info("Devil Bargen recurring action skipped safely: deal={}, clause={}, errors={}",
                            deal.id(), index + 1, errors);
                }
            }
            runtime.put(key, Long.toString(now + payment.periodTicks()));
            changed = true;
        }
        return changed ? deal.withRuntimeValues(runtime) : deal;
    }

    private static Map<String, String> initialRuntimeValues(Deal deal, ServerPlayer dealmaker, ServerPlayer acceptor) {
        Map<String, String> values = new HashMap<>();
        for (DealClause clause : deal.clauses()) {
            for (DealConditionTerm term : clause.condition().terms()) {
                DealCondition condition = term.asCondition();
                if (isEdgeCondition(condition.type())) {
                    // A condition that is already true when accepted must execute on the first observation.
                    // After that execution the stored true value prevents repeats until it becomes false and re-arms.
                    values.put("edge|" + conditionKey(condition), Boolean.FALSE.toString());
                }
                if (condition.type() == DealConditionType.PARTY_CHANGED_DIMENSION) {
                    ServerPlayer subject = party(deal, dealmaker, acceptor, condition.party());
                    values.put("dimension|" + conditionKey(condition), subject.level().dimension().location().toString());
                }
            }
        }
        for (DealClause clause : deal.clauses()) {
            if (clause.kind() == ClauseKind.REDIRECT_RESOURCE_GAIN_PERCENT) {
                ServerPlayer source = party(deal, dealmaker, acceptor, clause.from());
                values.put(resourceKey(clause), Double.toString(resourceValue(source, clause.assetId())));
            }
        }
        for (int index = 0; index < deal.clauses().size(); index++) {
            DealClause clause = deal.clauses().get(index);
            if (clause.trigger() == DealTrigger.ON_RECURRING_DUE) {
                values.put("due|" + index, Long.toString(deal.createdAt() + clause.periodTicks()));
            }
        }
        return values;
    }

    private static Deal runResourceGainRedirects(Deal deal, ServerPlayer dealmaker, ServerPlayer acceptor) {
        Map<String, String> values = new HashMap<>(deal.runtimeValues());
        boolean changed = false;
        for (DealClause clause : deal.clauses()) {
            if (clause.kind() != ClauseKind.REDIRECT_RESOURCE_GAIN_PERCENT) continue;
            ServerPlayer from = party(deal, dealmaker, acceptor, clause.from());
            ServerPlayer to = party(deal, dealmaker, acceptor, clause.to());
            String key = resourceKey(clause);
            double current = resourceValue(from, clause.assetId());
            double baseline = parseDouble(values.get(key), current);
            double rawGain = Math.max(0.0, current - baseline);
            String suppressionKey = from.getUUID() + "|" + clause.assetId();
            double suppressed = Math.min(rawGain, SUPPRESSED_RESOURCE_GAINS.getOrDefault(suppressionKey, 0.0));
            SUPPRESSED_RESOURCE_GAINS.computeIfPresent(suppressionKey, (ignored, value) -> value <= suppressed ? null : value - suppressed);
            double gain = rawGain - suppressed;
            double redirected = gain * clause.amount() / 100.0;
            if (redirected > 0.0 && transferResource(from, to, clause.assetId(), redirected)) {
                DealmakerMod.LOGGER.info("Devil Bargen redirected resource gain: deal={}, resource={}, source={}, recipient={}, gain={}, redirected={}",
                        deal.id(), clause.assetId(), from.getUUID(), to.getUUID(), gain, redirected);
                current = resourceValue(from, clause.assetId());
            }
            values.put(key, Double.toString(current));
            changed = true;
        }
        return changed ? deal.withRuntimeValues(values) : deal;
    }

    private static String resourceKey(DealClause clause) {
        return "resource|" + clause.from() + "|" + clause.to() + "|" + clause.assetId();
    }

    private static double resourceValue(ServerPlayer player, String resource) {
        IExistence existence = TensuraStorages.getExistenceFrom(player);
        return switch (resource) {
            case "ep" -> existence.getEP();
            case "magicule" -> existence.getMagicule();
            case "aura" -> existence.getAura();
            default -> 0.0;
        };
    }

    private static boolean transferResource(ServerPlayer from, ServerPlayer to, String resource, double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0 || resourceValue(from, resource) < amount) return false;
        IExistence source = TensuraStorages.getExistenceFrom(from);
        IExistence target = TensuraStorages.getExistenceFrom(to);
        switch (resource) {
            case "ep" -> { source.setEP(source.getEP() - amount); target.setEP(target.getEP() + amount); }
            case "magicule" -> { source.setMagicule(source.getMagicule() - amount); target.setMagicule(target.getMagicule() + amount); }
            case "aura" -> { source.setAura(source.getAura() - amount); target.setAura(target.getAura() + amount); }
            default -> { return false; }
        }
        source.markDirty();
        target.markDirty();
        SUPPRESSED_RESOURCE_GAINS.merge(to.getUUID() + "|" + resource, amount, Double::sum);
        return true;
    }

    private static boolean destroyResource(ServerPlayer player, String resource, double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0 || resourceValue(player, resource) < amount) return false;
        IExistence existence = TensuraStorages.getExistenceFrom(player);
        switch (resource) {
            case "ep" -> existence.setEP(existence.getEP() - amount);
            case "magicule" -> existence.setMagicule(existence.getMagicule() - amount);
            case "aura" -> existence.setAura(existence.getAura() - amount);
            default -> { return false; }
        }
        existence.markDirty();
        return true;
    }

    private static double parseDouble(String value, double fallback) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int saturatedInt(double value) {
        if (!Double.isFinite(value)) return 0;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.floor(value)));
    }

    private static Deal runAutomationConditions(Deal deal, ServerPlayer dealmaker, ServerPlayer acceptor) {
        Map<String, Integer> values = new HashMap<>(deal.conditionValues());
        Map<String, String> runtime = new HashMap<>(deal.runtimeValues());
        boolean changed = false;
        for (DealClause clause : deal.clauses()) {
            if (clause.trigger() != DealTrigger.ON_CONDITION_MET) continue;
            DealCondition condition = clause.condition();
            if (!automationConditionMatches(condition, deal, dealmaker, acceptor, values, runtime)) continue;
            List<String> errors = new ArrayList<>();
            TransferPlan plan = TransferPlan.validate(deal, dealmaker, acceptor, errors, DealTrigger.ON_CONDITION_MET, condition);
            if (!errors.isEmpty() || !plan.execute()) continue;
            DealmakerMod.LOGGER.info("Devil Bargen automation executed: deal={}, trigger={}, condition={}, ended={}",
                    deal.id(), DealTrigger.ON_CONDITION_MET, describeCondition(condition), plan.endsDeal());
            if (condition.type() == DealConditionType.PARTY_STAT_INCREASED) {
                values.put(conditionKey(condition), customStat(party(deal, dealmaker, acceptor, condition.party()), condition.assetId()));
                changed = true;
            } else if (condition.type() == DealConditionType.ITEM_ENTERED_INVENTORY) {
                values.put(conditionKey(condition), countMatchingItems(party(deal, dealmaker, acceptor, condition.party()), condition.assetId()));
                changed = true;
            } else if (condition.type() == DealConditionType.PARTY_RESOURCE_INCREASED) {
                values.put(conditionKey(condition), saturatedInt(resourceValue(
                        party(deal, dealmaker, acceptor, condition.party()), condition.assetId())));
                changed = true;
            }
            Deal updated = changed ? deal.withConditionValues(values) : deal;
            updated = runtime.equals(updated.runtimeValues()) ? updated : updated.withRuntimeValues(runtime);
            if (plan.endsDeal()) {
                notifyDealEnded(dealmaker, acceptor, deal);
                return updated.withStatus(DealStatus.COMPLETED);
            }
            deal = updated;
            changed = false;
        }
        Deal updated = changed ? deal.withConditionValues(values) : deal;
        return runtime.equals(updated.runtimeValues()) ? updated : updated.withRuntimeValues(runtime);
    }

    private static boolean breachConditionMatches(DealCondition condition, Deal deal, ServerPlayer dealmaker,
                                                  ServerPlayer acceptor) {
        return automationConditionMatches(condition, deal, dealmaker, acceptor,
                new HashMap<>(deal.conditionValues()), new HashMap<>(deal.runtimeValues()));
    }

    private static boolean automationConditionMatches(DealCondition condition, Deal deal, ServerPlayer dealmaker,
                                                      ServerPlayer acceptor, Map<String, Integer> values,
                                                      Map<String, String> runtime) {
        if (condition.type() == DealConditionType.PARTY_STAT_INCREASED) {
            int now = customStat(party(deal, dealmaker, acceptor, condition.party()), condition.assetId());
            int baseline = values.getOrDefault(conditionKey(condition), now);
            if (now != baseline) {
                DealmakerMod.LOGGER.info("Devil Bargen stat condition observed: deal={}, party={}, stat={}, baseline={}, current={}, requiredIncrease={}",
                        deal.id(), condition.party(), condition.assetId(), baseline, now, condition.amount());
            }
            return now >= baseline + condition.amount();
        }
        if (condition.type() == DealConditionType.ITEM_ENTERED_INVENTORY) {
            int now = countMatchingItems(party(deal, dealmaker, acceptor, condition.party()), condition.assetId());
            return now >= values.getOrDefault(conditionKey(condition), now) + condition.amount();
        }
        if (condition.type() == DealConditionType.PARTY_RESOURCE_INCREASED) {
            int now = saturatedInt(resourceValue(party(deal, dealmaker, acceptor, condition.party()), condition.assetId()));
            return (long) now >= (long) values.getOrDefault(conditionKey(condition), now) + condition.amount();
        }
        if (condition.type() == DealConditionType.PARTY_CHANGED_DIMENSION) {
            ServerPlayer subject = party(deal, dealmaker, acceptor, condition.party());
            String key = "dimension|" + conditionKey(condition);
            String now = subject.level().dimension().location().toString();
            String before = runtime.put(key, now);
            return before != null && !before.equals(now)
                    && (condition.dimensionId().isEmpty() || now.equals(condition.dimensionId()));
        }
        if (isEdgeCondition(condition.type())) {
            String key = "edge|" + conditionKey(condition);
            boolean now = conditionMatches(condition, deal, dealmaker, acceptor);
            boolean before = Boolean.parseBoolean(runtime.put(key, Boolean.toString(now)));
            return ConditionEdge.shouldFire(before, now);
        }
        return conditionMatches(condition, deal, dealmaker, acceptor);
    }

    private static boolean isEdgeCondition(DealConditionType type) {
        return type == DealConditionType.PARTY_WITHIN_DISTANCE_OF_PARTY
                || type == DealConditionType.PARTY_OUTSIDE_DISTANCE_OF_PARTY
                || type == DealConditionType.PARTY_IN_DIMENSION
                || type == DealConditionType.PARTY_NOT_IN_DIMENSION
                || type == DealConditionType.PARTY_WITHIN_COORDINATE_RADIUS
                || type == DealConditionType.PARTY_OUTSIDE_COORDINATE_RADIUS
                || type == DealConditionType.PARTY_ENTERED_COORDINATE_RADIUS
                || type == DealConditionType.PARTY_LEFT_COORDINATE_RADIUS
                || type == DealConditionType.PARTY_WEATHER_IS
                || type == DealConditionType.PARTY_TIME_OF_DAY_IS
                || type == DealConditionType.PARTY_LIGHT_LEVEL_AT_LEAST;
    }

    private static String conditionKey(DealCondition condition) {
        return condition.party() + "|" + condition.type() + "|" + condition.assetId() + "|" + condition.amount()
                + "|" + condition.slot()
                + "|" + condition.dimensionId() + "|" + condition.x() + "|" + condition.y() + "|" + condition.z()
                + "|" + condition.radius() + "|" + condition.useX() + condition.useY() + condition.useZ()
                + "|" + condition.negated() + "|" + condition.logic()
                + "|" + condition.additionalTerms();
    }

    private static int customStat(ServerPlayer player, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !isKnownCustomStat(id)) return 0;
        try {
            // StatType uses identity-keyed entries. Resolve the registry's canonical value rather
            // than feeding it a newly parsed-but-equal ResourceLocation (which creates an invalid Stat).
            ResourceLocation canonical = net.minecraft.core.registries.BuiltInRegistries.CUSTOM_STAT.get(key);
            return canonical == null ? 0 : player.getStats().getValue(Stats.CUSTOM, canonical);
        } catch (RuntimeException exception) {
            DealmakerMod.LOGGER.warn("Devil Bargen could not read custom statistic {} for {}: {}",
                    id, player.getGameProfile().getName(), exception.toString());
            return 0;
        }
    }

    private static boolean isKnownCustomStat(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key != null && net.minecraft.core.registries.BuiltInRegistries.CUSTOM_STAT.containsKey(key);
    }

    private static int itemCapacity(ServerPlayer player, Item item) {
        int capacity = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) capacity += item.getDefaultMaxStackSize();
            else if (stack.is(item)) capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.isEmpty()) capacity += item.getDefaultMaxStackSize();
            else if (stack.is(item)) capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return capacity;
    }

    private static void takeItems(ServerPlayer player, Item item, int count) {
        for (int i = 0; i < player.getInventory().getContainerSize() && count > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(item)) continue;
            int take = Math.min(count, stack.getCount());
            stack.shrink(take);
            count -= take;
        }
    }

    private static void giveItems(ServerPlayer player, Item item, int count) {
        while (count > 0) {
            ItemStack stack = new ItemStack(item, Math.min(count, item.getDefaultMaxStackSize()));
            count -= stack.getCount();
            if (!player.getInventory().add(stack)) player.drop(stack, false);
        }
    }

    /** Moves real source stacks so enchantments, custom data, damage, names, and mod components are never laundered away. */
    private static boolean moveItemsPreservingComponents(ServerPlayer from, ServerPlayer to, Item item, int count) {
        List<ItemStack> moved = new ArrayList<>();
        for (int slot = 0; slot < from.getInventory().getContainerSize() && count > 0; slot++) {
            ItemStack source = from.getInventory().getItem(slot);
            if (!source.is(item)) continue;
            int amount = Math.min(count, source.getCount());
            ItemStack part = source.copyWithCount(amount);
            source.shrink(amount);
            moved.add(part);
            count -= amount;
        }
        if (count != 0) return false;
        for (ItemStack stack : moved) {
            if (!to.getInventory().add(stack) || !stack.isEmpty()) return false;
        }
        return true;
    }

    private static boolean canFitTransferredItems(ServerPlayer from, ServerPlayer to, Item item, int count) {
        List<ItemStack> destination = to.getInventory().items.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (int slot = 0; slot < from.getInventory().getContainerSize() && count > 0; slot++) {
            ItemStack source = from.getInventory().getItem(slot);
            if (!source.is(item)) continue;
            int moving = Math.min(count, source.getCount());
            for (ItemStack target : destination) {
                if (moving <= 0) break;
                if (!target.isEmpty() && ItemStack.isSameItemSameComponents(target, source)) {
                    int accepted = Math.min(moving, target.getMaxStackSize() - target.getCount());
                    target.grow(accepted);
                    moving -= accepted;
                }
            }
            for (int index = 0; index < destination.size() && moving > 0; index++) {
                if (!destination.get(index).isEmpty()) continue;
                int accepted = Math.min(moving, source.getMaxStackSize());
                destination.set(index, source.copyWithCount(accepted));
                moving -= accepted;
            }
            if (moving > 0) return false;
            count -= Math.min(count, source.getCount());
        }
        return count == 0;
    }

    private static int maximumTransferableItems(ServerPlayer from, ServerPlayer to, Item item) {
        int low = 0;
        int high = countItems(from, item);
        while (low < high) {
            int candidate = low + (high - low + 1) / 2;
            if (canFitTransferredItems(from, to, item, candidate)) low = candidate;
            else high = candidate - 1;
        }
        return low;
    }

    private static List<Deal> collectInvolvedDeals(ServerPlayer player) {
        List<Deal> deals = new ArrayList<>();
        for (ServerPlayer owner : player.server.getPlayerList().getPlayers()) {
            for (Deal deal : owner.getData(DealmakerAttachments.PLAYER_DATA).deals()) {
                if (deal.dealmakerId().equals(player.getUUID()) || deal.acceptorId().equals(player.getUUID())) {
                    deals.add(deal);
                }
            }
        }
        return deals;
    }

    private static Component dealPage(ServerPlayer viewer, Deal deal) {
        boolean canSever = deal.dealmakerId().equals(viewer.getUUID());
        Component page = Component.literal("Status: ACTIVE\n")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal("Dealmaker: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(partyName(viewer.server, deal.dealmakerId()) + "\n").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("Acceptor: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(partyName(viewer.server, deal.acceptorId()) + "\n").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("Id: " + deal.id().toString().substring(0, 8) + "\n\n").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(clampBookText(deal.originalText()) + "\n\n").withStyle(ChatFormatting.GRAY));
        if (canSever) {
            page = page.copy().append(Component.literal("[Sever this deal]").withStyle(style -> style
                    .withColor(ChatFormatting.RED).withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/devilbargen sever " + deal.id()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("End this active deal. Completed transfers stay.")))));
        }
        return page;
    }

    private static String partyName(MinecraftServer server, UUID id) {
        if (id == null || id.equals(OPEN_ACCEPTOR)) return "unclaimed";
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        return server.getProfileCache().get(id).map(GameProfile::getName).orElse(id.toString().substring(0, 8));
    }

    private static void openEphemeralBook(ServerPlayer player, String title, String author,
                                          List<Filterable<Component>> pages) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(title), author, 0, List.copyOf(pages), true));
        placeViewerInHand(player, book);
        openViewerBook(player, book);
    }

    private static ItemStack obtainViewerBook(ServerPlayer player, ViewerBook.Kind kind) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (ViewerBook.kind(stack).filter(kind::equals).isPresent()) {
                return stack;
            }
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        ViewerBook.mark(book, kind, player.getUUID(), "");
        placeViewerInHand(player, book);
        return book;
    }

    private static void placeViewerInHand(ServerPlayer player, ItemStack book) {
        ItemStack main = player.getMainHandItem();
        if (main == book) return;
        if (ViewerBook.kind(main).filter(kind -> ViewerBook.kind(book).filter(kind::equals).isPresent()).isPresent()) {
            // Already holding another copy of the same viewer kind; replace it.
            player.setItemInHand(InteractionHand.MAIN_HAND, book);
            return;
        }
        if (main.isEmpty()) {
            // Remove from whatever inventory slot it currently occupies, then equip.
            clearMatchingStack(player, book);
            player.setItemInHand(InteractionHand.MAIN_HAND, book);
            return;
        }
        // Prefer main hand: move current main-hand item into inventory, then equip the viewer.
        ItemStack displaced = main.copy();
        clearMatchingStack(player, book);
        player.setItemInHand(InteractionHand.MAIN_HAND, book);
        if (!player.getInventory().add(displaced)) player.drop(displaced, false);
    }

    private static void clearMatchingStack(ServerPlayer player, ItemStack book) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot) == book) {
                player.getInventory().setItem(slot, ItemStack.EMPTY);
                return;
            }
        }
    }

    private static void openViewerBook(ServerPlayer player, ItemStack book) {
        placeViewerInHand(player, book);
        if (player.getMainHandItem() == book || ItemStack.isSameItemSameComponents(player.getMainHandItem(), book)) {
            player.openItemGui(player.getMainHandItem(), InteractionHand.MAIN_HAND);
            return;
        }
        if (player.getOffhandItem() == book || ItemStack.isSameItemSameComponents(player.getOffhandItem(), book)) {
            player.openItemGui(player.getOffhandItem(), InteractionHand.OFF_HAND);
            return;
        }
        player.openItemGui(book, InteractionHand.MAIN_HAND);
    }

    private static void refreshViewerBook(ServerPlayer player, ItemStack book, ViewerBook.Kind kind, boolean force) {
        boolean dealmaker = isDealmaker(player);
        String fingerprint = dealmaker ? viewerFingerprint(player, kind) : "empty";
        UUID boundTo = ViewerBook.boundTo(book).orElse(null);
        if (!force
                && fingerprint.equals(ViewerBook.fingerprint(book))
                && player.getUUID().equals(boundTo)) {
            return;
        }

        String title = kind == ViewerBook.Kind.LEDGER ? "Contract Ledger" : "Active Deals";
        List<Filterable<Component>> pages = dealmaker
                ? (kind == ViewerBook.Kind.LEDGER ? buildLedgerPages(player) : buildDealsPages(player))
                : List.of(Filterable.passThrough(Component.literal(" ")));
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(title), "Devil Bargen", 0, List.copyOf(pages), true));
        book.set(DataComponents.CUSTOM_NAME, Component.literal(title).withStyle(ChatFormatting.DARK_RED));
        ViewerBook.mark(book, kind, player.getUUID(), fingerprint);
    }

    private static List<Filterable<Component>> buildLedgerPages(ServerPlayer player) {
        List<LedgerBook> ledger = player.getData(DealmakerAttachments.PLAYER_DATA).ledger();
        List<Filterable<Component>> pages = new ArrayList<>();
        pages.add(Filterable.passThrough(Component.literal("Devil Bargen\nLedger\n\n")
                .withStyle(ChatFormatting.DARK_RED)
                .append(Component.literal(ledger.isEmpty()
                        ? "Empty. Hold a signed book and use Ledger to save a template.\n"
                        : ledger.size() + "/" + LedgerBook.MAX_ENTRIES + " saved.\nUse [Create] to spawn a copy.\n")
                        .withStyle(ChatFormatting.GRAY))));
        for (int index = 0; index < ledger.size(); index++) {
            LedgerBook entry = ledger.get(index);
            int createIndex = index;
            Component page = Component.literal("#" + (index + 1) + " " + entry.title() + "\n")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("by " + entry.author() + "\n")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(shorten(String.join(" ", entry.pages())) + "\n\n")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("[Create copy]").withStyle(style -> style
                            .withColor(ChatFormatting.GREEN).withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/devilbargen ledger create " + createIndex))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Create a new signed book from this template")))))
                    .append(Component.literal("  "))
                    .append(Component.literal("[Delete]").withStyle(style -> style
                            .withColor(ChatFormatting.RED).withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/devilbargen ledger delete " + createIndex))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Remove this template from the ledger")))));
            pages.add(Filterable.passThrough(page));
        }
        return pages;
    }

    private static List<Filterable<Component>> buildDealsPages(ServerPlayer player) {
        List<Deal> deals = collectInvolvedDeals(player);
        List<Deal> active = deals.stream().filter(deal -> deal.status() == DealStatus.ACTIVE).toList();
        List<Deal> other = deals.stream().filter(deal -> deal.status() != DealStatus.ACTIVE).toList();
        List<Filterable<Component>> pages = new ArrayList<>();
        pages.add(Filterable.passThrough(Component.literal("Devil Bargen\nActive Deals\n\n")
                .withStyle(ChatFormatting.DARK_RED)
                .append(Component.literal(active.isEmpty()
                        ? "No active deals.\n"
                        : active.size() + " active deal(s).\nClick [Sever] on a deal page to end it.\n")
                        .withStyle(ChatFormatting.GRAY))));
        for (Deal deal : active) {
            pages.add(Filterable.passThrough(dealPage(player, deal)));
        }
        if (!other.isEmpty()) {
            StringBuilder summary = new StringBuilder("Other deals:\n\n");
            for (Deal deal : other) {
                summary.append('[').append(deal.status()).append("] ")
                        .append(partyName(player.server, deal.dealmakerId()))
                        .append(" ↔ ")
                        .append(partyName(player.server, deal.acceptorId()))
                        .append('\n');
            }
            pages.add(Filterable.passThrough(Component.literal(clampBookText(summary.toString())).withStyle(ChatFormatting.DARK_GRAY)));
        }
        return pages;
    }

    private static String viewerFingerprint(ServerPlayer player, ViewerBook.Kind kind) {
        if (kind == ViewerBook.Kind.LEDGER) {
            List<LedgerBook> ledger = player.getData(DealmakerAttachments.PLAYER_DATA).ledger();
            StringBuilder builder = new StringBuilder("L").append(ledger.size());
            for (LedgerBook entry : ledger) {
                builder.append('|').append(entry.title()).append(':').append(entry.pages().size());
            }
            return builder.toString();
        }
        StringBuilder builder = new StringBuilder("D");
        for (Deal deal : collectInvolvedDeals(player)) {
            builder.append('|').append(deal.id()).append(':').append(deal.status());
        }
        return builder.toString();
    }

    private static boolean isDealmaker(ServerPlayer player) {
        return player.getData(DealmakerAttachments.PLAYER_DATA).dealmaker();
    }

    private static ItemStack toWrittenBook(LedgerBook entry) {
        List<Filterable<Component>> pages = entry.pages().stream()
                .<Filterable<Component>>map(page -> Filterable.passThrough(Component.literal(page)))
                .toList();
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(entry.title()), entry.author(), 0, pages, false));
        return book;
    }

    private static String clampBookText(String text) {
        if (text == null || text.isBlank()) return " ";
        return text.length() <= 220 ? text : text.substring(0, 217) + "...";
    }

    private static String shorten(String text) {
        return text.length() <= 70 ? text : text.substring(0, 67) + "...";
    }

    private static String describe(List<DealClause> clauses) {
        List<String> lines = new ArrayList<>();
        for (DealClause clause : clauses) {
            String from = clause.from() == Party.DEALMAKER ? "dealmaker" : "acceptor";
            String to = clause.to() == Party.DEALMAKER ? "dealmaker" : "acceptor";
            lines.add(switch (clause.kind()) {
                case TRANSFER_ALL_SKILLS_IN_CATEGORY -> "• " + from + " transfers every " + clause.assetId() + " skill to " + to;
                case TRANSFER_ALL_UNIQUE_SKILLS -> "• " + from + " transfers every Unique skill to " + to;
                case TRANSFER_ALL_ULTIMATE_SKILLS -> "• " + from + " transfers every Ultimate skill to " + to;
                case TRANSFER_ALL_MAGICS -> "• " + from + " transfers every Magic to " + to;
                case TRANSFER_ALL_BATTLEWILLS -> "• " + from + " transfers every Battlewill to " + to;
                case TRANSFER_SKILL -> "• " + from + " transfers skill " + clause.assetId() + " to " + to;
                case SHARE_SKILL -> "• " + from + " shares a cost-free, non-masterable copy of " + clause.assetId() + " with " + to;
                case TRANSFER_ATTRIBUTE_PERCENT -> "• " + from + " transfers " + clause.amount() + "% of "
                        + clause.assetId() + " to " + to;
                case TRANSFER_ATTRIBUTE_AMOUNT -> "• " + from + " transfers " + clause.amount() + " of "
                        + clause.assetId() + " to " + to;
                case REVOKE_ATTRIBUTE_GRANTS -> "• if breached, " + from + " loses this deal's granted "
                        + clause.assetId() + " back to " + to;
                case TRANSFER_ITEM_AMOUNT -> "• " + from + " transfers " + (long) clause.amount() + " "
                        + clause.assetId() + " to " + to;
                case TRANSFER_ALL_MATCHING_ITEMS -> "• " + from + " transfers every " + clause.assetId()
                        + " that currently fits to " + to;
                case TRANSFER_INVENTORY_SLOT -> "• " + from + " transfers the exact item in "
                        + clause.assetId() + " to " + to;
                case TRANSFER_RESOURCE_AMOUNT -> "• " + from + " transfers " + clause.amount() + " " + clause.assetId() + " to " + to;
                case TRANSFER_RESOURCE_PERCENT -> "• " + from + " transfers " + clause.amount() + "% of current " + clause.assetId() + " to " + to;
                case DRAIN_RESOURCE_AMOUNT -> "• " + from + " has " + clause.amount() + " " + clause.assetId() + " drained to " + to;
                case DRAIN_RESOURCE_PERCENT -> "• " + from + " has " + clause.amount() + "% of current " + clause.assetId() + " drained to " + to;
                case DESTROY_RESOURCE_AMOUNT -> "• " + from + " loses " + clause.amount() + " " + clause.assetId();
                case DESTROY_RESOURCE_PERCENT -> "• " + from + " loses " + clause.amount() + "% of current " + clause.assetId();
                case TRANSFER_EP_AMOUNT -> "• " + from + " transfers " + clause.amount() + " EP to " + to;
                case TRANSFER_EP_PERCENT -> "• " + from + " transfers " + clause.amount() + "% of current EP to " + to;
                case TRANSFER_MAGICULE_AMOUNT -> "• " + from + " transfers " + clause.amount() + " magicule to " + to;
                case TRANSFER_MAGICULE_PERCENT -> "• " + from + " transfers " + clause.amount() + "% of current magicule to " + to;
                case TRANSFER_AURA_AMOUNT -> "• " + from + " transfers " + clause.amount() + " aura to " + to;
                case TRANSFER_AURA_PERCENT -> "• " + from + " transfers " + clause.amount() + "% of current aura to " + to;
                case REDIRECT_RESOURCE_GAIN_PERCENT -> "• " + to + " receives " + clause.amount() + "% of newly gained "
                        + clause.assetId() + " from " + from;
                case REDIRECT_DAMAGE_PERCENT -> "• " + to + " receives " + clause.amount() + "% of damage intended for " + from;
                case RECURRING_ITEM_PAYMENT -> "• " + from + " pays " + (long) clause.amount() + " " + clause.assetId()
                        + " to " + to + " every " + clause.periodTicks() + " ticks";
                case FORFEIT_SOUL -> clause.periodTicks() > 0L
                        ? "• on the stated default, " + from + " forfeits their soul to " + to
                        : "• " + from + " voluntarily transfers their soul to " + to;
                case KILL_PLAYER -> "• " + from + " dies";
                case DEAL_DAMAGE_AMOUNT -> "• " + from + " takes " + clause.amount() + " direct damage";
                case SET_ON_FIRE_SECONDS -> "• " + from + " burns for " + (long) clause.amount() + " seconds";
                case END_DEAL -> "• the deal ends";
            });
        }
        return String.join("\n", lines);
    }

    private static void notifyDealEnded(ServerPlayer dealmaker, ServerPlayer acceptor, Deal deal) {
        Component message = Component.literal("A Devil Bargen contract ended as agreed ["
                        + deal.id().toString().substring(0, 8) + "]. Completed transfers are not reversed.")
                .withStyle(ChatFormatting.GOLD);
        dealmaker.sendSystemMessage(message);
        if (acceptor != null) acceptor.sendSystemMessage(message);
        refreshHeldViewerBooks(dealmaker);
        if (acceptor != null) refreshHeldViewerBooks(acceptor);
    }

    private record LocatedDeal(List<Deal> list, int index) {
        Deal deal() { return list.get(index); }
        void replace(Deal deal) { list.set(index, deal); }
    }

    private static ServerPlayer party(Deal deal, ServerPlayer dealmaker, ServerPlayer acceptor, Party party) {
        return party == Party.DEALMAKER ? dealmaker : acceptor;
    }

    private static boolean conditionMatches(DealCondition condition, Deal deal, ServerPlayer dealmaker, ServerPlayer acceptor) {
        return groupMatches(condition, leaf -> conditionMatchesLeaf(leaf, deal, dealmaker, acceptor));
    }

    private static boolean groupMatches(DealCondition condition, java.util.function.Predicate<DealCondition> matcher) {
        boolean any = false;
        for (DealConditionTerm term : condition.terms()) {
            boolean value = matcher.test(term.asCondition());
            if (term.negated()) value = !value;
            if (condition.logic() == ConditionLogic.ALL && !value) return false;
            if (condition.logic() == ConditionLogic.ANY && value) return true;
            any |= value;
        }
        return condition.logic() == ConditionLogic.ALL || any;
    }

    private static boolean conditionMatchesLeaf(DealCondition condition, Deal deal, ServerPlayer dealmaker, ServerPlayer acceptor) {
        if (condition.type() == DealConditionType.ALWAYS) return true;
        if (condition.type() == DealConditionType.PARTY_HARMED_PARTY) return false;
        if (condition.type() == DealConditionType.CHAT_MESSAGE_CONTAINS) return false;
        if (condition.type() == DealConditionType.PARTY_DIES) return false;
        if (condition.type() == DealConditionType.PARTY_ACCEPTED_OTHER_DEAL) return false;
        if (condition.type() == DealConditionType.PARTY_USES_SKILL_CATEGORY
                || condition.type() == DealConditionType.PARTY_USES_ANY_SKILL || condition.type() == DealConditionType.PARTY_USES_SKILL
                || condition.type() == DealConditionType.PARTY_USES_MAGIC || condition.type() == DealConditionType.PARTY_USES_BATTLEWILL) return false;
        ServerPlayer subject = party(deal, dealmaker, acceptor, condition.party());
        if (condition.type() == DealConditionType.PARTY_IS_CROUCHING) return subject.isCrouching();
        if (condition.type() == DealConditionType.PARTY_IS_SPRINTING) return subject.isSprinting();
        if (condition.type() == DealConditionType.PARTY_IS_SWIMMING) return subject.isSwimming();
        if (condition.type() == DealConditionType.PARTY_IS_ON_GROUND) return subject.onGround();
        if (condition.type() == DealConditionType.PARTY_WEATHER_IS) {
            return switch (condition.assetId()) {
                case "clear" -> !subject.level().isRaining();
                case "rain" -> subject.level().isRaining() && !subject.level().isThundering();
                case "thunder" -> subject.level().isThundering();
                default -> false;
            };
        }
        if (condition.type() == DealConditionType.PARTY_TIME_OF_DAY_IS) {
            long time = Math.floorMod(subject.level().getDayTime(), 24_000L);
            return switch (condition.assetId()) {
                case "dawn" -> time < 1_000L;
                case "day" -> time >= 1_000L && time < 12_000L;
                case "dusk" -> time >= 12_000L && time < 13_000L;
                case "night" -> time >= 13_000L;
                default -> false;
            };
        }
        if (condition.type() == DealConditionType.PARTY_LIGHT_LEVEL_AT_LEAST) {
            return subject.level().getMaxLocalRawBrightness(subject.blockPosition()) >= condition.amount();
        }
        if (condition.type() == DealConditionType.PARTY_WITHIN_DISTANCE_OF_PARTY || condition.type() == DealConditionType.PARTY_OUTSIDE_DISTANCE_OF_PARTY) {
            ServerPlayer target = "DEALMAKER".equals(condition.assetId()) ? dealmaker : acceptor;
            boolean within = subject.level() == target.level() && subject.distanceToSqr(target) <= (double) condition.amount() * condition.amount();
            return condition.type() == DealConditionType.PARTY_WITHIN_DISTANCE_OF_PARTY ? within : !within;
        }
        if (condition.type() == DealConditionType.PARTY_IN_DIMENSION
                || condition.type() == DealConditionType.PARTY_NOT_IN_DIMENSION) {
            boolean inDimension = subject.level().dimension().location().toString().equals(condition.dimensionId());
            return condition.type() == DealConditionType.PARTY_IN_DIMENSION ? inDimension : !inDimension;
        }
        if (condition.type() == DealConditionType.PARTY_CHANGED_DIMENSION) return false;
        if (condition.type() == DealConditionType.PARTY_WITHIN_COORDINATE_RADIUS
                || condition.type() == DealConditionType.PARTY_OUTSIDE_COORDINATE_RADIUS
                || condition.type() == DealConditionType.PARTY_ENTERED_COORDINATE_RADIUS
                || condition.type() == DealConditionType.PARTY_LEFT_COORDINATE_RADIUS) {
            // An omitted dimension intentionally means that the coordinate predicate applies in
            // whichever dimension the subject is currently in.  A supplied ID remains exact.
            boolean sameDimension = condition.dimensionId().isEmpty()
                    || subject.level().dimension().location().toString().equals(condition.dimensionId());
            double dx = subject.getX() - condition.x();
            double dy = subject.getY() - condition.y();
            double dz = subject.getZ() - condition.z();
            boolean within = sameDimension && SpatialDistance.within(dx, dy, dz, condition.radius(),
                    condition.useX(), condition.useY(), condition.useZ());
            return condition.type() == DealConditionType.PARTY_WITHIN_COORDINATE_RADIUS
                    || condition.type() == DealConditionType.PARTY_ENTERED_COORDINATE_RADIUS ? within : !within;
        }
        if (condition.type() == DealConditionType.PARTY_RESOURCE_AT_LEAST) {
            return resourceValue(subject, condition.assetId()) >= condition.amount();
        }
        if (condition.type() == DealConditionType.PARTY_RESOURCE_INCREASED) return false;
        if (condition.type() == DealConditionType.PARTY_STAT_AT_LEAST) {
            return customStat(subject, condition.assetId()) >= condition.amount();
        }
        if (condition.type() == DealConditionType.PARTY_STAT_INCREASED) return false;
        if (condition.type() == DealConditionType.PARTY_HAS_ITEM_IN_SLOT) {
            int slot = inventorySlot(subject, condition.slot());
            ItemStack stack = slot < 0 ? ItemStack.EMPTY : subject.getInventory().getItem(slot);
            return matchesItemPredicate(stack, condition.assetId()) && stack.getCount() >= condition.amount();
        }
        if (condition.type() == DealConditionType.PARTY_HOLDS_ANY_ITEM) {
            int slot = inventorySlot(subject, condition.slot());
            return slot >= 0 && !subject.getInventory().getItem(slot).isEmpty();
        }
        int count = countMatchingItems(subject, condition.assetId());
        return condition.type() == DealConditionType.PARTY_HAS_ITEM ? count >= condition.amount() : count < condition.amount();
    }

    /** Player inventory indexes: hotbar 0-8, armor feet/legs/chest/head 36-39, offhand 40. */
    private static int inventorySlot(String slot) {
        return inventorySlot(null, slot);
    }

    private static int inventorySlot(ServerPlayer player, String slot) {
        if (slot == null) return -1;
        if (slot.matches("HOTBAR_[1-9]")) return slot.charAt(slot.length() - 1) - '1';
        return switch (slot) {
            case "MAIN_HAND" -> player == null ? -1 : player.getInventory().selected;
            case "ARMOR_FEET" -> 36;
            case "ARMOR_LEGS" -> 37;
            case "ARMOR_CHEST" -> 38;
            case "ARMOR_HEAD" -> 39;
            case "OFFHAND", "OFF_HAND" -> 40;
            default -> -1;
        };
    }

    private static int freeMainInventorySlots(ServerPlayer player) {
        int free = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.isEmpty()) free++;
        return free;
    }

    private record TransferPlan(List<SkillTransfer> skills, List<AttributeMutation> attributeMutations,
                                List<ItemTransfer> items, List<SlotTransfer> slots, List<EpTransfer> epTransfers,
                                List<AllItemTransfer> allItems, List<SharedSkillGrant> sharedSkills, List<ResourceTransfer> resources,
                                List<ResourceDrain> drains, List<SoulTransfer> immediateSouls, List<ServerPlayer> deaths,
                                List<DamageEffect> damageEffects, List<FireEffect> fireEffects,
                                List<AttributeGrant> attributeGrants, boolean endsDeal) {
        static TransferPlan validate(Deal deal, ServerPlayer dealmaker, ServerPlayer acceptor, List<String> errors,
                                     DealTrigger trigger) {
            return validate(deal, dealmaker, acceptor, errors, trigger, null);
        }

        static TransferPlan validate(Deal deal, ServerPlayer dealmaker, ServerPlayer acceptor, List<String> errors,
                                     DealTrigger trigger, DealCondition forcedCondition) {
            List<SkillTransfer> skills = new ArrayList<>();
            List<AttributeTransfer> attributes = new ArrayList<>();
            List<ItemTransfer> items = new ArrayList<>();
            List<AllItemTransfer> allItems = new ArrayList<>();
            List<SharedSkillGrant> sharedSkills = new ArrayList<>();
            List<SlotTransfer> slots = new ArrayList<>();
            List<EpTransfer> epTransfers = new ArrayList<>();
            List<ResourceTransfer> resources = new ArrayList<>();
            List<ResourceDrain> drains = new ArrayList<>();
            List<SoulTransfer> souls = new ArrayList<>();
            List<ServerPlayer> deaths = new ArrayList<>();
            List<DamageEffect> damageEffects = new ArrayList<>();
            List<FireEffect> fireEffects = new ArrayList<>();
            Map<ServerPlayer, Set<Integer>> claimedSlots = new HashMap<>();
            boolean endsDeal = false;

            for (DealClause clause : deal.clauses()) {
                if (clause.trigger() != trigger || !(forcedCondition != null ? clause.condition().equals(forcedCondition)
                        : conditionMatches(clause.condition(), deal, dealmaker, acceptor))) continue;
                ServerPlayer from = party(deal, dealmaker, acceptor, clause.from());
                ServerPlayer to = party(deal, dealmaker, acceptor, clause.to());
                if (isSkillCategory(clause.kind())) {
                    SkillStorage fromSkills = SkillAPI.getSkillsFrom(from);
                    SkillStorage toSkills = SkillAPI.getSkillsFrom(to);
                    List<ManasSkillInstance> transferred = new ArrayList<>();
                    for (ManasSkillInstance instance : fromSkills.getLearnedSkills()) {
                        if (matchesCategory(instance, clause.kind(), clause.assetId())) {
                            if (toSkills.getSkill(instance.getSkillId()).isPresent()) {
                                errors.add(to.getName().getString() + " already owns " + instance.getSkillId() + ".");
                            } else transferred.add(costFreeTransferCopy(instance));
                        }
                    }
                    if (transferred.isEmpty()) errors.add(from.getName().getString() + " owns no skills in the requested category.");
                    else skills.add(new SkillTransfer(from, to, transferred));
                } else if (clause.kind() == ClauseKind.TRANSFER_SKILL) {
                    ResourceLocation id = ResourceLocation.tryParse(clause.assetId());
                    SkillStorage fromSkills = SkillAPI.getSkillsFrom(from);
                    SkillStorage toSkills = SkillAPI.getSkillsFrom(to);
                    ManasSkillInstance skill = id == null ? null : fromSkills.getSkill(id).orElse(null);
                    if (skill == null) errors.add(from.getName().getString() + " does not own skill " + clause.assetId() + ".");
                    else if (toSkills.getSkill(id).isPresent()) errors.add(to.getName().getString() + " already owns skill " + clause.assetId() + ".");
                    else skills.add(new SkillTransfer(from, to, List.of(costFreeTransferCopy(skill))));
                } else if (clause.kind() == ClauseKind.SHARE_SKILL) {
                    ResourceLocation id = ResourceLocation.tryParse(clause.assetId());
                    SkillStorage fromSkills = SkillAPI.getSkillsFrom(from);
                    SkillStorage toSkills = SkillAPI.getSkillsFrom(to);
                    ManasSkillInstance skill = id == null ? null : fromSkills.getSkill(id).orElse(null);
                    if (skill == null) errors.add(from.getName().getString() + " does not own skill " + clause.assetId() + ".");
                    else if (toSkills.getSkill(id).isPresent()) errors.add(to.getName().getString() + " already owns skill " + clause.assetId() + ".");
                    else sharedSkills.add(new SharedSkillGrant(from, to, sharedCopy(skill)));
                } else if (clause.kind() == ClauseKind.TRANSFER_ATTRIBUTE_PERCENT || clause.kind() == ClauseKind.TRANSFER_ATTRIBUTE_AMOUNT) {
                    ResourceLocation id = ResourceLocation.tryParse(clause.assetId());
                    Optional<Holder.Reference<Attribute>> attribute = id == null
                            ? Optional.empty() : net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getHolder(id);
                    if (attribute.isEmpty()) {
                        errors.add("Unknown attribute " + clause.assetId() + ".");
                        continue;
                    }
                    AttributeInstance source = from.getAttribute(attribute.get());
                    AttributeInstance target = to.getAttribute(attribute.get());
                    if (source == null || target == null) {
                        errors.add("Both parties must have attribute " + clause.assetId() + ".");
                        continue;
                    }
                    double amount = clause.kind() == ClauseKind.TRANSFER_ATTRIBUTE_PERCENT
                            ? source.getBaseValue() * clause.amount() / 100.0 : clause.amount();
                    attributes.add(new AttributeTransfer(from, to, source, target, amount, clause.assetId()));
                } else if (clause.kind() == ClauseKind.TRANSFER_ITEM_AMOUNT || clause.kind() == ClauseKind.RECURRING_ITEM_PAYMENT) {
                    Item item = resolveItem(clause.assetId());
                    int amount = (int) clause.amount();
                    if (item == null) errors.add("Unknown item " + clause.assetId() + ".");
                    else if (countItems(from, item) < amount) errors.add(from.getName().getString() + " does not have enough " + clause.assetId() + ".");
                    else if (!canFitTransferredItems(from, to, item, amount)) errors.add(to.getName().getString() + " has no room for " + clause.assetId() + ".");
                    else items.add(new ItemTransfer(from, to, item, amount));
                } else if (clause.kind() == ClauseKind.TRANSFER_ALL_MATCHING_ITEMS) {
                    Item item = resolveItem(clause.assetId());
                    if (item == null) errors.add("Unknown item " + clause.assetId() + ".");
                    else if (countItems(from, item) <= 0) errors.add(from.getName().getString() + " does not possess " + clause.assetId() + ".");
                    else allItems.add(new AllItemTransfer(from, to, item));
                } else if (clause.kind() == ClauseKind.TRANSFER_INVENTORY_SLOT) {
                    int sourceSlot = inventorySlot(from, clause.assetId());
                    ItemStack stack = sourceSlot < 0 ? ItemStack.EMPTY : from.getInventory().getItem(sourceSlot);
                    if (stack.isEmpty()) errors.add(from.getName().getString() + " has no item in " + clause.assetId() + ".");
                    else if (!claimedSlots.computeIfAbsent(from, ignored -> new java.util.HashSet<>()).add(sourceSlot))
                        errors.add("The same inventory slot cannot be transferred twice.");
                    else slots.add(new SlotTransfer(from, to, sourceSlot, stack.copy()));
                } else if (clause.kind() == ClauseKind.TRANSFER_RESOURCE_AMOUNT
                        || clause.kind() == ClauseKind.TRANSFER_RESOURCE_PERCENT) {
                    IExistence source = TensuraStorages.getExistenceFrom(from);
                    double available = switch (clause.assetId()) {
                        case "ep" -> source.getEP();
                        case "magicule" -> source.getMagicule();
                        case "aura" -> source.getAura();
                        default -> 0.0;
                    };
                    double amount = clause.kind() == ClauseKind.TRANSFER_RESOURCE_PERCENT
                            ? available * clause.amount() / 100.0D : clause.amount();
                    if (available < amount) errors.add(from.getName().getString() + " does not have enough " + clause.assetId() + ".");
                    else if (clause.assetId().equals("ep")) epTransfers.add(new EpTransfer(from, to, amount));
                    else resources.add(new ResourceTransfer(from, to, amount, clause.assetId().equals("magicule")));
                } else if (clause.kind() == ClauseKind.DRAIN_RESOURCE_AMOUNT
                        || clause.kind() == ClauseKind.DRAIN_RESOURCE_PERCENT
                        || clause.kind() == ClauseKind.DESTROY_RESOURCE_AMOUNT
                        || clause.kind() == ClauseKind.DESTROY_RESOURCE_PERCENT) {
                    double available = resourceValue(from, clause.assetId());
                    boolean percentage = clause.kind() == ClauseKind.DRAIN_RESOURCE_PERCENT
                            || clause.kind() == ClauseKind.DESTROY_RESOURCE_PERCENT;
                    double amount = percentage ? available * clause.amount() / 100.0D : clause.amount();
                    if (available < amount) errors.add(from.getName().getString() + " does not have enough " + clause.assetId() + ".");
                    else drains.add(new ResourceDrain(from, to, amount, clause.assetId(),
                            clause.kind() == ClauseKind.DESTROY_RESOURCE_AMOUNT
                                    || clause.kind() == ClauseKind.DESTROY_RESOURCE_PERCENT));
                } else if (clause.kind() == ClauseKind.TRANSFER_EP_AMOUNT || clause.kind() == ClauseKind.TRANSFER_EP_PERCENT) {
                    IExistence source = TensuraStorages.getExistenceFrom(from);
                    double amount = clause.kind() == ClauseKind.TRANSFER_EP_PERCENT ? source.getEP() * clause.amount() / 100.0D : clause.amount();
                    if (source.getEP() < amount) errors.add(from.getName().getString() + " does not have enough EP.");
                    else epTransfers.add(new EpTransfer(from, to, amount));
                } else if (clause.kind() == ClauseKind.TRANSFER_MAGICULE_AMOUNT || clause.kind() == ClauseKind.TRANSFER_AURA_AMOUNT
                        || clause.kind() == ClauseKind.TRANSFER_MAGICULE_PERCENT || clause.kind() == ClauseKind.TRANSFER_AURA_PERCENT) {
                    IExistence source = TensuraStorages.getExistenceFrom(from);
                    boolean magicule = clause.kind() == ClauseKind.TRANSFER_MAGICULE_AMOUNT || clause.kind() == ClauseKind.TRANSFER_MAGICULE_PERCENT;
                    double available = magicule ? source.getMagicule() : source.getAura();
                    double amount = (clause.kind() == ClauseKind.TRANSFER_MAGICULE_PERCENT || clause.kind() == ClauseKind.TRANSFER_AURA_PERCENT)
                            ? available * clause.amount() / 100.0D : clause.amount();
                    if (available < amount) errors.add(from.getName().getString() + " does not have enough "
                            + (magicule ? "magicule." : "aura."));
                    else resources.add(new ResourceTransfer(from, to, amount, magicule));
                } else if (clause.kind() == ClauseKind.FORFEIT_SOUL
                        && (clause.periodTicks() == 0L || clause.trigger() == DealTrigger.ON_RECURRING_DUE)) {
                    if (!hasCustody(to, from.getUUID()) && to.getInventory().getFreeSlot() < 0
                            && to.getData(DealmakerAttachments.PLAYER_DATA).storedSouls().size() >= 27) {
                        errors.add(to.getName().getString() + " has no room for " + from.getName().getString() + "'s soul.");
                    } else {
                        souls.add(new SoulTransfer(from, to));
                    }
                } else if (clause.kind() == ClauseKind.KILL_PLAYER) {
                    if (!deaths.contains(from)) deaths.add(from);
                } else if (clause.kind() == ClauseKind.DEAL_DAMAGE_AMOUNT) {
                    damageEffects.add(new DamageEffect(from, (float) clause.amount()));
                } else if (clause.kind() == ClauseKind.SET_ON_FIRE_SECONDS) {
                    fireEffects.add(new FireEffect(from, (int) clause.amount()));
                } else if (clause.kind() == ClauseKind.END_DEAL) {
                    endsDeal = true;
                }
            }

            Map<ServerPlayer, Integer> requiredSlots = new HashMap<>();
            for (SlotTransfer transfer : slots) {
                requiredSlots.merge(transfer.to(), 1, Integer::sum);
                for (ItemTransfer item : items) {
                    if (item.from() == transfer.from() && transfer.stack().is(item.item())) {
                        errors.add("An exact inventory-slot transfer cannot be combined with an item-count transfer of the same item.");
                    }
                }
            }
            for (Map.Entry<ServerPlayer, Integer> entry : requiredSlots.entrySet()) {
                if (freeMainInventorySlots(entry.getKey()) < entry.getValue()) {
                    errors.add(entry.getKey().getName().getString() + " has no room for all requested slot transfers.");
                }
            }

            Map<AttributeInstance, Double> finalBases = new HashMap<>();
            for (AttributeTransfer transfer : attributes) {
                finalBases.putIfAbsent(transfer.from(), transfer.from().getBaseValue());
                finalBases.putIfAbsent(transfer.to(), transfer.to().getBaseValue());
                finalBases.compute(transfer.from(), (instance, value) -> value - transfer.amount());
                finalBases.compute(transfer.to(), (instance, value) -> value + transfer.amount());
            }
            List<AttributeMutation> mutations = new ArrayList<>();
            for (Map.Entry<AttributeInstance, Double> entry : finalBases.entrySet()) {
                double sanitized = entry.getKey().getAttribute().value().sanitizeValue(entry.getValue());
                if (Math.abs(sanitized - entry.getValue()) > 0.000001D) {
                    errors.add("That attribute transfer would exceed " + entry.getKey().getAttribute().value().getDescriptionId() + " limits.");
                } else {
                    mutations.add(new AttributeMutation(entry.getKey(), entry.getValue()));
                }
            }
            List<AttributeGrant> grants = attributes.stream().map(transfer -> new AttributeGrant(
                    transfer.fromPlayer().getUUID(), transfer.toPlayer().getUUID(), transfer.id(), transfer.amount())).toList();
            return new TransferPlan(skills, mutations, items, slots, epTransfers, allItems, sharedSkills, resources,
                    drains, souls, deaths, damageEffects, fireEffects, grants, endsDeal);
        }

        boolean execute() {
            TransactionSnapshot snapshot = TransactionSnapshot.capture(this);
            try {
                if (executeUnsafe()) return true;
            } catch (RuntimeException exception) {
                DealmakerMod.LOGGER.error("Devil Bargen transaction failed and will be rolled back", exception);
            }
            snapshot.restore();
            return false;
        }

        private boolean executeUnsafe() {
            Map<ServerPlayer, Map<Item, Integer>> requiredItems = new HashMap<>();
            for (ItemTransfer transfer : items) {
                requiredItems.computeIfAbsent(transfer.from(), ignored -> new HashMap<>())
                        .merge(transfer.item(), transfer.amount(), Integer::sum);
            }
            for (var playerEntry : requiredItems.entrySet()) {
                for (var itemEntry : playerEntry.getValue().entrySet()) {
                    if (countItems(playerEntry.getKey(), itemEntry.getKey()) < itemEntry.getValue()) return false;
                }
            }
            Map<ServerPlayer, Double> requiredEp = new HashMap<>();
            for (EpTransfer transfer : epTransfers) requiredEp.merge(transfer.from(), transfer.amount(), Double::sum);
            for (var entry : requiredEp.entrySet()) {
                if (TensuraStorages.getExistenceFrom(entry.getKey()).getEP() < entry.getValue()) return false;
            }
            Map<String, Double> requiredBalances = new HashMap<>();
            for (EpTransfer transfer : epTransfers) {
                requiredBalances.merge(transfer.from().getUUID() + "|ep", transfer.amount(), Double::sum);
            }
            for (ResourceTransfer transfer : resources) {
                requiredBalances.merge(transfer.from().getUUID() + "|" + (transfer.magicule() ? "magicule" : "aura"),
                        transfer.amount(), Double::sum);
            }
            for (ResourceDrain drain : drains) {
                requiredBalances.merge(drain.from().getUUID() + "|" + drain.resource(), drain.amount(), Double::sum);
            }
            for (Map.Entry<String, Double> entry : requiredBalances.entrySet()) {
                String[] key = entry.getKey().split("\\|", 2);
                ServerPlayer player = java.util.stream.Stream.concat(
                                epTransfers.stream().map(EpTransfer::from),
                                java.util.stream.Stream.concat(resources.stream().map(ResourceTransfer::from),
                                        drains.stream().map(ResourceDrain::from)))
                        .filter(candidate -> candidate.getUUID().toString().equals(key[0])).findFirst().orElse(null);
                if (player == null || resourceValue(player, key[1]) < entry.getValue()) return false;
            }
            Map<String, Double> requiredResources = new HashMap<>();
            for (ResourceTransfer transfer : resources) {
                requiredResources.merge(transfer.from().getUUID() + "|" + transfer.magicule(), transfer.amount(), Double::sum);
            }
            for (ResourceTransfer transfer : resources) {
                IExistence source = TensuraStorages.getExistenceFrom(transfer.from());
                double required = requiredResources.get(transfer.from().getUUID() + "|" + transfer.magicule());
                if ((transfer.magicule() ? source.getMagicule() : source.getAura()) < required) return false;
            }
            for (ItemTransfer transfer : items) {
                if (countItems(transfer.from(), transfer.item()) < transfer.amount()
                        || !canFitTransferredItems(transfer.from(), transfer.to(), transfer.item(), transfer.amount())) return false;
            }
            for (SlotTransfer transfer : slots) {
                if (!ItemStack.matches(transfer.from().getInventory().getItem(transfer.sourceSlot()), transfer.stack())
                        || transfer.to().getInventory().getFreeSlot() < 0) return false;
            }
            for (EpTransfer transfer : epTransfers) {
                if (TensuraStorages.getExistenceFrom(transfer.from()).getEP() < transfer.amount()) return false;
            }
            for (ResourceTransfer transfer : resources) {
                IExistence source = TensuraStorages.getExistenceFrom(transfer.from());
                if ((transfer.magicule() ? source.getMagicule() : source.getAura()) < transfer.amount()) return false;
            }
            for (ResourceDrain drain : drains) {
                if (resourceValue(drain.from(), drain.resource()) < drain.amount()) return false;
            }
            for (SkillTransfer transfer : skills) {
                SkillStorage source = SkillAPI.getSkillsFrom(transfer.from());
                SkillStorage target = SkillAPI.getSkillsFrom(transfer.to());
                for (ManasSkillInstance skill : transfer.skills()) {
                    if (source.getSkill(skill.getSkillId()).isEmpty() || target.getSkill(skill.getSkillId()).isPresent()) return false;
                }
            }
            for (SharedSkillGrant grant : sharedSkills) {
                SkillStorage source = SkillAPI.getSkillsFrom(grant.owner());
                SkillStorage recipient = SkillAPI.getSkillsFrom(grant.recipient());
                if (source.getSkill(grant.skill().getSkillId()).isEmpty() || recipient.getSkill(grant.skill().getSkillId()).isPresent()) return false;
            }

            List<SkillTransfer> moved = new ArrayList<>();
            for (SkillTransfer transfer : skills) {
                SkillStorage source = SkillAPI.getSkillsFrom(transfer.from());
                SkillStorage target = SkillAPI.getSkillsFrom(transfer.to());
                for (ManasSkillInstance skill : transfer.skills()) {
                    source.forgetSkill(skill.getSkillId(), Component.literal("Transferred by Devil Bargen"));
                    if (!target.learnSkill(skill, Component.literal("Received through Devil Bargen"))) {
                        rollbackSkills(moved, transfer);
                        return false;
                    }
                    transferTrulyUniqueOwnership(transfer.from(), transfer.to(), skill);
                }
                moved.add(transfer);
            }
            for (SharedSkillGrant grant : sharedSkills) {
                if (!SkillAPI.getSkillsFrom(grant.recipient()).learnSkill(grant.skill(), Component.literal("Shared through Devil Bargen"))) {
                    return false;
                }
            }
            for (ItemTransfer transfer : items) {
                if (!moveItemsPreservingComponents(transfer.from(), transfer.to(), transfer.item(), transfer.amount())) return false;
            }
            for (AllItemTransfer transfer : allItems) {
                int amount = maximumTransferableItems(transfer.from(), transfer.to(), transfer.item());
                if (amount <= 0) continue;
                if (!moveItemsPreservingComponents(transfer.from(), transfer.to(), transfer.item(), amount)) return false;
            }
            for (SlotTransfer transfer : slots) {
                int targetSlot = transfer.to().getInventory().getFreeSlot();
                if (targetSlot < 0) return false;
                transfer.from().getInventory().setItem(transfer.sourceSlot(), ItemStack.EMPTY);
                transfer.to().getInventory().setItem(targetSlot, transfer.stack().copy());
            }
            for (EpTransfer transfer : epTransfers) {
                IExistence from = TensuraStorages.getExistenceFrom(transfer.from());
                IExistence to = TensuraStorages.getExistenceFrom(transfer.to());
                from.setEP(from.getEP() - transfer.amount());
                to.setEP(to.getEP() + transfer.amount());
                from.markDirty();
                to.markDirty();
            }
            for (ResourceTransfer transfer : resources) {
                IExistence from = TensuraStorages.getExistenceFrom(transfer.from());
                IExistence to = TensuraStorages.getExistenceFrom(transfer.to());
                if (transfer.magicule()) {
                    from.setMagicule(from.getMagicule() - transfer.amount());
                    to.setMagicule(to.getMagicule() + transfer.amount());
                } else {
                    from.setAura(from.getAura() - transfer.amount());
                    to.setAura(to.getAura() + transfer.amount());
                }
                from.markDirty();
                to.markDirty();
            }
            for (ResourceDrain drain : drains) {
                if (drain.destroy()) {
                    if (!destroyResource(drain.from(), drain.resource(), drain.amount())) return false;
                } else if (!transferResource(drain.from(), drain.to(), drain.resource(), drain.amount())) {
                    return false;
                }
            }
            for (AttributeMutation mutation : attributeMutations) mutation.instance().setBaseValue(mutation.baseValue());
            for (SoulTransfer soul : immediateSouls) if (!grantSoul(soul.to(), soul.from())) return false;
            for (ServerPlayer player : deaths) if (player.isAlive()) player.kill();
            for (DamageEffect effect : damageEffects) {
                if (effect.player().isAlive()) effect.player().hurt(effect.player().damageSources().generic(), effect.amount());
            }
            for (FireEffect effect : fireEffects) {
                if (effect.player().isAlive()) effect.player().igniteForSeconds(effect.seconds());
            }
            return true;
        }

        private Set<ServerPlayer> participants() {
            Set<ServerPlayer> players = new java.util.HashSet<>();
            skills.forEach(t -> { players.add(t.from()); players.add(t.to()); });
            sharedSkills.forEach(t -> { players.add(t.owner()); players.add(t.recipient()); });
            items.forEach(t -> { players.add(t.from()); players.add(t.to()); });
            allItems.forEach(t -> { players.add(t.from()); players.add(t.to()); });
            slots.forEach(t -> { players.add(t.from()); players.add(t.to()); });
            epTransfers.forEach(t -> { players.add(t.from()); players.add(t.to()); });
            resources.forEach(t -> { players.add(t.from()); players.add(t.to()); });
            drains.forEach(t -> { players.add(t.from()); players.add(t.to()); });
            immediateSouls.forEach(t -> { players.add(t.from()); players.add(t.to()); });
            players.addAll(deaths);
            damageEffects.forEach(t -> players.add(t.player()));
            fireEffects.forEach(t -> players.add(t.player()));
            return players;
        }

        private static void rollbackSkills(List<SkillTransfer> moved, SkillTransfer current) {
            SkillStorage currentSource = SkillAPI.getSkillsFrom(current.from());
            SkillStorage currentTarget = SkillAPI.getSkillsFrom(current.to());
            for (ManasSkillInstance skill : current.skills()) {
                currentTarget.forgetSkill(skill.getSkillId(), Component.literal("Devil Bargen rollback"));
                if (currentSource.getSkill(skill.getSkillId()).isEmpty()) {
                    currentSource.learnSkill(skill.copy(), Component.literal("Devil Bargen rollback"));
                }
            }
            for (SkillTransfer transfer : moved.reversed()) {
                SkillStorage source = SkillAPI.getSkillsFrom(transfer.from());
                SkillStorage target = SkillAPI.getSkillsFrom(transfer.to());
                for (ManasSkillInstance skill : transfer.skills()) {
                    target.forgetSkill(skill.getSkillId(), Component.literal("Devil Bargen rollback"));
                    source.learnSkill(skill.copy(), Component.literal("Devil Bargen rollback"));
                }
            }
        }

        private static boolean isSkillCategory(ClauseKind kind) {
            return kind == ClauseKind.TRANSFER_ALL_SKILLS_IN_CATEGORY
                    || kind == ClauseKind.TRANSFER_ALL_UNIQUE_SKILLS || kind == ClauseKind.TRANSFER_ALL_ULTIMATE_SKILLS
                    || kind == ClauseKind.TRANSFER_ALL_MAGICS || kind == ClauseKind.TRANSFER_ALL_BATTLEWILLS;
        }

        private static ManasSkillInstance sharedCopy(ManasSkillInstance original) {
            ManasSkillInstance copy = costFreeTransferCopy(original);
            copy.setMastery(0.0);
            copy.getOrCreateTag().putBoolean(SHARED_SKILL_TAG, true);
            return copy;
        }

        /** Storage-level grants never charge acquisition MP, EP, magicule, or soul-energy costs. */
        private static ManasSkillInstance costFreeTransferCopy(ManasSkillInstance original) {
            ManasSkillInstance copy = original.copy();
            copy.getOrCreateTag().putBoolean("NoMagiculeCost", true);
            return copy;
        }

        /**
         * Tensura's {@code trulyUnique} rule records each Unique skill in a world-level ownership map.
         * A normal storage transfer does not update that map, which makes the skill available again during
         * reincarnation. Move the ownership record only after the recipient has learned the skill.
         */
        private static void transferTrulyUniqueOwnership(ServerPlayer from, ServerPlayer to, ManasSkillInstance instance) {
            if (!(instance.getSkill() instanceof Skill skill) || skill.getType() != Skill.SkillType.UNIQUE
                    || !from.server.overworld().getGameRules().getBoolean(TensuraGameRules.TRULY_UNIQUE)) return;
            ITrulyUnique uniqueStorage = TensuraStorages.getUniqueStorageFrom(from.server.overworld());
            uniqueStorage.addSkill(instance.getSkillId(), to.getUUID());
        }

        private static boolean matchesCategory(ManasSkillInstance instance, ClauseKind kind, String category) {
            if (kind == ClauseKind.TRANSFER_ALL_SKILLS_IN_CATEGORY) {
                return switch (category) {
                    case "unique" -> instance.getSkill() instanceof Skill skill && skill.getType() == Skill.SkillType.UNIQUE;
                    case "ultimate" -> instance.getSkill() instanceof Skill skill && skill.getType() == Skill.SkillType.ULTIMATE;
                    case "magic" -> instance.getSkill() instanceof Magic;
                    case "battlewill" -> instance.getSkill() instanceof Battlewill;
                    default -> false;
                };
            }
            return switch (kind) {
                case TRANSFER_ALL_UNIQUE_SKILLS -> instance.getSkill() instanceof Skill skill && skill.getType() == Skill.SkillType.UNIQUE;
                case TRANSFER_ALL_ULTIMATE_SKILLS -> instance.getSkill() instanceof Skill skill && skill.getType() == Skill.SkillType.ULTIMATE;
                case TRANSFER_ALL_MAGICS -> instance.getSkill() instanceof Magic;
                case TRANSFER_ALL_BATTLEWILLS -> instance.getSkill() instanceof Battlewill;
                default -> false;
            };
        }
    }

    private record TransactionSnapshot(Map<ServerPlayer, List<ItemStack>> inventories,
                                       Map<ServerPlayer, List<ManasSkillInstance>> skills,
                                       Map<ServerPlayer, double[]> resources,
                                       Map<ServerPlayer, List<UUID>> storedSouls,
                                       Map<AttributeInstance, Double> attributes,
                                       Map<ResourceLocation, Optional<UUID>> trulyUniqueOwners) {
        static TransactionSnapshot capture(TransferPlan plan) {
            Map<ServerPlayer, List<ItemStack>> inventories = new HashMap<>();
            Map<ServerPlayer, List<ManasSkillInstance>> skills = new HashMap<>();
            Map<ServerPlayer, double[]> resources = new HashMap<>();
            Map<ServerPlayer, List<UUID>> souls = new HashMap<>();
            for (ServerPlayer player : plan.participants()) {
                List<ItemStack> inventory = new ArrayList<>();
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    inventory.add(player.getInventory().getItem(slot).copy());
                }
                inventories.put(player, inventory);
                skills.put(player, SkillAPI.getSkillsFrom(player).getLearnedSkills().stream()
                        .map(ManasSkillInstance::copy).toList());
                IExistence existence = TensuraStorages.getExistenceFrom(player);
                resources.put(player, new double[]{existence.getEP(), existence.getMagicule(), existence.getAura()});
                souls.put(player, List.copyOf(player.getData(DealmakerAttachments.PLAYER_DATA).storedSouls()));
            }
            Map<AttributeInstance, Double> attributes = new HashMap<>();
            for (AttributeMutation mutation : plan.attributeMutations()) {
                attributes.put(mutation.instance(), mutation.instance().getBaseValue());
            }
            Map<ResourceLocation, Optional<UUID>> trulyUniqueOwners = new HashMap<>();
            if (!plan.skills().isEmpty()) {
                ServerPlayer player = plan.skills().getFirst().from();
                if (player.server.overworld().getGameRules().getBoolean(TensuraGameRules.TRULY_UNIQUE)) {
                    ITrulyUnique uniqueStorage = TensuraStorages.getUniqueStorageFrom(player.server.overworld());
                    for (SkillTransfer transfer : plan.skills()) {
                        for (ManasSkillInstance instance : transfer.skills()) {
                            if (instance.getSkill() instanceof Skill skill && skill.getType() == Skill.SkillType.UNIQUE) {
                                ResourceLocation id = instance.getSkillId();
                                trulyUniqueOwners.putIfAbsent(id, uniqueStorage.hasSkill(id)
                                        ? Optional.ofNullable(uniqueStorage.getOwner(id)) : Optional.empty());
                            }
                        }
                    }
                }
            }
            return new TransactionSnapshot(inventories, skills, resources, souls, attributes, trulyUniqueOwners);
        }

        void restore() {
            inventories.forEach((player, stacks) -> {
                for (int slot = 0; slot < stacks.size(); slot++) player.getInventory().setItem(slot, stacks.get(slot).copy());
            });
            skills.forEach((player, instances) -> {
                SkillStorage storage = SkillAPI.getSkillsFrom(player);
                List<ResourceLocation> current = storage.getLearnedSkills().stream().map(ManasSkillInstance::getSkillId).toList();
                current.forEach(id -> storage.forgetSkill(id, Component.literal("Devil Bargen transaction rollback")));
                instances.forEach(instance -> storage.learnSkill(instance.copy(), Component.literal("Devil Bargen transaction rollback")));
            });
            resources.forEach((player, values) -> {
                IExistence existence = TensuraStorages.getExistenceFrom(player);
                existence.setEP(values[0]);
                existence.setMagicule(values[1]);
                existence.setAura(values[2]);
                existence.markDirty();
            });
            storedSouls.forEach((player, values) -> {
                List<UUID> souls = player.getData(DealmakerAttachments.PLAYER_DATA).storedSouls();
                souls.clear();
                souls.addAll(values);
            });
            attributes.forEach(AttributeInstance::setBaseValue);
            if (!trulyUniqueOwners.isEmpty()) {
                ServerPlayer player = inventories.keySet().iterator().next();
                ITrulyUnique uniqueStorage = TensuraStorages.getUniqueStorageFrom(player.server.overworld());
                trulyUniqueOwners.forEach((id, owner) -> {
                    uniqueStorage.removeSkill(id);
                    owner.ifPresent(uuid -> uniqueStorage.addSkill(id, uuid));
                });
            }
        }
    }

    private record SkillTransfer(ServerPlayer from, ServerPlayer to, List<ManasSkillInstance> skills) {}
    private record AttributeTransfer(ServerPlayer fromPlayer, ServerPlayer toPlayer, AttributeInstance from,
                                     AttributeInstance to, double amount, String id) {}
    private record AttributeMutation(AttributeInstance instance, double baseValue) {}
    private record ItemTransfer(ServerPlayer from, ServerPlayer to, Item item, int amount) {}
    private record AllItemTransfer(ServerPlayer from, ServerPlayer to, Item item) {}
    private record SharedSkillGrant(ServerPlayer owner, ServerPlayer recipient, ManasSkillInstance skill) {}
    private record SlotTransfer(ServerPlayer from, ServerPlayer to, int sourceSlot, ItemStack stack) {}
    private record EpTransfer(ServerPlayer from, ServerPlayer to, double amount) {}
    private record ResourceTransfer(ServerPlayer from, ServerPlayer to, double amount, boolean magicule) {}
    private record ResourceDrain(ServerPlayer from, ServerPlayer to, double amount, String resource, boolean destroy) {}
    private record SoulTransfer(ServerPlayer from, ServerPlayer to) {}
    private record DamageEffect(ServerPlayer player, float amount) {}
    private record FireEffect(ServerPlayer player, int seconds) {}
    private record RevocationMutation(AttributeInstance loser, double loserValue,
                                      AttributeInstance restored, double restoredValue) {}
}
