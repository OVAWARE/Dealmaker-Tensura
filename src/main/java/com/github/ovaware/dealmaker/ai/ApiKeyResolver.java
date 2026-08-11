package com.github.ovaware.dealmaker.ai;

import com.github.ovaware.dealmaker.config.DealmakerConfigs;

final class ApiKeyResolver {
    private ApiKeyResolver() {}

    static String google() {
        return resolve("GOOGLE_AI_STUDIO_API_KEY", "GEMINI_API_KEY", "dealmaker.googleApiKey",
                DealmakerConfigs.server().googleAiStudioApiKey);
    }

    static String openRouter() {
        return resolve("OPENROUTER_API_KEY", null, "dealmaker.openRouterApiKey",
                DealmakerConfigs.server().openRouterApiKey);
    }

    private static String resolve(String environment, String alternateEnvironment, String property, String configured) {
        String value = System.getenv(environment);
        if ((value == null || value.isBlank()) && alternateEnvironment != null) value = System.getenv(alternateEnvironment);
        if (value == null || value.isBlank()) value = System.getProperty(property);
        if (value == null || value.isBlank()) value = configured;
        return value == null ? "" : value.trim();
    }
}
