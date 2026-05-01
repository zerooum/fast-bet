package com.fastbet.identity.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Structured INFO logger for signup attempts. NEVER logs raw email or
 * password — only a SHA-256 truncated hash of the normalized email.
 */
@ApplicationScoped
public class SignupLogger {

    private static final Logger LOG = Logger.getLogger("com.fastbet.identity.signup");

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public void attempt(String requestId, String normalizedEmail, String outcome) {
        String emailHash = hashEmail(normalizedEmail);
        LOG.infof("signup attempt request_id=%s email_hash=%s outcome=%s",
                requestId == null ? "-" : requestId,
                emailHash,
                outcome);
    }

    static String hashEmail(String email) {
        if (email == null) {
            return "-";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(email.getBytes(StandardCharsets.UTF_8));
            return toHex(digest, 6); // 6 bytes -> 12 hex chars
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE; treat as fatal misconfiguration.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes, int byteCount) {
        int n = Math.min(bytes.length, byteCount);
        char[] out = new char[n * 2];
        for (int i = 0; i < n; i++) {
            int b = bytes[i] & 0xff;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0f];
        }
        return new String(out);
    }
}
