package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.SqlStatementCounter;
import dev.proppilot.domain.TenantRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TenantResolverIT extends PostgresIntegrationTest {

    @Autowired
    TenantResolver resolver;
    @Autowired
    TenantRepository tenants;
    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    void looksUpTheLeasesOfAllMatchingTenantsWithASingleQuery() {
        assertThat(tenants.searchByName("أحمد الحربي")).hasSize(1);
        assertThat(tenants.searchByName("Al-")).hasSizeGreaterThan(20);
        var statements = new SqlStatementCounter(entityManagerFactory);

        long oneMatch = statements.during(() -> resolver.resolve(Optional.of("أحمد الحربي"), Optional.empty()));
        long manyMatches = statements.during(() -> resolver.resolve(Optional.of("Al-"), Optional.empty()));

        assertThat(oneMatch).as("one query for the tenants, one for their leases").isEqualTo(2);
        assertThat(manyMatches).isEqualTo(oneMatch);
    }

    @Test
    void stillFindsTheOnlyActiveLeaseOfAUniqueMatch() {
        var resolution = resolver.resolve(Optional.of("أحمد الحربي"), Optional.empty());

        assertThat(resolution).isInstanceOfSatisfying(TenantResolver.Resolution.Found.class,
                found -> assertThat(found.lease().getTenant().getNameEn()).isEqualTo("Ahmed Al-Harbi"));
    }

    @Test
    void asksForClarificationWhenSeveralTenantsMatch() {
        var resolution = resolver.resolve(Optional.of("Ahmed"), Optional.empty());

        assertThat(resolution).isInstanceOfSatisfying(TenantResolver.Resolution.Failed.class,
                failed -> assertThat(failed.reason()).contains("tenants match 'Ahmed'").contains("(unit "));
    }
}
