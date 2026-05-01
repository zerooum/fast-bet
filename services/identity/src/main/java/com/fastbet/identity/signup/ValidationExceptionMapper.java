package com.fastbet.identity.signup;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fastbet.identity.observability.SignupMetrics;

import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps Bean Validation failures from the signup endpoint (and any other
 * resource on this service) into the structured 400 envelope the spec
 * mandates: {@code {"error":"validation","fields":{"<field>":"<message>"}}}.
 */
@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    private final SignupMetrics metrics;

    @Context
    UriInfo uriInfo;

    @Inject
    public ValidationExceptionMapper(SignupMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (ConstraintViolation<?> v : exception.getConstraintViolations()) {
            fields.put(lastNode(v.getPropertyPath().toString()), v.getMessage());
        }
        // Only increment the signup metric when the failing path is /signup.
        // The mapper is service-wide; future routes must not pollute this counter.
        String path = uriInfo != null && uriInfo.getPath() != null ? uriInfo.getPath() : "";
        if (path.equals("signup") || path.startsWith("signup/")) {
            metrics.attempt("invalid");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation");
        body.put("fields", fields);
        return Response
                .status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }

    /**
     * Hibernate Validator reports paths like {@code signup.arg0.email}; the
     * client cares only about the leaf field name.
     */
    private static String lastNode(String path) {
        if (path == null || path.isEmpty()) {
            return "_";
        }
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }
}
