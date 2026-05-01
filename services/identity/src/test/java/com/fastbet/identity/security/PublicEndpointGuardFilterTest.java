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

class PublicEndpointGuardFilterTest {

    private final PublicEndpointGuardFilter filter = new PublicEndpointGuardFilter(
            "/signup",
            "Authorization,X-User-Id,X-User-Roles");

    @Test
    void rejectsAuthorizationHeaderOnPublicPath() {
        Response response = invoke("/signup", Map.of("Authorization", "Bearer x"));
        assertThat(response.getStatus()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body).containsEntry("error", "forbidden_header");
        assertThat(body).containsEntry("header", "Authorization");
        assertThat(response.getHeaderString("X-Request-Id")).isNotBlank();
    }

    @Test
    void rejectsXUserIdHeader() {
        Response response = invoke("/signup", Map.of("X-User-Id", "00000000-0000-0000-0000-000000000000"));
        assertThat(response.getStatus()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body).containsEntry("header", "X-User-Id");
    }

    @Test
    void rejectsLowercaseXUserRolesHeaderWithCanonicalNameInBody() {
        Response response = invoke("/signup", Map.of("x-user-roles", "ADMIN"));
        assertThat(response.getStatus()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body).containsEntry("header", "X-User-Roles");
    }

    @Test
    void allowsRequestWithoutForbiddenHeaders() {
        ContainerRequestContext ctx = mockCtx("/signup", Map.of("Content-Type", "application/json"));
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void doesNotRejectOnNonPublicPathEvenWithForbiddenHeaders() {
        ContainerRequestContext ctx = mockCtx("/users/me", Map.of("Authorization", "Bearer x"));
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void existingRequestIdHeaderIsPropagatedToRejection() {
        Response response = invoke("/signup", Map.of(
                "Authorization", "Bearer x",
                "X-Request-Id", "11111111-2222-3333-4444-555555555555"));
        assertThat(response.getHeaderString("X-Request-Id"))
                .isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    @Test
    void emptyPublicPathListDisablesGuard() {
        PublicEndpointGuardFilter disabled = new PublicEndpointGuardFilter("", "Authorization");
        ContainerRequestContext ctx = mockCtx("/signup", Map.of("Authorization", "Bearer x"));
        disabled.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

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
        // UriInfo.getPath() returns path WITHOUT the leading slash.
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
