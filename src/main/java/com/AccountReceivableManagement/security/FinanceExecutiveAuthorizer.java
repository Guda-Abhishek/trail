package com.AccountReceivableManagement.security;

import com.AccountReceivableManagement.security.AuthExceptions.AccessForbiddenException;
import com.AccountReceivableManagement.security.AuthExceptions.AuthenticationRequiredException;
import com.AccountReceivableManagement.security.AuthExceptions.AuthorizationNotConfiguredException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cryptographically verifies a bearer JWT and requires the configured role
 * (FINANCE_EXECUTIVE). The token is never merely decoded: the signature is
 * checked with the configured key, the header algorithm must equal the
 * configured one (so {@code alg=none} and algorithm-confusion tokens are
 * rejected), and {@code exp} is mandatory. The token and key are never logged.
 * With no verification settings configured every call fails closed.
 */
@Slf4j
@Component
public class FinanceExecutiveAuthorizer {

    private final JwtAuthProperties properties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public FinanceExecutiveAuthorizer(JwtAuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    FinanceExecutiveAuthorizer(JwtAuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** @throws AuthenticationRequiredException 401, AccessForbiddenException 403, AuthorizationNotConfiguredException 503 */
    public void requireFinanceExecutive(String authorizationHeader) {
        JWTClaimsSet claims = verify(authorizationHeader);
        Set<String> roles = rolesOf(claims);
        if (!roles.contains(normalize(properties.getRequiredRole()))) {
            throw new AccessForbiddenException("The " + properties.getRequiredRole()
                    + " role is required to manage the authorized signature.");
        }
    }

    /** Any user with a cryptographically valid, unexpired token (no role requirement); used for read access. */
    public void requireAuthenticated(String authorizationHeader) {
        verify(authorizationHeader);
    }

    private JWTClaimsSet verify(String authorizationHeader) {
        if (!properties.isConfigured()) {
            log.error("[SignatureAuth] app.security.jwt.* is not configured; refusing the request.");
            throw new AuthorizationNotConfiguredException(
                    "Signature management is unavailable: JWT verification is not configured "
                            + "(app.security.jwt.algorithm, key and roles-claim).");
        }
        String token = extractBearer(authorizationHeader);
        JWSVerifier verifier = buildVerifier();

        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.parse(properties.getAlgorithm().toUpperCase(Locale.ROOT))
                    .equals(jwt.getHeader().getAlgorithm())) {
                throw new AuthenticationRequiredException("The token's signing algorithm is not accepted.");
            }
            if (!jwt.verify(verifier)) {
                throw new AuthenticationRequiredException("The token signature is invalid.");
            }
            claims = jwt.getJWTClaimsSet();
        } catch (AuthenticationRequiredException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AuthenticationRequiredException("The token is invalid.");
        }

        validateClaims(claims);
        return claims;
    }

    private void validateClaims(JWTClaimsSet claims) {
        Date now = Date.from(clock.instant());
        long skewMs = properties.getClockSkewSeconds() * 1000;
        Date exp = claims.getExpirationTime();
        if (exp == null) {
            throw new AuthenticationRequiredException("The token has no expiry.");
        }
        if (now.getTime() > exp.getTime() + skewMs) {
            throw new AuthenticationRequiredException("The token has expired.");
        }
        Date nbf = claims.getNotBeforeTime();
        if (nbf != null && now.getTime() + skewMs < nbf.getTime()) {
            throw new AuthenticationRequiredException("The token is not yet valid.");
        }
        String issuer = properties.getIssuer();
        if (issuer != null && !issuer.isBlank() && !issuer.equals(claims.getIssuer())) {
            throw new AuthenticationRequiredException("The token issuer is not accepted.");
        }
        String audience = properties.getAudience();
        if (audience != null && !audience.isBlank()
                && (claims.getAudience() == null || !claims.getAudience().contains(audience))) {
            throw new AuthenticationRequiredException("The token audience is not accepted.");
        }
    }

    private Set<String> rolesOf(JWTClaimsSet claims) {
        Object value = claims.getClaims();
        for (String part : properties.getRolesClaim().split("\\.")) {
            if (value instanceof Map<?, ?> map) {
                value = map.get(part);
            } else {
                value = null;
                break;
            }
        }
        Set<String> roles = new HashSet<>();
        collect(value, roles);
        return roles;
    }

    private static void collect(Object value, Set<String> out) {
        if (value instanceof String s) {
            for (String part : s.split("[,\\s]+")) {
                if (!part.isBlank()) {
                    out.add(normalize(part));
                }
            }
        } else if (value instanceof List<?> list) {
            list.forEach(v -> collect(v, out));
        } else if (value instanceof Map<?, ?> map) {
            // e.g. [{"authority": "ROLE_X"}] / [{"name": "X"}]
            collect(map.get("authority"), out);
            collect(map.get("name"), out);
        }
    }

    private static String normalize(String role) {
        String r = role.trim().toUpperCase(Locale.ROOT);
        return r.startsWith("ROLE_") ? r.substring(5) : r;
    }

    private static String extractBearer(String header) {
        if (header == null || header.isBlank()) {
            throw new AuthenticationRequiredException("Authentication is required: no Authorization header.");
        }
        if (header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new AuthenticationRequiredException("Authentication is required: a Bearer token is expected.");
        }
        return header.substring(7).trim();
    }

    private JWSVerifier buildVerifier() {
        String alg = properties.getAlgorithm().toUpperCase(Locale.ROOT);
        try {
            if (alg.startsWith("HS")) {
                byte[] secret = properties.getSecretEncoding() == JwtAuthProperties.SecretEncoding.BASE64
                        ? Base64.getDecoder().decode(properties.getSecret().trim())
                        : properties.getSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return new MACVerifier(secret);
            }
            String pem = properties.getPublicKeyPem().replace("\\n", "\n")
                    .replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
            return new RSASSAVerifier(key);
        } catch (Exception ex) {
            log.error("[SignatureAuth] the configured JWT verification key is unusable: {}", ex.getClass().getSimpleName());
            throw new AuthorizationNotConfiguredException(
                    "Signature management is unavailable: the configured JWT verification key is unusable.");
        }
    }
}
