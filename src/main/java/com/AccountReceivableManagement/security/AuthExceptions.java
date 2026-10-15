package com.AccountReceivableManagement.security;

/** Failures of the signature-management authorization check, each mapped to a distinct HTTP status. */
public final class AuthExceptions {

    private AuthExceptions() {
    }

    /** Missing, malformed, tampered or expired token - HTTP 401. */
    public static class AuthenticationRequiredException extends RuntimeException {
        public AuthenticationRequiredException(String message) {
            super(message);
        }
    }

    /** Valid token without the required role - HTTP 403. */
    public static class AccessForbiddenException extends RuntimeException {
        public AccessForbiddenException(String message) {
            super(message);
        }
    }

    /** Verification settings are absent or unusable, so no token can be trusted - HTTP 503. */
    public static class AuthorizationNotConfiguredException extends RuntimeException {
        public AuthorizationNotConfiguredException(String message) {
            super(message);
        }
    }
}
