package dev.proppilot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RagPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(RagProperties.class)
    static class RagConfig {
    }

    /** The application's own application.yml, read the way the application reads it. */
    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(RagConfig.class);

    @Test
    void aNegativeCountIsRefusedWithTheNameOfTheSetting() {
        assertThatThrownBy(() -> new RagProperties(-1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RAG_DOCUMENT_COUNT");
    }

    @Test
    void noDocumentsAtAllIsAllowed() {
        assertThat(new RagProperties(0, true).documentCount()).isZero();
    }

    @Test
    void theDefaultsAre30DocumentsAndSeedingOnStartup() {
        application.run(context -> {
            var properties = context.getBean(RagProperties.class);
            assertThat(properties.documentCount()).isEqualTo(30);
            assertThat(properties.seedOnStartup()).isTrue();
        });
    }

    @Test
    void theEnvironmentVariablesChooseTheCountAndTheStartupSwitch() {
        application.withSystemProperties("RAG_DOCUMENT_COUNT=12", "RAG_SEED_ON_STARTUP=false").run(context -> {
            var properties = context.getBean(RagProperties.class);
            assertThat(properties.documentCount()).isEqualTo(12);
            assertThat(properties.seedOnStartup()).isFalse();
        });
    }

    @Test
    void aNegativeCountInTheEnvironmentStopsTheApplicationFromStarting() {
        application.withSystemProperties("RAG_DOCUMENT_COUNT=-3").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("RAG_DOCUMENT_COUNT"));
    }
}
