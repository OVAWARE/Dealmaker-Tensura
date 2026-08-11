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

public final class OpenRouterDealParser implements AsyncDealParser {
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private final HttpClient client;

    public OpenRouterDealParser(HttpClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<ParseResult> parse(String contractText) {
        String key = ApiKeyResolver.openRouter();
        if (key.isBlank()) return CompletableFuture.completedFuture(ParseResult.rejected(
                "OpenRouter is selected but OPENROUTER_API_KEY is not set."));
        String model = DealmakerConfigs.server().openRouterModel.trim();
        if (!model.matches("[A-Za-z0-9~._:/-]{1,160}")) return CompletableFuture.completedFuture(ParseResult.rejected("Invalid OpenRouter model name."));

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", Math.max(0.0, Math.min(2.0, DealmakerConfigs.server().temperature)));
        body.addProperty("max_tokens", Math.max(64, Math.min(2048, DealmakerConfigs.server().maxOutputTokens)));
        body.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        messages.add(message("system", AiContractProtocol.instructions()));
        messages.add(message("user", "<contract>\n" + contractText + "\n</contract>"));
        body.add("messages", messages);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "devil_bargen_contract");
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", AiContractProtocol.schema());
        format.add("json_schema", jsonSchema);
        body.add("response_format", format);
        JsonObject provider = new JsonObject();
        provider.addProperty("require_parameters", true);
        body.add("provider", provider);

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(Math.max(5, Math.min(180, DealmakerConfigs.server().requestTimeoutSeconds))))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .header("X-OpenRouter-Title", "Dealmaker")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> decodeResponse(response, model))
                .exceptionally(error -> {
                    AiDiagnostics.requestFailure("OpenRouter", model, error);
                    return ParseResult.rejected("OpenRouter request failed or timed out; see the server log for details.");
                });
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static ParseResult decodeResponse(HttpResponse<String> response, String model) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            AiDiagnostics.httpFailure("OpenRouter", model, response.statusCode(), response.body());
            return ParseResult.rejected("OpenRouter returned HTTP " + response.statusCode() + ".");
        }
        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String text = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            ParseResult result = AiContractProtocol.decode(text);
            if (!result.accepted() && result.errors().contains("The AI returned malformed contract data.")) {
                AiDiagnostics.malformedProgram("OpenRouter", model, AiContractProtocol.responseShape(text), text);
            }
            return result;
        } catch (RuntimeException exception) {
            AiDiagnostics.unreadableResponse("OpenRouter", model, exception);
            return ParseResult.rejected("OpenRouter returned an unreadable response.");
        }
    }
}
