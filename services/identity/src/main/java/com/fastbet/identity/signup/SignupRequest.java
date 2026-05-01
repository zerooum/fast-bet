package com.fastbet.identity.signup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Public signup request payload.
 *
 * <p>Note: this DTO deliberately omits any {@code roles}/{@code role} field.
 * Combined with {@link JsonIgnoreProperties}, any client attempt to set a
 * role through the public endpoint is dropped before validation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SignupRequest {

    @NotBlank
    @Email
    @Size(max = 254)
    public String email;

    @NotBlank
    @Size(min = 12, max = 128)
    @Pattern(
            regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
            message = "must contain at least one letter and one digit")
    public String password;

    @Size(min = 1, max = 60)
    public String displayName;

    public SignupRequest() {
    }
}
