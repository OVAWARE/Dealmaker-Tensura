package com.github.ovaware.dealmaker.config;

import io.github.manasmods.manascore.config.api.Comment;
import io.github.manasmods.manascore.config.api.ManasConfig;

public final class DealmakerServerConfig extends ManasConfig {
    @Comment("LOCAL, GOOGLE_AI_STUDIO, or OPENROUTER.")
    public String aiProvider = "LOCAL";

    @Comment("Optional Google AI Studio key. Kept server-side; leave blank to use GOOGLE_AI_STUDIO_API_KEY or GEMINI_API_KEY.")
    public String googleAiStudioApiKey = "";

    @Comment("Optional OpenRouter key. Kept server-side; leave blank to use OPENROUTER_API_KEY.")
    public String openRouterApiKey = "";

    @Comment("Google AI Studio Gemini model used when aiProvider is GOOGLE_AI_STUDIO.")
    public String googleModel = "gemini-3.5-flash";

    @Comment("Ask Google to enforce responseSchema. Some Gemini models reject complex schemas; local validation always remains enabled.")
    public boolean googleStructuredOutput = false;

    @Comment("Log a truncated AI response when it cannot be decoded. API keys are never logged.")
    public boolean logMalformedAiResponse = true;

    @Comment("OpenRouter model slug used when aiProvider is OPENROUTER.")
    public String openRouterModel = "google/gemini-3.5-flash";

    @Comment("Hard timeout for a contract parsing API request.")
    public int requestTimeoutSeconds = 90;

    @Comment("HTTPS connection timeout for a contract parsing API request.")
    public int connectTimeoutSeconds = 10;

    @Comment("Maximum tokens the AI may use for its schema-constrained contract response.")
    public int maxOutputTokens = 512;

    @Comment("AI sampling temperature. Zero is deterministic and recommended for contracts.")
    public double temperature = 0.0;

    @Comment("Minimum delay between API-backed contract submissions by one player.")
    public int requestCooldownSeconds = 5;

    @Override
    public String getFileName() {
        return "tensura/dealmaker/server";
    }
}
