package dev.proppilot.guardrail;

import dev.proppilot.config.GuardrailProperties;
import dev.proppilot.domain.TenantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GuardrailConfig {

    @Bean
    TenantNames tenantNames(TenantRepository tenants) {
        return new TenantNames(() -> tenants.allNames().stream()
                .map(name -> new TenantNames.Name(name.getNameEn(), name.getNameAr())).toList());
    }

    @Bean
    Guardrail guardrail(GuardrailProperties props, TenantNames tenantNames) {
        return new Guardrail(props.guardrailMode(), new GroundingChecker(tenantNames));
    }
}
