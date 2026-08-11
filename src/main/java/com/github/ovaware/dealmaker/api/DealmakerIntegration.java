package com.github.ovaware.dealmaker.api;

import com.github.ovaware.dealmaker.deal.DealClause;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Optional integrations validate and execute only their explicitly registered contract assets. */
public interface DealmakerIntegration {
    boolean supports(DealClause clause);

    void validate(DealClause clause, List<String> errors);

    boolean execute(DealClause clause, ServerPlayer from, ServerPlayer to);

    /** Additional trusted instructions supplied to the remote contract parser. */
    default String aiInstructions() {
        return "";
    }

    /** Adds only the clause kinds an integration can safely execute. */
    default void extendAiSchema(com.google.gson.JsonObject schema) {
    }
}
