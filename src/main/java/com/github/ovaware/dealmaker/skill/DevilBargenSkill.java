package com.github.ovaware.dealmaker.skill;

import com.github.ovaware.dealmaker.DealmakerMod;
import com.github.ovaware.dealmaker.deal.DealService;
import com.github.ovaware.dealmaker.deal.ViewerBook;
import io.github.manasmods.manascore.skill.api.ManasSkillInstance;
import io.github.manasmods.tensura.ability.TensuraSkill;
import io.github.manasmods.tensura.ability.skill.Skill;
import io.github.manasmods.tensura.registry.attribute.TensuraAttributes;
import io.github.manasmods.tensura.registry.sound.TensuraSoundEvents;
import io.github.manasmods.tensura.storage.TensuraStorages;
import io.github.manasmods.tensura.storage.player.ITensuraPlayer;
import io.github.manasmods.tensura.util.AttributeHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.stream.Collectors;

public final class DevilBargenSkill extends Skill {
    public static final int CONTRACT = 0;
    public static final int APPRAISAL = 1;
    public static final int LEDGER = 2;
    public static final int SOUL_STORAGE = 3;
    public static final int DEALS = 4;

    /** Far above Analyst/Great Sage so Devil Bargen can read nearly any target. */
    public static final int APPRAISAL_LEVEL = 64;
    public static final int APPRAISAL_RADIUS = 48;
    private static final ResourceLocation APPRAISAL_MODIFIER = DealmakerMod.id("analysis");

    public DevilBargenSkill() {
        super(SkillType.UNIQUE);
    }

    @Override
    public ResourceLocation getSkillIcon() {
        return ResourceLocation.fromNamespaceAndPath("tensura", "textures/item/element_core_water.png");
    }

    @Override
    public boolean checkAcquiringRequirement(Player entity, double newEP) {
        return false;
    }

    @Override
    public double getAcquiringMagiculeCost(ManasSkillInstance instance) {
        return 0.0;
    }

    @Override
    public int getAcquirementMastery(LivingEntity entity) {
        return 0;
    }

    @Override
    public int getModes(ManasSkillInstance instance) {
        return 5;
    }

    @Override
    public int nextMode(LivingEntity entity, ManasSkillInstance instance, int mode, boolean reverse) {
        if (reverse) {
            return mode == 0 ? DEALS : mode - 1;
        }
        return mode == DEALS ? CONTRACT : mode + 1;
    }

    @Override
    public String getModeId(ManasSkillInstance instance, int mode) {
        return switch (mode) {
            case CONTRACT -> "devil_bargen.contract";
            case APPRAISAL -> "devil_bargen.appraisal";
            case LEDGER -> "devil_bargen.ledger";
            case SOUL_STORAGE -> "devil_bargen.soul_storage";
            case DEALS -> "devil_bargen.deals";
            default -> super.getModeId(instance, mode);
        };
    }

    @Override
    public double getMagiculeCost(LivingEntity entity, ManasSkillInstance instance, int mode) {
        return 0.0;
    }

    @Override
    public void onPressed(ManasSkillInstance instance, LivingEntity entity, int keyNumber, int mode) {
        if (!(entity instanceof ServerPlayer player) || entity.level().isClientSide()) return;
        switch (mode) {
            case CONTRACT -> contract(player);
            case APPRAISAL -> appraisal(instance, player);
            case LEDGER -> DealService.ledger(player);
            case SOUL_STORAGE -> DealService.openSoulStorage(player);
            case DEALS -> DealService.openDealsUi(player);
            default -> { }
        }
    }

    @Override
    public boolean onDeath(ManasSkillInstance instance, LivingEntity owner, DamageSource source) {
        AttributeInstance level = owner.getAttribute(TensuraAttributes.ANALYSIS_LEVEL);
        if (level != null && !level.hasModifier(APPRAISAL_MODIFIER)) {
            instance.getOrCreateTag().putBoolean("Activated", false);
        }
        return true;
    }

