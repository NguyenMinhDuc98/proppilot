package dev.proppilot.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.config.AdminProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The application context must not come up with a weak admin token. */
class AdminStartupTest {

    @Configuration
    @EnableConfigurationProperties(AdminProperties.class)
    @Import(AdminTokenInterceptor.class)
    static class AdminBeans {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(AdminBeans.class);

    @Test
    void startsWithoutAnAdminToken() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startsWithAStrongToken() {
        runner.withPropertyValues("proppilot.admin.token=" + "k".repeat(32)).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsFastWithAClearMessageWhenTheTokenIsTooShort() {
        runner.withPropertyValues("proppilot.admin.token=hunter2").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause()
                .hasMessageContaining("ADMIN_TOKEN must be at least 24 characters")
                .hasMessageNotContaining("hunter2"));
    }
}
