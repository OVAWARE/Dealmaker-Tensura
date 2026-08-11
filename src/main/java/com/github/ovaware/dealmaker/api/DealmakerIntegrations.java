package com.github.ovaware.dealmaker.api;

import com.github.ovaware.dealmaker.deal.DealClause;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Server-side extension registry for optional Dealmaker addons. */
public final class DealmakerIntegrations {
    private static final List<DealmakerIntegration> INTEGRATIONS = new CopyOnWriteArrayList<>();

    private DealmakerIntegrations() {}

    public static void register(DealmakerIntegration integration) {
        INTEGRATIONS.add(integration);
    }

    public static boolean supports(DealClause clause) {
        return INTEGRATIONS.stream().anyMatch(integration -> integration.supports(clause));
    }

    public static void validate(DealClause clause, List<String> errors) {
        INTEGRATIONS.stream().filter(integration -> integration.supports(clause))
                .forEach(integration -> integration.validate(clause, errors));
    }

    public static boolean execute(DealClause clause, ServerPlayer from, ServerPlayer to) {
        for (DealmakerIntegration integration : INTEGRATIONS) {
            if (integration.supports(clause)) return integration.execute(clause, from, to);
        }
        return false;
    }
}
