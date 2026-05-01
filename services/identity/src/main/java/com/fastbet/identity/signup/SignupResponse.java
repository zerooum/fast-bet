package com.fastbet.identity.signup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class SignupResponse {

    public UUID id;
    public String email;
    public String displayName;
    public List<String> roles;
    public Instant createdAt;

    public SignupResponse() {
    }

    public SignupResponse(UUID id, String email, String displayName, List<String> roles, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.roles = roles;
        this.createdAt = createdAt;
    }
}
