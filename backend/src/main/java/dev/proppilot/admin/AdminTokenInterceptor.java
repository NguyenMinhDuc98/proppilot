package dev.proppilot.admin;

import dev.proppilot.config.AdminProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards /api/admin/**. Without a configured token the endpoints answer 404 as if they did not exist; with one, the
 * request must carry it in the X-Admin-Token header (never in the URL, which ends up in logs and browser history).
 */
@Component
class AdminTokenInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Admin-Token";
    static final int MIN_TOKEN_LENGTH = 24;

    private final AdminProperties props;

    AdminTokenInterceptor(AdminProperties props) {
        if (props.enabled() && props.token().length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("ADMIN_TOKEN must be at least " + MIN_TOKEN_LENGTH
                    + " characters when it is set. Generate one with: openssl rand -hex 24");
        }
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        if (!props.enabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }
        if (!tokenMatches(request.getHeader(HEADER))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }

    private boolean tokenMatches(String supplied) {
        return supplied != null && MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), props.token().getBytes(StandardCharsets.UTF_8));
    }
}
