package com.github.ovaware.dealmaker.config;

public final class DealmakerServerConfig {
    public String aiProvider = "LOCAL";

    public String googleAiStudioApiKey = "";

    public String openRouterApiKey = "";

    public String googleModel = "gemini-3.5-flash";

    public boolean googleStructuredOutput = false;

    public boolean logMalformedAiResponse = true;

    public String openRouterModel = "google/gemini-3.5-flash";

    public int requestTimeoutSeconds = 90;

    public int connectTimeoutSeconds = 10;

    public int maxOutputTokens = 512;

    public double temperature = 0.0;

    public int requestCooldownSeconds = 5;

}
