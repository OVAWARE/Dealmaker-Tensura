package com.github.ovaware.dealmaker.registry;

import com.github.ovaware.dealmaker.DealmakerMod;
import com.github.ovaware.dealmaker.skill.DevilBargenSkill;
import dev.architectury.registry.registries.RegistrySupplier;
import io.github.manasmods.manascore.skill.api.ManasSkill;
import io.github.manasmods.manascore.skill.impl.SkillRegistry;

public final class DealmakerSkills {
    public static final RegistrySupplier<ManasSkill> DEVIL_BARGEN = SkillRegistry.SKILLS.register(
            DealmakerMod.id("devil_bargen"), DevilBargenSkill::new);

    private DealmakerSkills() {}
    public static void init() {}
}
