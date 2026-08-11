package com.github.ovaware.dealmaker.ai;

import com.github.ovaware.dealmaker.config.DealmakerConfigs;
import com.github.ovaware.dealmaker.deal.ParseResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class GoogleAiStudioDealParser implements AsyncDealParser {
    private final HttpClient client;

    public GoogleAiStudioDealParser(HttpClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<ParseResult> parse(String contractText) {
        String key = ApiKeyResolver.google();
        if (key.isBlank()) return CompletableFuture.completedFuture(ParseResult.rejected(
                "Google AI Studio is selected but GOOGLE_AI_STUDIO_API_KEY (or GEMINI_API_KEY) is not set."));
        String model = DealmakerConfigs.server().googleModel.trim();
        if (model.startsWith("models/")) model = model.substring("models/".length());
        if (!model.matches("[A-Za-z0-9._-]{1,100}")) return CompletableFuture.completedFuture(ParseResult.rejected("Invalid Google model name."));
        return send(model, key, contractText, DealmakerConfigs.server().googleStructuredOutput);
    }

    private CompletableFuture<ParseResult> send(String model, String key, String contractText, boolean includeSchema) {
        JsonObject body = requestBody(contractText, includeSchema);
        HttpRequest request = request(model, key, body);
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    if (includeSchema && response.statusCode() == 400) {
                        AiDiagnostics.structuredFallback("Google AI Studio", model, response.statusCode(), response.body());
                        return client.sendAsync(request(model, key, requestBody(contractText, false)),
                                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                                .thenApply(fallback -> decodeResponse(fallback, model));
                    }
                    return CompletableFuture.completedFuture(decodeResponse(response, model));
                })
                .exceptionally(error -> {
                    AiDiagnostics.requestFailure("Google AI Studio", model, error);
                    return ParseResult.rejected("Google AI Studio request failed or timed out; see the server log for details.");
                });
    }

    private static JsonObject requestBody(String contractText, boolean includeSchema) {
        JsonObject body = new JsonObject();
        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        JsonObject part = new JsonObject();
        part.addProperty("text", AiContractProtocol.instructions() + "\n<contract>\n" + contractText + "\n</contract>");
        JsonArray parts = new JsonArray();
        parts.add(part);
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);
        body.add("contents", contents);
        JsonObject generation = new JsonObject();
        generation.addProperty("temperature", Math.max(0.0, Math.min(2.0, DealmakerConfigs.server().temperature)));
        generation.addProperty("maxOutputTokens", Math.max(64, Math.min(2048, DealmakerConfigs.server().maxOutputTokens)));
        generation.addProperty("responseMimeType", "application/json");
        if (includeSchema) generation.add("responseSchema", AiContractProtocol.googleResponseSchema());
        body.add("generationConfig", generation);
        return body;
    }

    private static HttpRequest request(String model, String key, JsonObject body) {
        return HttpRequest.newBuilder(URI.create(
                        "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                .timeout(timeout())
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private static ParseResult decodeResponse(HttpResponse<String> response, String model) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            AiDiagnostics.httpFailure("Google AI Studio", model, response.statusCode(), response.body());
            return ParseResult.rejected("Google AI Studio returned HTTP " + response.statusCode() + ".");
        }
        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray parts = root.getAsJsonArray("candidates").get(0).getAsJsonObject()
                    .getAsJsonObject("content").getAsJsonArray("parts");
            String text = selectJsonText(parts);
            ParseResult result = AiContractProtocol.decode(text);
            if (!result.accepted()) {
                AiDiagnostics.rejectedResponse("Google AI Studio", model, response.body(), result.errors());
            }
            return result;
        } catch (RuntimeException exception) {
            AiDiagnostics.unreadableResponse("Google AI Studio", model, exception, response.body());
            return ParseResult.rejected("Google AI Studio returned an unreadable response.");
        }
    }

    private static Duration timeout() {
        return Duration.ofSeconds(Math.max(5, Math.min(180, DealmakerConfigs.server().requestTimeoutSeconds)));
    }

    private static String selectJsonText(JsonArray parts) {
        String lastText = "";
        for (int i = parts.size() - 1; i >= 0; i--) {
            JsonObject part = parts.get(i).getAsJsonObject();
            if (!part.has("text") || !part.get("text").isJsonPrimitive()) continue;
            String text = part.get("text").getAsString();
            if (lastText.isEmpty()) lastText = text;
            String extracted = AiContractProtocol.extractJsonObject(text);
            try {
                if (JsonParser.parseString(extracted).isJsonObject()) return extracted;
            } catch (RuntimeException ignored) {
            }
        }
        return lastText;
    }
}
