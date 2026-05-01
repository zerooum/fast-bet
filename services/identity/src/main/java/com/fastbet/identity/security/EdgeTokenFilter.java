package com.fastbet.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fastbet.identity.observability.RequestIdFilter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Validates the internal {@code X-Edge-Token} header on business API requests.
 *
 * <p>Only the Gateway is the supported public entry point. This filter ensures
 * that even if a business service port is accidentally exposed, a caller without
 * the shared edge secret cannot reach business logic.
 *
 * <p>Filter ordering with {@link PublicEndpointGuardFilter}:
 * <ol>
 *   <li>{@code PublicEndpointGuardFilter} (priority {@code AUTHENTICATION - 100 = 900})
 *       runs first — rejects forbidden auth headers on public paths with {@code 400}.
 *   <li>{@code EdgeTokenFilter} (priority {@code AUTHENTICATION = 1000}) runs next —
 *       rejects requests without a matching edge token with {@code 401}.
 * </ol>
 * This ordering preserves the existing {@code 400 forbidden_header} contract for
 * direct callers that send {@code Authorization} or {@code X-User-*}.
 * {@code X-Edge-Token} is intentionally NOT in the forbidden-header list.
 *
 * <p>Quarkus management endpoints ({@code /q/health/*} and {@code /q/metrics})
 * are exempt so liveness/readiness probes do not require the edge secret.
 *
 * <p>Token comparison uses {@link MessageDigest#isEqual} to prevent
 * timing-side-channel attacks.
 *
 * <p>Startup fails if {@code fastbet.identity.edge-token} ({@code IDENTITY_EDGE_TOKEN})
 * is missing or blank so misconfigured deployments are detected early.
 */
@Provider
@PreMatching
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
public class EdgeTokenFilter implements ContainerRequestFilter {

    static final String HEADER = "X-Edge-Token";

    @Inject
    @ConfigProperty(name = "fastbet.identity.edge-token", defaultValue = "")
    String configuredToken;

    public EdgeTokenFilter() {
        // CDI no-arg ctor; configuredToken populated via injection.
    }

    /** Test-only constructor: skips CDI injection and startup validation. */
    EdgeTokenFilter(String token) {
        this.configuredToken = token;
    }

    @PostConstruct
    void validateConfig() {
        if (configuredToken == null || configuredToken.isBlank()) {
            throw new IllegalStateException(
                    "fastbet.identity.edge-token (IDENTITY_EDGE_TOKEN) must be set to a"
                            + " non-blank value before the service can accept traffic");
        }
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String rawPath = ctx.getUriInfo().getPath();
        String path = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        if (isExempt(path)) {
            return;
        }

        String inbound = ctx.getHeaderString(HEADER);
        if (!tokensMatch(inbound, configuredToken)) {
            ctx.abortWith(buildUnauthorized(ctx));
        }
    }

    /** Returns true for Quarkus management paths that MUST NOT require the edge token. */
    private static boolean isExempt(String path) {
        return path.startsWith("/q/health") || path.equals("/q/metrics");
    }

    /**
     * Constant-time comparison to prevent timing side-channels.
     * Returns false immediately when the inbound token is null or blank
     * so callers without the header are rejected without leaking information
     * about the configured secret length.
     */
    static boolean tokensMatch(String inbound, String expected) {
        if (inbound == null || inbound.isBlank()) {
            return false;
        }
        byte[] a = inbound.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static Response buildUnauthorized(ContainerRequestContext ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "edge_unauthorized");

        String requestId = ctx.getHeaderString(RequestIdFilter.HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        ctx.setProperty(RequestIdFilter.PROPERTY, requestId);

        return Response
                .status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .header(RequestIdFilter.HEADER, requestId)
                .entity(body)
                .build();
    }
}
