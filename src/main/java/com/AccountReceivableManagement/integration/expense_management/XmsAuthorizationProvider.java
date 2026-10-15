package com.AccountReceivableManagement.integration.expense_management;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the Authorization header for XMS calls the same way the TMS
 * integration does: the caller's header on the current HTTP request is
 * forwarded unchanged (no parsing, regeneration, login or fallback token).
 * XMS validates the token and authorizes the call. The value is never logged.
 */
@Component
public class XmsAuthorizationProvider {

    public String resolveAuthorizationHeader() {
        String header = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            header = request.getHeader(HttpHeaders.AUTHORIZATION);
        }
        if (header == null || header.isBlank()) {
            throw new XmsIntegrationException(XmsIntegrationException.Kind.UNAUTHORIZED, 0,
                    "No Authorization header is present on the current request, so it cannot be forwarded to "
                            + "XMS. Call AR with the user's JWT.");
        }
        return header;
    }
}
