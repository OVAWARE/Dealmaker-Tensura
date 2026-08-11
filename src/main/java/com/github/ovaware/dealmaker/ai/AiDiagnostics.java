package com.github.ovaware.dealmaker.ai;

import com.github.ovaware.dealmaker.DealmakerMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Logs provider failures without ever serializing authorization headers, keys, or contract text. */
final class AiDiagnostics {
    private AiDiagnostics() {}

    static void httpFailure(String provider, String model, int status, String body) {
        DealmakerMod.LOGGER.warn("{} contract parsing failed: HTTP {} for model {}: {}",
                provider, status, model, apiMessage(body));
    }

    static void structuredFallback(String provider, String model, int status, String body) {
        DealmakerMod.LOGGER.info("{} rejected structured output for model {} (HTTP {}: {}); retrying JSON mode.",
                provider, model, status, apiMessage(body));
    }

    static void rejectedResponse(String provider, String model, String response, java.util.List<String> errors) {
        if (com.github.ovaware.dealmaker.config.DealmakerConfigs.server().logMalformedAiResponse) {
            DealmakerMod.LOGGER.warn("{} rejected contract program for model {}; errors: {}; full response: {}",
                    provider, model, String.join(" ", errors), response == null ? "<null>" : response);
        } else {
            DealmakerMod.LOGGER.warn("{} rejected contract program for model {}; errors: {}",
                    provider, model, String.join(" ", errors));
        }
    }

    static void unreadableResponse(String provider, String model, RuntimeException error, String response) {
        if (com.github.ovaware.dealmaker.config.DealmakerConfigs.server().logMalformedAiResponse) {
            DealmakerMod.LOGGER.warn("{} returned an unreadable response for model {} ({}); full response: {}",
                    provider, model, error.getClass().getSimpleName(), response == null ? "<null>" : response);
        } else {
            DealmakerMod.LOGGER.warn("{} contract parsing returned an unreadable response for model {} ({})",
                    provider, model, error.getClass().getSimpleName());
        }
    }

    static void malformedProgram(String provider, String model, String shape, String response) {
        if (com.github.ovaware.dealmaker.config.DealmakerConfigs.server().logMalformedAiResponse) {
            DealmakerMod.LOGGER.warn("{} returned a malformed contract program for model {}; response shape: {}; response: {}",
                    provider, model, shape, response == null ? "<null>" : response);
        } else {
            DealmakerMod.LOGGER.warn("{} returned a malformed contract program for model {}; response shape: {}",
                    provider, model, shape);
        }
    }

    static void requestFailure(String provider, String model, Throwable error) {
        Throwable cause = unwrap(error);
        String message = cause.getMessage();
        if (message == null || message.isBlank()) message = "no detail supplied";
        message = message.replaceAll("[\\r\\n\\t]+", " ");
        message = message.substring(0, Math.min(message.length(), 300));
        DealmakerMod.LOGGER.warn("{} contract parsing request failed for model {}: {}: {}",
                provider, model, cause.getClass().getSimpleName(), message);
    }

    private static String apiMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject error = root.getAsJsonObject("error");
            if (error != null) {
                JsonElement message = error.get("message");
                String detail = error.has("details") ? sanitize(error.get("details").toString()) : "";
                if (message != null && message.isJsonPrimitive()) {
                    String combined = sanitize(message.getAsString());
                    return detail.isBlank() ? combined : combined + " Details: " + detail;
                }
            }
            JsonElement message = root.get("message");
            if (message != null && message.isJsonPrimitive()) return sanitize(message.getAsString());
        } catch (RuntimeException ignored) {
        }
        return "no provider error message supplied";
    }

    private static String sanitize(String value) {
        value = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.substring(0, Math.min(value.length(), 500));
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

}
