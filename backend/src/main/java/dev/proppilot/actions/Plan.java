package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;

/** A validated proposal: the normalized arguments, what they would change and how to say so in both languages. */
public record Plan(JsonNode args, JsonNode preview, String summaryEn, String summaryAr) {
}
