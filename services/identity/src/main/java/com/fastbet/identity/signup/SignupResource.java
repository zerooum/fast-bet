package com.fastbet.identity.signup;

import java.util.Arrays;
import java.util.List;
import com.fastbet.identity.domain.UserAccount;
import com.fastbet.identity.observability.RequestIdFilter;
import com.fastbet.identity.observability.SignupLogger;
import com.fastbet.identity.observability.SignupMetrics;
import com.fastbet.identity.security.SignupRateLimiter;
import com.fastbet.identity.security.TrustedProxyResolver;

import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/signup")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SignupResource {

    private static final String XFF_HEADER = "X-Forwarded-For";

    private final SignupUseCase useCase;
    private final SignupLogger signupLogger;
    private final SignupMetrics metrics;
    private final SignupRateLimiter rateLimiter;
    private final TrustedProxyResolver proxyResolver;

    @Inject
    public SignupResource(SignupUseCase useCase,
                          SignupLogger signupLogger,
                          SignupMetrics metrics,
                          SignupRateLimiter rateLimiter,
                          TrustedProxyResolver proxyResolver) {
        this.useCase = useCase;
        this.signupLogger = signupLogger;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
        this.proxyResolver = proxyResolver;
    }

    @POST
    public Response signup(@Valid SignupRequest request,
                           @Context ContainerRequestContext ctx,
                           @Context HttpHeaders httpHeaders,
                           @Context RoutingContext routing) {
        String requestId = requestIdOf(ctx);

        // Rate limit BEFORE touching the body, before hashing — so a flood
        // can't burn CPU on Argon2 and so even malformed payloads count
        // against the budget.
        String clientIp = resolveClientIp(httpHeaders, routing);
        SignupRateLimiter.Decision decision = rateLimiter.check(clientIp);
        if (!decision.allowed) {
            signupLogger.attempt(requestId, null, "rate_limited");
            metrics.attempt("rate_limited");
            return Response
                    .status(429)
                    .header("Retry-After", Long.toString(Math.max(decision.retryAfterSeconds, 1L)))
                    .entity(java.util.Map.of("error", "rate_limited"))
                    .build();
        }

        String normalizedEmail = SignupUseCase.normalizeEmail(request.email);

        try {
            UserAccount created = useCase.signup(request.email, request.password, request.displayName);
            signupLogger.attempt(requestId, normalizedEmail, "ok");

            List<String> roles = created.getRoles() == null
                    ? List.of()
                    : Arrays.asList(created.getRoles());
            SignupResponse body = new SignupResponse(
                    created.getId(),
                    created.getEmail(),
                    created.getDisplayName(),
                    roles,
                    created.getCreatedAt());
            return Response
                    .status(Response.Status.CREATED)
                    .header("Location", "/users/" + created.getId())
                    .entity(body)
                    .build();
        } catch (EmailAlreadyTakenException e) {
            signupLogger.attempt(requestId, normalizedEmail, "conflict");
            return Response
                    .status(Response.Status.CONFLICT)
                    .entity(java.util.Map.of("error", "email_taken"))
                    .build();
        }
    }

    private String resolveClientIp(HttpHeaders httpHeaders, RoutingContext routing) {
        String xff = httpHeaders == null ? null : httpHeaders.getHeaderString(XFF_HEADER);
        String peer = "unknown";
        if (routing != null && routing.request() != null && routing.request().remoteAddress() != null) {
            peer = routing.request().remoteAddress().host();
        }
        return proxyResolver.resolveClientIp(peer, xff);
    }

    private static String requestIdOf(ContainerRequestContext ctx) {
        Object v = ctx == null ? null : ctx.getProperty(RequestIdFilter.PROPERTY);
        return v == null ? null : v.toString();
    }
}
