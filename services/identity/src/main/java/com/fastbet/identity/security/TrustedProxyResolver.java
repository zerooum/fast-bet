package com.fastbet.identity.security;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Resolves the client IP for rate-limiting and logging.
 *
 * <p>Trust rules:
 * <ul>
 *   <li>If the TCP peer's address falls within the configured
 *       {@code IDENTITY_TRUSTED_PROXIES} CIDR allow-list, the leftmost entry
 *       of {@code X-Forwarded-For} is used.</li>
 *   <li>Otherwise, {@code X-Forwarded-For} is ignored and the TCP peer
 *       address is used as the client IP.</li>
 * </ul>
 *
 * <p>The allow-list defaults to empty (trust nobody), so a misconfigured
 * deployment cannot be tricked into honoring a spoofed XFF.
 */
@ApplicationScoped
public class TrustedProxyResolver {

    private static final Logger LOG = Logger.getLogger(TrustedProxyResolver.class);

    private final List<Cidr> trusted;

    @Inject
    public TrustedProxyResolver(
            @ConfigProperty(name = "fastbet.identity.trusted-proxies", defaultValue = "")
            Optional<String> trustedProxiesCsv) {
        this.trusted = parseCidrList(trustedProxiesCsv.orElse(""));
    }

    /** Test-only constructor. */
    public TrustedProxyResolver(List<String> cidrs) {
        this.trusted = parseCidrList(String.join(",", cidrs));
    }

    /**
     * Resolve the effective client IP.
     *
     * @param peerAddress    the TCP peer's IP literal (never {@code null})
     * @param xForwardedFor  the raw {@code X-Forwarded-For} header value, may be {@code null}
     * @return the IP to use for rate-limit keys; never {@code null}
     */
    public String resolveClientIp(String peerAddress, String xForwardedFor) {
        if (peerAddress == null || peerAddress.isBlank()) {
            return "unknown";
        }
        if (!isTrusted(peerAddress) || xForwardedFor == null || xForwardedFor.isBlank()) {
            return peerAddress;
        }
        // Take the leftmost non-empty entry as the originating client.
        for (String part : xForwardedFor.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return peerAddress;
    }

    private boolean isTrusted(String peerAddress) {
        if (trusted.isEmpty()) {
            return false;
        }
        Optional<InetAddress> addr = parseAddress(peerAddress);
        if (addr.isEmpty()) {
            return false;
        }
        for (Cidr cidr : trusted) {
            if (cidr.contains(addr.get())) {
                return true;
            }
        }
        return false;
    }

    private static List<Cidr> parseCidrList(String csv) {
        List<Cidr> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String entry : csv.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                out.add(Cidr.parse(trimmed));
            } catch (IllegalArgumentException e) {
                LOG.warnf("ignoring malformed trusted-proxy entry '%s': %s", trimmed, e.getMessage());
            }
        }
        return out;
    }

    private static Optional<InetAddress> parseAddress(String literal) {
        try {
            return Optional.of(InetAddress.getByName(literal));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    /** Minimal CIDR matcher supporting both IPv4 and IPv6. */
    private static final class Cidr {
        private final BigInteger network;
        private final BigInteger mask;
        private final int byteLen;

        private Cidr(BigInteger network, BigInteger mask, int byteLen) {
            this.network = network;
            this.mask = mask;
            this.byteLen = byteLen;
        }

        static Cidr parse(String spec) {
            int slash = spec.indexOf('/');
            String addr;
            int prefix;
            if (slash < 0) {
                addr = spec;
                prefix = -1;
            } else {
                addr = spec.substring(0, slash);
                try {
                    prefix = Integer.parseInt(spec.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("bad prefix in '" + spec + "'");
                }
            }
            InetAddress base;
            try {
                base = InetAddress.getByName(addr);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("bad address in '" + spec + "'");
            }
            int byteLen = base.getAddress().length;
            int maxPrefix = byteLen * 8;
            if (prefix < 0) {
                prefix = maxPrefix;
            }
            if (prefix > maxPrefix) {
                throw new IllegalArgumentException("prefix out of range in '" + spec + "'");
            }
            BigInteger mask = prefix == 0
                    ? BigInteger.ZERO
                    : BigInteger.ONE.shiftLeft(maxPrefix).subtract(BigInteger.ONE)
                            .shiftLeft(maxPrefix - prefix)
                            .and(BigInteger.ONE.shiftLeft(maxPrefix).subtract(BigInteger.ONE));
            BigInteger network = new BigInteger(1, base.getAddress()).and(mask);
            return new Cidr(network, mask, byteLen);
        }

        boolean contains(InetAddress addr) {
            byte[] bytes = addr.getAddress();
            if (bytes.length != byteLen) {
                return false;
            }
            BigInteger value = new BigInteger(1, bytes);
            return value.and(mask).equals(network);
        }
    }
}
