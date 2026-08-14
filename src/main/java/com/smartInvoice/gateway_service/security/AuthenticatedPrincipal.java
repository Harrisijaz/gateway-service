package com.smartInvoice.gateway_service.security;

public record AuthenticatedPrincipal(String userId, String email, String role, String jti, String tokenType) {
}
