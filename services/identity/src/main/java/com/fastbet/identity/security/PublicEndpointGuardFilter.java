package com.fastbet.identity.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

/**
 * Defense-in-depth guard for public endpoints.
 *
 * <p>The Gateway is the supported entry point and already strips
 * {@code Authorization} / {@code X-User-*} on public routes
 * (see {@code gateway/src/routes/signup.rs}). This filter ensures Identity
 * itself rejects those headers if anyone reaches a public path directly,
 * so the trust boundary is not silently bypassed by misconfiguration.
 *
 * <p>The filter runs at {@code @PreMatching} priority so it fires before
 * resource matching — a developer cannot bypass it by changing {@code @Path}.
 */
@Provider
@PreMatching
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 100)
public class PublicEndpointGuardFilter implements ContainerRequestFilter {

    @Inject
    @ConfigProperty(name = "fastbet.identity.public-paths", defaultValue = "")
    String publicPathsCsv;

    @Inject
    @ConfigProperty(
            name = "fastbet.identity.public-paths.forbidden-headers",
            defaultValue = "Authorization,X-User-Id,X-User-Roles")
    String forbiddenHeadersCsv;

    private List<String> publicPaths;
    /** Lower-cased header name → canonical form (for the response body). */
    private Map<String, String> forbiddenHeaders;

    public PublicEndpointGuardFilter() {
        // CDI no-arg ctor; fields populated post-construct.
    }

    /** Test-only constructor. */
    PublicEndpointGuardFilter(String publicPathsCsv, String forbiddenHeadersCsv) {
        this.publicPathsCsv = publicPathsCsv;
        this.forbiddenHeadersCsv = forbiddenHeadersCsv;
        init();
    }

    @PostConstruct
    void init() {
        this.publicPaths = parseCsv(publicPathsCsv);
        this.forbiddenHeaders = parseHeaderSet(forbiddenHeadersCsv);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (publicPaths == null || publicPaths.isEmpty()
                || forbiddenHeaders == null || forbiddenHeaders.isEmpty()) {
            return;
        }
        UriInfo uri = ctx.getUriInfo();
        String rawPath = uri.getPath();
        String path = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        if (!matchesPublic(path)) {
            return;
        }

        MultivaluedMap<String, String> headers = ctx.getHeaders();
        for (String name : new LinkedHashSet<>(headers.keySet())) {
            String canonical = forbiddenHeaders.get(name.toLowerCase(Locale.ROOT));
            if (canonical != null) {
                ctx.abortWith(buildRejection(ctx, canonical));
                return;
            }
        }
    }

    private boolean matchesPublic(String path) {
        for (String prefix : publicPaths) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static Response buildRejection(ContainerRequestContext ctx, String canonicalHeader) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "forbidden_header");
        body.put("header", canonicalHeader);

        // Ensure the response carries an X-Request-Id even though the
        // RequestIdFilter has not yet run (we are @PreMatching).
        String requestId = ctx.getHeaderString(RequestIdFilter.HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        ctx.setProperty(RequestIdFilter.PROPERTY, requestId);

        return Response
                .status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .header(RequestIdFilter.HEADER, requestId)
                .entity(body)
                .build();
    }

    private static List<String> parseCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String entry : csv.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed.startsWith("/") ? trimmed : "/" + trimmed);
            }
        }
        return out;
    }

    private static Map<String, String> parseHeaderSet(String csv) {
        Map<String, String> out = new LinkedHashMap<>();
        if (csv == null) {
            return out;
        }
        for (String entry : csv.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                out.put(trimmed.toLowerCase(Locale.ROOT), trimmed);
            }
        }
        return out;
    }

    /** Test-only accessor for the parsed forbidden header set (lowercased keys). */
    Set<String> forbiddenHeaderKeys() {
        return forbiddenHeaders.keySet();
    }
}
