package com.AccountReceivableManagement.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Verification settings for the UMS-issued JWTs accepted by the
 * signature-management endpoints ({@code app.security.jwt.*}).
 *
 * Nothing here has a default key, algorithm or claim name: the signing key,
 * algorithm and roles-claim of the UMS token contract are not defined in
 * this repository, so they must be supplied by configuration. Until they
 * are, the endpoints fail closed (see {@link FinanceExecutiveAuthorizer}).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.security.jwt")
public class JwtAuthProperties {

    public enum SecretEncoding { UTF8, BASE64 }

    /** HS256/HS384/HS512 (shared secret) or RS256/RS384/RS512 (RSA public key). The token's alg must match exactly. */
    private String algorithm;

    /** Shared secret for HS* algorithms. Environment only - never committed or logged. */
    private String secret;

    private SecretEncoding secretEncoding = SecretEncoding.UTF8;

    /** PEM (X.509 SubjectPublicKeyInfo) RSA public key for RS* algorithms; literal "\n" sequences are accepted. */
    private String publicKeyPem;

    /** Expected {@code iss}; not checked when blank. */
    private String issuer;

    /** Required {@code aud} value; not checked when blank. */
    private String audience;

    /** Name of the claim carrying the user's role(s); a dotted path (a.b) reaches nested claims. Required. */
    private String rolesClaim;

    /** Role allowed to manage the signature. */
    private String requiredRole = "FINANCE_EXECUTIVE";

    /** Tolerance for exp / nbf clock drift. */
    private long clockSkewSeconds = 30;

    public boolean isConfigured() {
        if (isBlank(algorithm) || isBlank(rolesClaim) || isBlank(requiredRole)) {
            return false;
        }
        return algorithm.toUpperCase().startsWith("HS") ? !isBlank(secret)
                : algorithm.toUpperCase().startsWith("RS") && !isBlank(publicKeyPem);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
