package com.github.ovaware.dealmaker.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public final class DealmakerConfigs {
    private static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.ConfigValue<String> AI_PROVIDER;
    private static final ForgeConfigSpec.ConfigValue<String> GOOGLE_KEY;
    private static final ForgeConfigSpec.ConfigValue<String> OPENROUTER_KEY;
    private static final ForgeConfigSpec.ConfigValue<String> GOOGLE_MODEL;
    private static final ForgeConfigSpec.BooleanValue GOOGLE_STRUCTURED_OUTPUT;
    private static final ForgeConfigSpec.BooleanValue LOG_MALFORMED;
    private static final ForgeConfigSpec.ConfigValue<String> OPENROUTER_MODEL;
    private static final ForgeConfigSpec.IntValue REQUEST_TIMEOUT;
    private static final ForgeConfigSpec.IntValue CONNECT_TIMEOUT;
    private static final ForgeConfigSpec.IntValue MAX_OUTPUT_TOKENS;
    private static final ForgeConfigSpec.DoubleValue TEMPERATURE;
    private static final ForgeConfigSpec.IntValue REQUEST_COOLDOWN;
    private static DealmakerServerConfig server = new DealmakerServerConfig();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        AI_PROVIDER = builder.define("aiProvider", "LOCAL");
        GOOGLE_KEY = builder.define("googleAiStudioApiKey", "");
        OPENROUTER_KEY = builder.define("openRouterApiKey", "");
        GOOGLE_MODEL = builder.define("googleModel", "gemini-3.5-flash");
        GOOGLE_STRUCTURED_OUTPUT = builder.define("googleStructuredOutput", false);
        LOG_MALFORMED = builder.define("logMalformedAiResponse", true);
        OPENROUTER_MODEL = builder.define("openRouterModel", "google/gemini-3.5-flash");
        REQUEST_TIMEOUT = builder.defineInRange("requestTimeoutSeconds", 90, 1, 300);
        CONNECT_TIMEOUT = builder.defineInRange("connectTimeoutSeconds", 10, 1, 120);
        MAX_OUTPUT_TOKENS = builder.defineInRange("maxOutputTokens", 512, 1, 8192);
        TEMPERATURE = builder.defineInRange("temperature", 0.0D, 0.0D, 2.0D);
        REQUEST_COOLDOWN = builder.defineInRange("requestCooldownSeconds", 5, 1, 60);
        SPEC = builder.build();
    }

    private DealmakerConfigs() {}

    public static void init() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC, "dealmaker-server.toml");
        reload();
    }

    public static DealmakerServerConfig server() {
        return server;
    }

    public static void reload() {
        DealmakerServerConfig loaded = new DealmakerServerConfig();
        loaded.aiProvider = AI_PROVIDER.get();
        loaded.googleAiStudioApiKey = GOOGLE_KEY.get();
        loaded.openRouterApiKey = OPENROUTER_KEY.get();
        loaded.googleModel = GOOGLE_MODEL.get();
        loaded.googleStructuredOutput = GOOGLE_STRUCTURED_OUTPUT.get();
        loaded.logMalformedAiResponse = LOG_MALFORMED.get();
        loaded.openRouterModel = OPENROUTER_MODEL.get();
        loaded.requestTimeoutSeconds = REQUEST_TIMEOUT.get();
        loaded.connectTimeoutSeconds = CONNECT_TIMEOUT.get();
        loaded.maxOutputTokens = MAX_OUTPUT_TOKENS.get();
        loaded.temperature = TEMPERATURE.get();
        loaded.requestCooldownSeconds = REQUEST_COOLDOWN.get();
        server = loaded;
    }
}
