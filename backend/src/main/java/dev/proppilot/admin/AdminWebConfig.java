package dev.proppilot.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class AdminWebConfig implements WebMvcConfigurer {

    private final AdminTokenInterceptor adminToken;

    AdminWebConfig(AdminTokenInterceptor adminToken) {
        this.adminToken = adminToken;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminToken).addPathPatterns("/api/admin/**");
    }
}
