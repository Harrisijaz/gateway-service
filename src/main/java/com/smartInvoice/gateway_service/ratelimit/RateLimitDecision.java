package com.smartInvoice.gateway_service.ratelimit;

public record RateLimitDecision(boolean allowed, int limit, long remaining, long retryAfterSeconds) {
}
