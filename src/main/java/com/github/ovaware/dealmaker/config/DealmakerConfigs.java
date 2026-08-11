package com.github.ovaware.dealmaker.config;

import com.github.ovaware.dealmaker.DealmakerMod;
import io.github.manasmods.manascore.config.ConfigRegistry;
import io.github.manasmods.tensura.config.ReincarnationConfig;

import java.util.LinkedHashSet;
import java.util.List;

public final class DealmakerConfigs {
    private static final String DEVIL_BARGEN_SKILL_ID = "dealmaker:devil_bargen";

    private DealmakerConfigs() {}

    public static void init() {
        ConfigRegistry.registerConfig(new DealmakerServerConfig());
    }

    public static DealmakerServerConfig server() {
        return ConfigRegistry.getConfig(DealmakerServerConfig.class);
    }

    public static void reload() {
        ConfigRegistry.loadConfigSyncData();
        DealmakerMod.LOGGER.info("Reloaded Devil Bargen server configuration.");
    }

    /**
     * Adds Devil Bargen to Tensura's reincarnation unique-skill roll pool via config.
     * Tensura rebuilds its in-memory pool from this list on the next launch.
     */
    public static void addToReincarnationPool() {
        ReincarnationConfig reincarnation = ConfigRegistry.getConfig(ReincarnationConfig.class);
        if (!addSkillId(reincarnation.Skills.startingSkills, DEVIL_BARGEN_SKILL_ID)) {
            return;
        }
        ConfigRegistry.saveAllConfigs();
        DealmakerMod.LOGGER.info("Added {} to the reincarnation unique-skill roll pool.", DEVIL_BARGEN_SKILL_ID);
    }

    private static boolean addSkillId(List<String> skills, String skillId) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(skills);
        boolean changed = merged.add(skillId);
        if (changed) {
            skills.clear();
            skills.addAll(merged);
        }
        return changed;
    }
}
