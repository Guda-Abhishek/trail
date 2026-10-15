package com.AccountReceivableManagement.global_exception_handler;

import com.AccountReceivableManagement.dto.centralizeddto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Diagnostic only: records which handler turned which exception into which HTTP status, so a 4xx/5xx
     * seen by the browser can be traced to its origin. The message is the one already returned to the
     * caller; no headers or tokens are logged.
     */
    private static void logFailure(HttpStatus status, Exception ex, HttpServletRequest request) {
        log.warn("[ApiError] status={} method={} path={} exception={} message={}",
                status.value(),
                request != null ? request.getMethod() : "?",
                request != null ? request.getRequestURI() : "?",
                ex.getClass().getName(),
                ex.getMessage());
    }


    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        logFailure(HttpStatus.BAD_REQUEST, ex, request);

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Validation failed.");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.builder()
                        .success(false)
                        .message(message)
                        .data(null)
                        .build());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNotFound(
            ResourceNotFoundException ex) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(DuplicateBillingConfigurationException.class)
    public ResponseEntity<ApiResponse<Object>> handleDuplicate(
            DuplicateBillingConfigurationException ex) {

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    /**
     * Failed XMS calls. XMS rejecting AR's credentials is an upstream
     * problem, not the AR caller's, so it is a 502 rather than a 403.
     */
    @ExceptionHandler(com.AccountReceivableManagement.integration.expense_management.XmsIntegrationException.class)
    public ResponseEntity<ApiResponse<Object>> handleXmsIntegration(
            com.AccountReceivableManagement.integration.expense_management.XmsIntegrationException ex,
            HttpServletRequest request) {

        HttpStatus status = switch (ex.getKind()) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND, CONFLICT -> HttpStatus.CONFLICT;
            case UNAUTHORIZED -> HttpStatus.BAD_GATEWAY;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case MISCONFIGURED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        log.warn("[ApiError] status={} method={} path={} exception=XmsIntegrationException kind={} upstreamStatus={} message={}",
                status.value(), request != null ? request.getMethod() : "?",
                request != null ? request.getRequestURI() : "?", ex.getKind(), ex.getUpstreamStatus(), ex.getMessage());

        return ResponseEntity.status(status)
                .body(ApiResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(com.AccountReceivableManagement.security.AuthExceptions.AuthenticationRequiredException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnauthenticated(
            com.AccountReceivableManagement.security.AuthExceptions.AuthenticationRequiredException ex) {
        return error(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(com.AccountReceivableManagement.security.AuthExceptions.AccessForbiddenException.class)
    public ResponseEntity<ApiResponse<Object>> handleForbidden(
            com.AccountReceivableManagement.security.AuthExceptions.AccessForbiddenException ex) {
        return error(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(com.AccountReceivableManagement.security.AuthExceptions.AuthorizationNotConfiguredException.class)
    public ResponseEntity<ApiResponse<Object>> handleAuthNotConfigured(
            com.AccountReceivableManagement.security.AuthExceptions.AuthorizationNotConfiguredException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleUploadTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "The uploaded file exceeds the maximum allowed size.");
    }

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingPart(
            org.springframework.web.multipart.support.MissingServletRequestPartException ex) {
        return error(HttpStatus.BAD_REQUEST, "The signature file is missing.");
    }

    private static ResponseEntity<ApiResponse<Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(ApiResponse.builder().success(false).message(message).data(null).build());
    }

    /**
     * The approved exchange-rate source could not be read. Only the fixed public message is returned - the
     * cause (SQL, host names, ...) stays in the server log - and 503 tells the caller it may retry.
     */
    @ExceptionHandler(com.AccountReceivableManagement.integration.currency.ExchangeRateSourceUnavailableException.class)
    public ResponseEntity<ApiResponse<Object>> handleExchangeRateSourceUnavailable(
            com.AccountReceivableManagement.integration.currency.ExchangeRateSourceUnavailableException ex,
            HttpServletRequest request) {
        log.warn("[ApiError] status=503 method={} path={} exception=ExchangeRateSourceUnavailableException",
                request != null ? request.getMethod() : "?",
                request != null ? request.getRequestURI() : "?");
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                com.AccountReceivableManagement.integration.currency.ExchangeRateSourceUnavailableException.PUBLIC_MESSAGE);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(
            ValidationException ex, HttpServletRequest request) {
        logFailure(HttpStatus.BAD_REQUEST, ex, request);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        logFailure(HttpStatus.BAD_REQUEST, ex, request);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalState(
            IllegalStateException ex, HttpServletRequest request) {
        logFailure(HttpStatus.BAD_REQUEST, ex, request);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Object>> handleDuplicateResource(
            DuplicateResourceException ex) {

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    /*
     * ==========================================
     * CONCURRENT RECORD LOCK
     * ==========================================
     */

    @ExceptionHandler(RecordLockedException.class)
    public ResponseEntity<ApiResponse<Object>> handleRecordLocked(
            RecordLockedException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }


    /*
     * ==========================================
     * OPTIMISTIC LOCKING
     * ==========================================
     */

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Object>> handleOptimisticLocking(
            ObjectOptimisticLockingFailureException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.builder()
                        .success(false)
                        .message(
                                "This record was modified by another user. "
                                        + "Please refresh the page and try again."
                        )
                        .data(null)
                        .build());
    }

    /** Malformed JSON or an unknown enum value is a client error, not a 500. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex, HttpServletRequest request) {
        logFailure(HttpStatus.BAD_REQUEST, ex, request);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.builder()
                        .success(false)
                        .message("Request body is malformed or contains an invalid value.")
                        .data(null)
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleException(
            Exception ex, HttpServletRequest request) {
        log.error("[ApiError] status=500 method={} path={} unhandled exception",
                request != null ? request.getMethod() : "?",
                request != null ? request.getRequestURI() : "?", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(null)
                        .build());
    }

    public static class ResourceNotFoundException
            extends RuntimeException {

        public ResourceNotFoundException(String message) {
            super(message);
        }
    }

    public static class DuplicateBillingConfigurationException
            extends RuntimeException {

        public DuplicateBillingConfigurationException(String message) {
            super(message);
        }
    }

    public static class DuplicateResourceException
            extends RuntimeException {

        public DuplicateResourceException(String message) {
            super(message);
        }
    }

    public static class ValidationException
            extends RuntimeException {

        public ValidationException(String message) {
            super(message);
        }
    }
}