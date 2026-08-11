package com.github.ovaware.dealmaker.command;

import com.github.ovaware.dealmaker.deal.DealService;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.manasmods.tensura.storage.TensuraStorages;
import io.github.manasmods.tensura.storage.Alignment;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DealmakerCommands {
    private static final ConcurrentHashMap<String, Long> SOUL_ACTION_COOLDOWNS = new ConcurrentHashMap<>();
    private DealmakerCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("devilbargen")
                .then(Commands.literal("accept")
                        .then(Commands.argument("deal", StringArgumentType.word())
                                .executes(context -> reply(context.getSource().getPlayerOrException(),
                                        DealService.accept(context.getSource().getPlayerOrException(), parseUuid(context, "deal"))))))
                .then(Commands.literal("sever")
                        .then(Commands.argument("deal", StringArgumentType.word())
                                .executes(context -> reply(context.getSource().getPlayerOrException(),
                                        DealService.sever(context.getSource().getPlayerOrException(), parseUuid(context, "deal"))))))
                .then(Commands.literal("deals").executes(context -> {
                    DealService.showDeals(context.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("debug")
                        .then(Commands.argument("deal", StringArgumentType.word()).executes(context -> {
                            DealService.debugDeal(context.getSource().getPlayerOrException(), parseUuid(context, "deal"));
                            return 1;
                        })))
                .then(Commands.literal("ledger")
                        .then(Commands.literal("create")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .executes(context -> reply(context.getSource().getPlayerOrException(),
                                                DealService.createLedgerBook(context.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(context, "index"))))))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .executes(context -> reply(context.getSource().getPlayerOrException(),
                                                DealService.deleteLedgerBook(context.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(context, "index")))))))
                .then(Commands.literal("soul")
                        .then(Commands.literal("forcedeal")
                                .then(Commands.literal("all")
                                        .executes(context -> forceDeal(context.getSource().getPlayerOrException(), null)))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> forceDeal(context.getSource().getPlayerOrException(),
                                                EntityArgument.getPlayer(context, "target")))))
                        .then(Commands.literal("damage")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> soulDamage(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "target")))))
                        .then(Commands.literal("take_ep")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(1.0, 1.0E15))
                                                .executes(context -> takeEp(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "target"),
                                                        DoubleArgumentType.getDouble(context, "amount"))))))
                        .then(Commands.literal("summon")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> summon(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "target")))))
                        .then(Commands.literal("inventory")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> inventory(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "target")))))
                        .then(Commands.literal("name")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(context -> rename(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "target"),
                                                        StringArgumentType.getString(context, "name"))))))
                        .then(Commands.literal("alignment")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("alignment", StringArgumentType.word())
                                                .executes(context -> alignment(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "target"),
                                                        StringArgumentType.getString(context, "alignment"))))))));

        event.getDispatcher().register(Commands.literal("devilhost")
                .then(Commands.literal("admin").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("steal")
                                .then(Commands.argument("holder", EntityArgument.player())
                                        .then(Commands.argument("soul_owner", EntityArgument.player())
                                                .executes(context -> adminSteal(context.getSource().getPlayerOrException(),
                                                        EntityArgument.getPlayer(context, "holder"),
                                                        EntityArgument.getPlayer(context, "soul_owner"))))))
                        .then(Commands.literal("return")
                                .then(Commands.argument("soul_owner", EntityArgument.player())
                                        .executes(context -> adminReturn(context.getSource().getPlayerOrException(),
                                                EntityArgument.getPlayer(context, "soul_owner")))))));
    }

    private static UUID parseUuid(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context, String name)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try {
            return UUID.fromString(StringArgumentType.getString(context, name));
        } catch (IllegalArgumentException exception) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Invalid deal id."))
                    .create();
        }
    }

    private static int forceDeal(ServerPlayer holder, ServerPlayer target) {
        String action = target == null ? "forcedeal_all" : "forcedeal";
        if (target != null) {
            if (!custody(holder, target, action)) return 0;
            return reply(holder, DealService.forceDealSoul(holder, target));
        }
        // Custody is rechecked per owned soul inside DealService; only rate-limit the bulk action.
        String key = holder.getUUID() + "|all|" + action;
        long now = holder.serverLevel().getGameTime();
        Long previous = SOUL_ACTION_COOLDOWNS.put(key, now);
        if (previous != null && now - previous < 10L) {
            holder.sendSystemMessage(Component.literal("That soul action is cooling down.").withStyle(ChatFormatting.RED));
            return 0;
        }
        return reply(holder, DealService.forceDealSoulAll(holder));
    }

    private static int soulDamage(ServerPlayer holder, ServerPlayer target) {
        if (!custody(holder, target, "damage")) return 0;
        target.hurt(target.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
        if (target.isAlive()) target.kill();
        return reply(holder, "Soul authority invoked against " + target.getName().getString() + ".");
    }

    private static int adminSteal(ServerPlayer administrator, ServerPlayer holder, ServerPlayer soulOwner) {
        if (!DealService.forceStealSoul(holder, soulOwner)) return reply(administrator, "Could not forcefully steal that soul.");
        return reply(administrator, "Forcefully assigned " + soulOwner.getName().getString() + "'s soul to "
                + holder.getName().getString() + ".");
    }

    private static int adminReturn(ServerPlayer administrator, ServerPlayer soulOwner) {
        DealService.forceReturnSoul(administrator.server, soulOwner.getUUID());
        return reply(administrator, "Forcefully returned " + soulOwner.getName().getString() + "'s soul.");
    }

    private static int takeEp(ServerPlayer holder, ServerPlayer target, double requested) {
        if (!custody(holder, target, "take_ep")) return 0;
        var from = TensuraStorages.getExistenceFrom(target);
        var to = TensuraStorages.getExistenceFrom(holder);
        double amount = Math.min(requested, Math.max(0.0, from.getEP()));
        from.setEP(from.getEP() - amount);
        to.setEP(to.getEP() + amount);
        from.markDirty();
        to.markDirty();
        return reply(holder, "Took " + Math.round(amount) + " EP from " + target.getName().getString() + ".");
    }

    private static int summon(ServerPlayer holder, ServerPlayer target) {
        if (!custody(holder, target, "summon")) return 0;
        target.teleportTo(holder.serverLevel(), holder.getX(), holder.getY(), holder.getZ(),
                holder.getYRot(), holder.getXRot());
        return reply(holder, "Summoned " + target.getName().getString() + ".");
    }

    private static int inventory(ServerPlayer holder, ServerPlayer target) {
        if (!custody(holder, target, "inventory")) return 0;
        return DealService.openSoulInventory(holder, target) ? 1 : 0;
    }

    private static int rename(ServerPlayer holder, ServerPlayer target, String name) {
        if (!custody(holder, target, "name")) return 0;
        String cleaned = name.trim();
        if (cleaned.isEmpty() || cleaned.length() > 48) return reply(holder, "A soulbound name must be 1 to 48 characters.");
        var existence = TensuraStorages.getExistenceFrom(target);
        existence.setName(cleaned);
        existence.markDirty();
        return reply(holder, "Renamed " + target.getName().getString() + " to " + cleaned + ".");
    }

    private static int alignment(ServerPlayer holder, ServerPlayer target, String requested) {
        if (!custody(holder, target, "alignment")) return 0;
        Alignment alignment;
        try {
            alignment = Alignment.valueOf(requested.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return reply(holder, "Unknown alignment. Use default, majin, holy, or chaos.");
        }
        var existence = TensuraStorages.getExistenceFrom(target);
        existence.setAlignment(alignment);
        existence.markDirty();
        return reply(holder, "Set " + target.getName().getString() + "'s alignment to " + alignment.getSerializedName() + ".");
    }

    private static boolean custody(ServerPlayer holder, ServerPlayer target, String action) {
        if (holder == target || !DealService.hasCustody(holder, target.getUUID())) {
            com.github.ovaware.dealmaker.DealmakerMod.LOGGER.warn("Soul authority denied: holder={}, owner={}, action={}, reason=no_custody",
                    holder.getUUID(), target.getUUID(), action);
            holder.sendSystemMessage(Component.literal("You do not currently possess that player's soul.").withStyle(ChatFormatting.RED));
            return false;
        }
        String key = holder.getUUID() + "|" + target.getUUID() + "|" + action;
        long now = holder.serverLevel().getGameTime();
        Long previous = SOUL_ACTION_COOLDOWNS.put(key, now);
        if (previous != null && now - previous < 10L) {
            holder.sendSystemMessage(Component.literal("That soul action is cooling down.").withStyle(ChatFormatting.RED));
            return false;
        }
        com.github.ovaware.dealmaker.DealmakerMod.LOGGER.info("Soul authority authorized: holder={}, owner={}, action={}",
                holder.getUUID(), target.getUUID(), action);
        return true;
    }

    private static int reply(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
        return 1;
    }
}
