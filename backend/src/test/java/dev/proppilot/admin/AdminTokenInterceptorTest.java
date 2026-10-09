package dev.proppilot.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proppilot.config.AdminProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminTokenInterceptorTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/runs");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private boolean allowed(String configuredToken) throws Exception {
        return new AdminTokenInterceptor(new AdminProperties(configuredToken)).preHandle(request, response, new Object());
    }

    @Test
    void letsARequestWithTheRightTokenThrough() throws Exception {
        request.addHeader("X-Admin-Token", TOKEN);

        assertThat(allowed(TOKEN)).isTrue();
    }

    @Test
    void hidesTheEndpointsWhenNoTokenIsConfigured() throws Exception {
        request.addHeader("X-Admin-Token", TOKEN);

        assertThat(allowed(null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void treatsABlankTokenAsNotConfigured() throws Exception {
        request.addHeader("X-Admin-Token", "");

        assertThat(allowed("   ")).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void refusesARequestWithoutTheHeader() throws Exception {
        assertThat(allowed(TOKEN)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void refusesAWrongToken() throws Exception {
        request.addHeader("X-Admin-Token", TOKEN.substring(0, TOKEN.length() - 1) + "0");

        assertThat(allowed(TOKEN)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void refusesAPrefixOfTheToken() throws Exception {
        request.addHeader("X-Admin-Token", TOKEN.substring(0, 24));

        assertThat(allowed(TOKEN)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void neverAcceptsTheTokenInTheQueryString() throws Exception {
        request.addParameter("X-Admin-Token", TOKEN);
        request.addParameter("token", TOKEN);

        assertThat(allowed(TOKEN)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void letsACorsPreflightThroughBecauseBrowsersSendNoHeadersWithIt() throws Exception {
        var preflight = new MockHttpServletRequest("OPTIONS", "/api/admin/runs");
        preflight.addHeader("Origin", "http://localhost:5173");
        preflight.addHeader("Access-Control-Request-Method", "GET");
        preflight.addHeader("Access-Control-Request-Headers", "x-admin-token");

        var interceptor = new AdminTokenInterceptor(new AdminProperties(TOKEN));

        assertThat(interceptor.preHandle(preflight, response, new Object())).isTrue();
    }

    @Test
    void refusesToStartWithATokenShorterThan24Characters() {
        assertThatThrownBy(() -> new AdminTokenInterceptor(new AdminProperties("abc123")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 24 characters")
                .hasMessageNotContaining("abc123");
    }

    @Test
    void acceptsATokenOfExactly24Characters() {
        assertThat(new AdminTokenInterceptor(new AdminProperties("a".repeat(24)))).isNotNull();
    }
}
