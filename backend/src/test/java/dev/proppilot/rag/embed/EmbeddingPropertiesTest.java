package dev.proppilot.rag.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proppilot.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;

class EmbeddingPropertiesTest {

    private static EmbeddingProperties props(String provider, String key, String model, int dimensions) {
        return new EmbeddingProperties(provider, key, model, dimensions, "http://localhost/v1/embeddings", 20, 3, 1000);
    }

    @Test
    void theProviderIsNoneOrVoyageInAnyCase() {
        assertThat(props("none", "", "voyage-4-lite", 1024).provider()).isEqualTo("none");
        assertThat(props(" Voyage ", "key", "voyage-4-lite", 1024).provider()).isEqualTo("voyage");
    }

    @Test
    void noProviderSettingIsNone() {
        assertThat(props(null, "", "voyage-4-lite", 1024).provider()).isEqualTo("none");
    }

    @Test
    void aBlankProviderIsNoneSoAnUnfilledDashboardValueCannotStopTheBackend() {
        assertThat(props("", "", "voyage-4-lite", 1024).provider()).isEqualTo("none");
        assertThat(props("   ", "", "voyage-4-lite", 1024).provider()).isEqualTo("none");
    }

    @Test
    void anyOtherProviderIsRefusedWithTheNameOfTheSetting() {
        for (var provider : new String[] {"openai", "voyage-ai", "none,voyage"}) {
            assertThatThrownBy(() -> props(provider, "", "voyage-4-lite", 1024))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EMBEDDING_PROVIDER must be none or voyage");
        }
    }

    @Test
    void theModelMustFitTheColumnItIsStoredIn() {
        assertThatThrownBy(() -> props("none", "", " ", 1024)).hasMessageContaining("EMBEDDING_MODEL");
        assertThatThrownBy(() -> props("none", "", "m".repeat(65), 1024)).hasMessageContaining("EMBEDDING_MODEL");
        assertThat(props("none", "", "m".repeat(64), 1024).model()).hasSize(64);
    }

    @Test
    void theDimensionsMustBePositive() {
        assertThatThrownBy(() -> props("none", "", "voyage-4-lite", 0)).hasMessageContaining("EMBEDDING_DIMENSIONS");
    }

    @Test
    void aMissingKeyIsAnEmptyKey() {
        assertThat(props("none", null, "voyage-4-lite", 1024).apiKey()).isEmpty();
    }

    @Test
    void printingThePropertiesNeverShowsTheKey() {
        assertThat(props("voyage", "pa-secret-key", "voyage-4-lite", 1024).toString())
                .doesNotContain("pa-secret-key")
                .contains("voyage-4-lite");
    }
}
