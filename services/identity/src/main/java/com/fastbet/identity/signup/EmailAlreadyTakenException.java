package com.fastbet.identity.signup;

/**
 * Thrown by {@link SignupUseCase} when the normalized email is already in
 * use. Mapped to {@code 409 Conflict} by the resource layer.
 */
public class EmailAlreadyTakenException extends RuntimeException {

    public EmailAlreadyTakenException(String email) {
        super("email already taken: " + email);
    }
}
