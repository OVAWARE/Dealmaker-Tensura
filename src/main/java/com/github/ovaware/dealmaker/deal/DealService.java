package com.github.ovaware.dealmaker.deal;

import com.github.ovaware.dealmaker.DealmakerMod;
import com.github.ovaware.dealmaker.item.ClaimedSoulItem;
import com.github.ovaware.dealmaker.registry.DealmakerCapabilities;
import com.github.ovaware.dealmaker.registry.DealmakerItems;
import com.github.ovaware.dealmaker.storage.LedgerBook;
import com.github.ovaware.dealmaker.storage.SoulBoundInventoryContainer;
import com.github.ovaware.dealmaker.storage.SoulStorageContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Server-authoritative vanilla contract and soul-custody mechanics. */
public final class DealService {
    private static final UUID OPEN_ACCEPTOR = new UUID(0L, 0L);
    private static boolean applyingRedirectedDamage;

    private DealService() {}

    private static com.github.ovaware.dealmaker.storage.DealmakerPlayerData data(ServerPlayer player) {
        return DealmakerCapabilities.data(player);
    }

    public static String accept(ServerPlayer acceptor, UUID id) {
        Located located = locate(acceptor.server, id).orElse(null);
        if (located == null || located.deal().status() != DealStatus.PENDING) return "That pending deal does not exist.";
        Deal deal = located.deal();
        if (deal.dealmakerId().equals(acceptor.getUUID())) return "You cannot accept your own deal.";
        if (deal.hasAcceptor() && !deal.acceptorId().equals(acceptor.getUUID())) return "This contract has already been claimed.";
        ServerPlayer maker = acceptor.server.getPlayerList().getPlayer(deal.dealmakerId());
        if (maker == null) return "The dealmaker must be online.";
        if (DealBook.findDeal(acceptor, deal.id(), maker.getUUID()).isEmpty()) return "You must possess this contract book to accept it.";
        List<String> errors = new ArrayList<>(DealPolicy.validateClauses(deal.clauses()));
        if (!errors.isEmpty()) return "Deal rejected: " + String.join(" ", errors);
        if (!execute(deal, maker, acceptor, DealTrigger.ON_ACCEPTANCE)) return "Deal rejected because an asset changed during execution.";
        boolean active = deal.clauses().stream().anyMatch(c -> c.trigger() != DealTrigger.ON_ACCEPTANCE || c.kind() == ClauseKind.REDIRECT_DAMAGE_PERCENT);
        located.replace(deal.withAcceptor(acceptor.getUUID()).withStatus(active ? DealStatus.ACTIVE : DealStatus.COMPLETED));
        DealBook.findDeal(acceptor, deal.id(), maker.getUUID()).ifPresent(stack -> stack.shrink(1));
        maker.sendSystemMessage(Component.literal(acceptor.getName().getString() + " accepted the contract.").withStyle(ChatFormatting.GOLD));
        return "Contract accepted.";
    }

    public static String sever(ServerPlayer maker, UUID id) {
        Located located = locate(maker.server, id).orElse(null);
        if (located == null || !located.deal().dealmakerId().equals(maker.getUUID())) return "You do not own that deal.";
        if (located.deal().status() != DealStatus.ACTIVE) return "Only an active deal can be severed.";
        located.replace(located.deal().withStatus(DealStatus.SEVERED));
        return "Deal severed. Completed transfers are not reversed.";
    }

    public static void showDeals(ServerPlayer player) {
        List<Deal> deals = involved(player);
        player.sendSystemMessage(Component.literal("Devil Bargen deals:").withStyle(ChatFormatting.DARK_RED));
        if (deals.isEmpty()) player.sendSystemMessage(Component.literal("No deals.").withStyle(ChatFormatting.GRAY));
        for (Deal deal : deals) player.sendSystemMessage(Component.literal(deal.id() + " [" + deal.status() + "] " + deal.originalText()).withStyle(ChatFormatting.GRAY));
    }

    public static void debugDeal(ServerPlayer player, UUID id) {
        Located located = locate(player.server, id).orElse(null);
        if (located == null || (!located.deal().dealmakerId().equals(player.getUUID()) && !located.deal().acceptorId().equals(player.getUUID()))) {
            player.sendSystemMessage(Component.literal("You are not a party to that deal.").withStyle(ChatFormatting.RED));
            return;
        }
        player.sendSystemMessage(Component.literal("Deal " + located.deal().id() + " [" + located.deal().status() + "]").withStyle(ChatFormatting.GOLD));
        located.deal().clauses().forEach(clause -> player.sendSystemMessage(Component.literal(clause.toString()).withStyle(ChatFormatting.GRAY)));
    }

