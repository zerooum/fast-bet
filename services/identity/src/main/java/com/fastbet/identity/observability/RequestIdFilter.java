package com.fastbet.identity.observability;

import java.util.UUID;

import org.jboss.logging.MDC;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Propagates the {@code X-Request-Id} header into the SLF4J/JBoss MDC for
 * the duration of the request. Generates a UUID when the header is absent so
 * downstream logs always have a correlation id.
 */
@Provider
public class RequestIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";
    public static final String PROPERTY = "fastbet.request_id";

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String requestId = requestContext.getHeaderString(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        requestContext.setProperty(PROPERTY, requestId);
        MDC.put(MDC_KEY, requestId);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        Object id = requestContext.getProperty(PROPERTY);
        if (id != null) {
            responseContext.getHeaders().putSingle(HEADER, id.toString());
        }
        MDC.remove(MDC_KEY);
    }
}
