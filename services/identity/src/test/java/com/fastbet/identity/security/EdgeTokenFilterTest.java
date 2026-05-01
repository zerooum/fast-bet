package com.fastbet.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

class EdgeTokenFilterTest {

    private static final String SECRET = "test-secret-value";

    private final EdgeTokenFilter filter = new EdgeTokenFilter(SECRET);

    // --- tokensMatch helper ---

    @Test
    void tokensMatchReturnsTrueForMatchingValue() {
        assertThat(EdgeTokenFilter.tokensMatch(SECRET, SECRET)).isTrue();
    }

    @Test
    void tokensMatchReturnsFalseForNullInbound() {
        assertThat(EdgeTokenFilter.tokensMatch(null, SECRET)).isFalse();
    }

    @Test
    void tokensMatchReturnsFalseForBlankInbound() {
        assertThat(EdgeTokenFilter.tokensMatch("   ", SECRET)).isFalse();
    }

    @Test
    void tokensMatchReturnsFalseForWrongValue() {
        assertThat(EdgeTokenFilter.tokensMatch("wrong-secret", SECRET)).isFalse();
    }

    // --- filter() — allowed / rejected ---

    @Test
    void validTokenIsAllowedThrough() {
        ContainerRequestContext ctx = mockCtx("/signup", Map.of("X-Edge-Token", SECRET));
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void missingTokenIsRejectedWith401() {
        Response response = invoke("/signup", Map.of());
        assertThat(response.getStatus()).isEqualTo(401);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body).containsEntry("error", "edge_unauthorized");
        assertThat(response.getHeaderString("X-Request-Id")).isNotBlank();
    }

    @Test
    void wrongTokenIsRejectedWith401() {
        Response response = invoke("/signup", Map.of("X-Edge-Token", "attacker-token"));
        assertThat(response.getStatus()).isEqualTo(401);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body).containsEntry("error", "edge_unauthorized");
    }

    // --- Exempt management paths ---

    @Test
    void healthReadyIsExempt() {
        ContainerRequestContext ctx = mockCtx("/q/health/ready", Map.of());
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void healthLiveIsExempt() {
        ContainerRequestContext ctx = mockCtx("/q/health/live", Map.of());
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void metricsIsExempt() {
        ContainerRequestContext ctx = mockCtx("/q/metrics", Map.of());
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    // --- Request-Id propagation ---

    @Test
    void existingRequestIdIsPropagatedToRejection() {
        String rid = "11111111-2222-3333-4444-555555555555";
        Response response = invoke("/signup", Map.of("X-Request-Id", rid));
        assertThat(response.getHeaderString("X-Request-Id")).isEqualTo(rid);
    }

    // --- Helpers ---

    private Response invoke(String path, Map<String, String> headers) {
        ContainerRequestContext ctx = mockCtx(path, headers);
        filter.filter(ctx);
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        return captor.getValue();
    }

    private static ContainerRequestContext mockCtx(String path, Map<String, String> headers) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        String relative = path.startsWith("/") ? path.substring(1) : path;
        when(uri.getPath()).thenReturn(relative);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost:8081" + path));
        when(ctx.getUriInfo()).thenReturn(uri);

        MultivaluedMap<String, String> hdrs = new MultivaluedHashMap<>();
        headers.forEach(hdrs::putSingle);
        when(ctx.getHeaders()).thenReturn(hdrs);
        when(ctx.getHeaderString(any())).thenAnswer(inv -> {
            String n = inv.getArgument(0);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(n)) {
                    return e.getValue();
                }
            }
            return null;
        });
        return ctx;
    }
}
