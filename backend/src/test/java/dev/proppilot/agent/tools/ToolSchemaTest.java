package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolSchemaTest {

    @Test
    void anArrayOfStringsStatesItsItemTypeAndItsLimit() {
        var schema = ToolSchema.object()
                .string("query", "What to look for")
                .stringArray("unit_codes", "Unit codes such as A-203", 10)
                .required("query")
                .build();

        var codes = schema.at("/properties/unit_codes");
        assertThat(codes.get("type").asText()).isEqualTo("array");
        assertThat(codes.get("items").get("type").asText()).isEqualTo("string");
        assertThat(codes.get("maxItems").asInt()).isEqualTo(10);
        assertThat(codes.get("description").asText()).isEqualTo("Unit codes such as A-203");
        assertThat(schema.get("required")).extracting(node -> node.asText()).containsExactly("query");
    }
}
