package com.github.ovaware.dealmaker.api;

import com.github.ovaware.dealmaker.deal.DealClause;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Optional integrations validate and execute only their explicitly registered contract assets. */
public interface DealmakerIntegration {
    boolean supports(DealClause clause);

    void validate(DealClause clause, List<String> errors);

    boolean execute(DealClause clause, ServerPlayer from, ServerPlayer to);
}