    public static String createLedgerBook(ServerPlayer player, int index) {
        List<LedgerBook> ledger = data(player).ledger();
        if (index < 0 || index >= ledger.size()) return "Unknown ledger entry.";
        LedgerBook entry = ledger.get(index);
        ItemStack book = new ItemStack(net.minecraft.world.item.Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", entry.title());
        tag.putString("author", entry.author());
        ListTag pages = new ListTag();
        entry.pages().forEach(page -> pages.add(StringTag.valueOf(page)));
        tag.put("pages", pages);
        if (!player.getInventory().add(book)) player.drop(book, false);
        return "Created a copy of \"" + entry.title() + "\".";
    }

    public static String deleteLedgerBook(ServerPlayer player, int index) {
        List<LedgerBook> ledger = data(player).ledger();
        if (index < 0 || index >= ledger.size()) return "Unknown ledger entry.";
        return "Removed \"" + ledger.remove(index).title() + "\" from the ledger.";
    }

    public static void refreshViewerBooks(ServerPlayer player) {}

    public static void returnSoulsInIllegalContainers(ServerPlayer player) {
        returnSoulsInIllegalContainer(player, player.containerMenu);
    }

    public static void returnSoulsInIllegalContainer(ServerPlayer player, AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory() || slot.container instanceof SoulStorageContainer) continue;
            ClaimedSoulItem.owner(slot.getItem()).ifPresent(owner -> {
                ItemStack soul = slot.getItem().copy();
                slot.set(ItemStack.EMPTY);
                if (!player.getInventory().add(soul)) data(player).storedSouls().add(owner);
            });
        }
    }

    public static void tick(ServerPlayer maker) {
        if (maker.tickCount % 20 != 0) return;
        for (Deal deal : List.copyOf(data(maker).deals())) {
            if (deal.status() != DealStatus.ACTIVE) continue;
            ServerPlayer acceptor = maker.server.getPlayerList().getPlayer(deal.acceptorId());
            if (acceptor == null) continue;
            for (DealClause clause : deal.clauses()) {
                if (clause.trigger() == DealTrigger.ON_RECURRING_DUE && maker.serverLevel().getGameTime() >= deal.nextDueAt()) {
                    execute(new Deal(deal.id(), deal.dealmakerId(), deal.acceptorId(), deal.originalText(), List.of(clause), deal.status(), deal.createdAt(), deal.nextDueAt()), maker, acceptor, DealTrigger.ON_RECURRING_DUE);
                }
            }
        }
    }

    public static void onPlayerDied(ServerPlayer player) {
        for (ServerPlayer maker : player.server.getPlayerList().getPlayers()) {
            for (Deal deal : data(maker).deals()) {
                if (deal.status() != DealStatus.ACTIVE) continue;
                ServerPlayer acceptor = player.server.getPlayerList().getPlayer(deal.acceptorId());
                if (acceptor != null) executeMatching(deal, maker, acceptor, DealTrigger.ON_CONDITION_MET, DealConditionType.PARTY_DIES, player);
            }
        }
    }

    public static void onPlayerHarmed(ServerPlayer victim, ServerPlayer attacker) {
        for (ServerPlayer maker : victim.server.getPlayerList().getPlayers()) {
            for (Deal deal : data(maker).deals()) {
                ServerPlayer acceptor = victim.server.getPlayerList().getPlayer(deal.acceptorId());
                if (deal.status() == DealStatus.ACTIVE && acceptor != null) executeMatching(deal, maker, acceptor, DealTrigger.ON_CONDITION_MET, DealConditionType.PARTY_HARMED_PARTY, attacker);
            }
        }
    }

    public static void onPlayerChat(ServerPlayer sender, String text) {
        for (ServerPlayer maker : sender.server.getPlayerList().getPlayers()) for (Deal deal : data(maker).deals()) {
            ServerPlayer acceptor = sender.server.getPlayerList().getPlayer(deal.acceptorId());
            if (deal.status() != DealStatus.ACTIVE || acceptor == null) continue;
            for (DealClause clause : deal.clauses()) if (clause.trigger() == DealTrigger.ON_CONDITION_MET
                    && clause.condition().type() == DealConditionType.CHAT_MESSAGE_CONTAINS
                    && party(clause.condition().party(), maker, acceptor) == sender
                    && text.toLowerCase(java.util.Locale.ROOT).contains(clause.condition().assetId().toLowerCase(java.util.Locale.ROOT))) {
                execute(deal, maker, acceptor, DealTrigger.ON_CONDITION_MET);
            }
        }
    }

    public static float redirectIncomingDamage(ServerPlayer victim, net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (applyingRedirectedDamage) return amount;
        float remaining = amount;
        for (ServerPlayer maker : victim.server.getPlayerList().getPlayers()) for (Deal deal : data(maker).deals()) {
            if (deal.status() != DealStatus.ACTIVE) continue;
            ServerPlayer acceptor = victim.server.getPlayerList().getPlayer(deal.acceptorId());
            if (acceptor == null) continue;
            for (DealClause clause : deal.clauses()) if (clause.kind() == ClauseKind.REDIRECT_DAMAGE_PERCENT && party(clause.from(), maker, acceptor) == victim) {
                ServerPlayer recipient = party(clause.to(), maker, acceptor);
                float redirected = remaining * (float) (clause.amount() / 100D);
                applyingRedirectedDamage = true;
                try { recipient.hurt(source, redirected); } finally { applyingRedirectedDamage = false; }
                remaining -= redirected;
            }
        }
        return Math.max(0, remaining);
    }

    public static boolean hasCustody(ServerPlayer holder, UUID owner) {
        return data(holder).storedSouls().contains(owner) || holder.getInventory().items.stream().anyMatch(stack -> ClaimedSoulItem.owner(stack).filter(owner::equals).isPresent());
    }

    public static boolean hasSoul(ServerPlayer player) { return !data(player).soulClaimed(); }

    public static void removeSoul(ServerPlayer holder, UUID owner) {
        data(holder).storedSouls().removeIf(owner::equals);
        for (int i = 0; i < holder.getInventory().getContainerSize(); i++) if (ClaimedSoulItem.owner(holder.getInventory().getItem(i)).filter(owner::equals).isPresent()) holder.getInventory().setItem(i, ItemStack.EMPTY);
    }

    public static void forceReturnSoul(MinecraftServer server, UUID owner) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) removeSoul(player, owner);
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) data(player).setSoulClaimed(false);
    }

    public static boolean forceStealSoul(ServerPlayer holder, ServerPlayer owner) {
        if (holder == owner) return false;
        forceReturnSoul(holder.server, owner.getUUID());
        ItemStack soul = ClaimedSoulItem.create(DealmakerItems.CLAIMED_SOUL.get(), owner.getUUID(), owner.getName().getString());
        if (holder.getInventory().add(soul)) { data(owner).setSoulClaimed(true); return true; }
        if (data(holder).storedSouls().size() >= 27) return false;
        data(holder).storedSouls().add(owner.getUUID());
        data(owner).setSoulClaimed(true);
        return true;
    }

    public static String forceDealSoul(ServerPlayer holder, ServerPlayer owner) { return "Standalone contracts require the recipient to accept the signed book."; }
    public static String forceDealSoulAll(ServerPlayer holder) { return "Standalone contracts require recipients to accept signed books."; }

    public static boolean openSoulInventory(ServerPlayer holder, ServerPlayer owner) {
        if (!hasCustody(holder, owner.getUUID())) return false;
        holder.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> ChestMenu.threeRows(id, inventory, new SoulBoundInventoryContainer(holder, owner)), Component.literal(owner.getName().getString() + "'s Soulbound Inventory")));
        return true;
    }

    private static boolean execute(Deal deal, ServerPlayer maker, ServerPlayer acceptor, DealTrigger trigger) {
        for (DealClause clause : deal.clauses()) if (clause.trigger() == trigger) {
            ServerPlayer from = party(clause.from(), maker, acceptor);
            ServerPlayer to = party(clause.to(), maker, acceptor);
            if (clause.kind() == ClauseKind.TRANSFER_ITEM_AMOUNT || clause.kind() == ClauseKind.RECURRING_ITEM_PAYMENT) {
                Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(clause.assetId())).orElse(null);
                if (item == null || !moveItems(from, to, item, (int) clause.amount())) return false;
            } else if (clause.kind() == ClauseKind.TRANSFER_ALL_MATCHING_ITEMS) {
                Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(clause.assetId())).orElse(null);
                if (item == null || !moveItems(from, to, item, count(from, item))) return false;
            } else if (clause.kind() == ClauseKind.TRANSFER_INVENTORY_SLOT) {
                int slot = slot(clause.assetId(), from);
                if (slot < 0 || from.getInventory().getItem(slot).isEmpty() || !to.getInventory().add(from.getInventory().getItem(slot).copy())) return false;
                from.getInventory().setItem(slot, ItemStack.EMPTY);
            } else if (clause.kind() == ClauseKind.TRANSFER_ATTRIBUTE_AMOUNT || clause.kind() == ClauseKind.TRANSFER_ATTRIBUTE_PERCENT) {
                ResourceLocation id = ResourceLocation.tryParse(clause.assetId());
                Attribute attribute = id == null ? null : BuiltInRegistries.ATTRIBUTE.get(id);
                if (attribute == null) return false;
                AttributeInstance source = from.getAttribute(attribute);
                AttributeInstance target = to.getAttribute(attribute);
                if (source == null || target == null) return false;
                double value = clause.kind() == ClauseKind.TRANSFER_ATTRIBUTE_PERCENT ? source.getBaseValue() * clause.amount() / 100D : clause.amount();
                source.setBaseValue(source.getBaseValue() - value);
                target.setBaseValue(target.getBaseValue() + value);
            } else if (clause.kind() == ClauseKind.FORFEIT_SOUL) {
                if (!forceStealSoul(to, from)) return false;
            } else if (clause.kind() == ClauseKind.KILL_PLAYER) from.kill();
            else if (clause.kind() == ClauseKind.DEAL_DAMAGE_AMOUNT) from.hurt(from.damageSources().generic(), (float) clause.amount());
            else if (clause.kind() == ClauseKind.SET_ON_FIRE_SECONDS) from.setSecondsOnFire((int) clause.amount());
        }
        return true;
    }

    private static void executeMatching(Deal deal, ServerPlayer maker, ServerPlayer acceptor, DealTrigger trigger, DealConditionType type, ServerPlayer player) {
        for (DealClause clause : deal.clauses()) if (clause.trigger() == trigger && clause.condition().type() == type
                && party(clause.condition().party(), maker, acceptor) == player) { execute(deal, maker, acceptor, trigger); return; }
    }

    private static ServerPlayer party(Party party, ServerPlayer maker, ServerPlayer acceptor) { return party == Party.DEALMAKER ? maker : acceptor; }
    private static int count(ServerPlayer player, Item item) { return player.getInventory().items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static boolean moveItems(ServerPlayer from, ServerPlayer to, Item item, int amount) {
        if (amount < 1 || count(from, item) < amount) return false;
        int remaining = amount;
        for (ItemStack stack : from.getInventory().items) if (stack.is(item) && remaining > 0) { int moved = Math.min(remaining, stack.getCount()); ItemStack copy = stack.copyWithCount(moved); if (!to.getInventory().add(copy)) return false; stack.shrink(moved); remaining -= moved; }
        return remaining == 0;
    }
    private static int slot(String name, ServerPlayer player) {
        if (name.matches("HOTBAR_[1-9]")) return name.charAt(name.length() - 1) - '1';
        return switch (name) { case "MAIN_HAND" -> player.getInventory().selected; case "ARMOR_FEET" -> 36; case "ARMOR_LEGS" -> 37; case "ARMOR_CHEST" -> 38; case "ARMOR_HEAD" -> 39; case "OFFHAND", "OFF_HAND" -> 40; default -> -1; };
    }
    private static List<Deal> involved(ServerPlayer player) { List<Deal> result = new ArrayList<>(); for (ServerPlayer owner : player.server.getPlayerList().getPlayers()) for (Deal deal : data(owner).deals()) if (deal.dealmakerId().equals(player.getUUID()) || deal.acceptorId().equals(player.getUUID())) result.add(deal); return result; }
    private static Optional<Located> locate(MinecraftServer server, UUID id) { for (ServerPlayer owner : server.getPlayerList().getPlayers()) { List<Deal> deals = data(owner).deals(); for (int i = 0; i < deals.size(); i++) if (deals.get(i).id().equals(id)) return Optional.of(new Located(deals, i)); } return Optional.empty(); }
    private record Located(List<Deal> deals, int index) { Deal deal() { return deals.get(index); } void replace(Deal deal) { deals.set(index, deal); } }
}
