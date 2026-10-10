package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** What approving an action now, with the given edits, would do. Nothing was changed to produce it. */
public record PreviewView(UUID id, ActionKind kind, JsonNode args, JsonNode preview, String summaryEn, String summaryAr) {
}
