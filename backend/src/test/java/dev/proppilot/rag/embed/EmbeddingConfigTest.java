package dev.proppilot.rag.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.config.EmbeddingProperties;
import java.util.List;
import java.util.Map;
import org.assertj.core.util.Throwables;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

/** Which client the settings select, and what refuses to start, without a database. */
class EmbeddingConfigTest {

    @Configuration
    @EnableConfigurationProperties(EmbeddingProperties.class)
    @Import(EmbeddingConfig.class)
    static class Clients {
        @Bean
        ObjectMapper json() {
            return new ObjectMapper();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Clients.class)
            .withPropertyValues("proppilot.embedding.model=voyage-4-lite", "proppilot.embedding.dimensions=1024",
                    "proppilot.embedding.api-url=http://127.0.0.1:9/v1/embeddings",
                    "proppilot.embedding.timeout-seconds=20", "proppilot.embedding.max-retries=3",
                    "proppilot.embedding.retry-base-delay-ms=1000");

    /** Sets the provider as it is, spaces included: {@code withPropertyValues} would trim them. */
    private ApplicationContextRunner withProvider(String provider) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("provider", Map.of("proppilot.embedding.provider", provider))));
    }

    @Test
    void noProviderSettingMeansNoEmbeddings() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(EmbeddingClient.class).hasSingleBean(NoEmbeddingClient.class);
            assertThat(context.getBean(EmbeddingClient.class).enabled()).isFalse();
        });
    }

    @Test
    void noneMeansNoEmbeddings() {
        runner.withPropertyValues("proppilot.embedding.provider=none").run(context -> {
            assertThat(context).hasSingleBean(NoEmbeddingClient.class);
            assertThat(context.getBean(EmbeddingClient.class).model()).isEqualTo("voyage-4-lite");
            assertThat(context.getBean(EmbeddingClient.class).dimensions()).isEqualTo(1024);
        });
    }

    @Test
    void voyageWithAKeyMeansVoyage() {
        runner.withPropertyValues("proppilot.embedding.provider=voyage", "proppilot.embedding.api-key=pa-test")
                .run(context -> {
                    assertThat(context).hasSingleBean(VoyageEmbeddingClient.class);
                    assertThat(context.getBean(EmbeddingClient.class).enabled()).isTrue();
                });
    }

    @Test
    void theProviderIsChosenByTheSettingsEvenWhenItIsPaddedOrInUpperCase() {
        for (var provider : List.of(" voyage", "voyage ", " Voyage\t", "VOYAGE")) {
            withProvider(provider).withPropertyValues("proppilot.embedding.api-key=pa-test").run(context -> {
                assertThat(context).as("[%s]", provider).hasSingleBean(EmbeddingClient.class);
                assertThat(context.getBean(EmbeddingClient.class)).as("[%s]", provider)
                        .isInstanceOf(VoyageEmbeddingClient.class);
            });
        }
        for (var provider : List.of(" none", "none ", "NONE")) {
            withProvider(provider).run(context -> {
                assertThat(context).as("[%s]", provider).hasSingleBean(EmbeddingClient.class);
                assertThat(context.getBean(EmbeddingClient.class)).as("[%s]", provider)
                        .isInstanceOf(NoEmbeddingClient.class);
            });
        }
    }

    @Test
    void voyageWithoutAKeyFailsStartupAndSaysWhichSettingIsMissing() {
        runner.withPropertyValues("proppilot.embedding.provider=voyage", "proppilot.embedding.api-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("VOYAGE_API_KEY is required when EMBEDDING_PROVIDER=voyage");
                });
    }

    @Test
    void aKeyThatAHeaderCannotCarryFailsStartupWithoutQuotingItAnywhere() {
        var key = "pa-\u2013-secret";

        runner.withPropertyValues("proppilot.embedding.provider=voyage", "proppilot.embedding.api-key=" + key)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("VOYAGE_API_KEY");
                    assertThat(Throwables.getStackTrace(context.getStartupFailure())).doesNotContain(key)
                            .doesNotContain("secret");
                });
    }

    @Test
    void aKeyPastedWithItsQuotesFailsStartupWithoutQuotingIt() {
        runner.withPropertyValues("proppilot.embedding.provider=voyage",
                        "proppilot.embedding.api-key=\"pa-secret\"")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("VOYAGE_API_KEY")
                            .hasMessageContaining("quote");
                    assertThat(Throwables.getStackTrace(context.getStartupFailure())).doesNotContain("secret");
                });
    }

    @Test
    void anUnknownProviderFailsStartupAndSaysWhatIsAllowed() {
        runner.withPropertyValues("proppilot.embedding.provider=cohere").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("EMBEDDING_PROVIDER must be none or voyage");
        });
    }

    @Test
    void theNoneClientRefusesToEmbed() {
        runner.run(context -> {
            var client = context.getBean(EmbeddingClient.class);
            assertThatThrownBy(() -> client.embedQuery("question"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> client.embedDocuments(List.of("a")))
                    .isInstanceOf(IllegalStateException.class);
        });
    }
}