    @Override
    public void onRespawn(ManasSkillInstance instance, ServerPlayer player, boolean conqueredEnd) {
        if (instance.getOrCreateTag().getBoolean("Activated")
                && player.getAttribute(TensuraAttributes.ANALYSIS_LEVEL) != null) {
            AttributeHelper.addAnalysisAttributes(player, APPRAISAL_LEVEL, APPRAISAL_RADIUS, APPRAISAL_MODIFIER);
        }
    }

    @Override
    public void onForgetSkill(ManasSkillInstance instance, LivingEntity entity) {
        super.onForgetSkill(instance, entity);
        if (entity instanceof ServerPlayer player) {
            AttributeHelper.removeAnalysisAttributes(player, true, true, false, APPRAISAL_MODIFIER);
        }
    }

    private static void appraisal(ManasSkillInstance instance, ServerPlayer player) {
        if (player.isShiftKeyDown()) {
            ITensuraPlayer data = TensuraStorages.getPlayerDataFrom(player);
            switch (data.getAnalysisMode()) {
                case 1 -> {
                    data.setAnalysisMode(2);
                    player.displayClientMessage(Component.translatable("tensura.skill.analytical.analyzing_mode.block")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)), true);
                }
                case 2 -> {
                    data.setAnalysisMode(0);
                    player.displayClientMessage(Component.translatable("tensura.skill.analytical.analyzing_mode.both")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)), true);
                }
                default -> {
                    data.setAnalysisMode(1);
                    player.displayClientMessage(Component.translatable("tensura.skill.analytical.analyzing_mode.entity")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)), true);
                }
            }
            player.playNotifySound((SoundEvent) TensuraSoundEvents.GENERIC_CAST.get(), TensuraSkill.ABILITY_SOUND, 1.0f, 1.0f);
            data.markDirty();
            return;
        }

        CompoundTag tag = instance.getOrCreateTag();
        AttributeInstance level = player.getAttribute(TensuraAttributes.ANALYSIS_LEVEL);
        if (level != null && level.hasModifier(APPRAISAL_MODIFIER)) {
            AttributeHelper.removeAnalysisAttributes(player, true, true, false, APPRAISAL_MODIFIER);
            tag.putBoolean("Activated", false);
            player.displayClientMessage(Component.literal("Analytical Appraisal deactivated.")
                    .withStyle(ChatFormatting.GRAY), true);
        } else {
            AttributeHelper.addAnalysisAttributes(player, APPRAISAL_LEVEL, APPRAISAL_RADIUS, APPRAISAL_MODIFIER);
            tag.putBoolean("Activated", true);
            player.displayClientMessage(Component.literal("Analytical Appraisal activated (level " + APPRAISAL_LEVEL + ").")
                    .withStyle(ChatFormatting.DARK_AQUA), true);
        }
        player.playNotifySound((SoundEvent) TensuraSoundEvents.GENERIC_CAST.get(), TensuraSkill.ABILITY_SOUND, 1.0f, 1.0f);
    }

    private static void contract(ServerPlayer player) {
        submitContract(player);
    }

    public static void submitContract(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (ViewerBook.isViewer(held)) {
            player.sendSystemMessage(Component.literal("That book is a Devil Bargen viewer, not a contract.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        WrittenBookContent book = held.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (book == null) {
            ItemStack writable = new ItemStack(Items.WRITABLE_BOOK);
            if (!player.getInventory().add(writable)) player.drop(writable, false);
            player.sendSystemMessage(Component.literal("Write the bargain, sign it, hold it, then use Contract again.")
                    .withStyle(ChatFormatting.GOLD));
            return;
        }

        String text = book.pages().stream().map(Filterable::raw).map(Component::getString)
                .collect(Collectors.joining("\n")).trim();
        DealService.proposeBookAsync(player, held, book, text);
    }
}
