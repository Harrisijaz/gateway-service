package com.smartInvoice.gateway_service.web;

public final class GatewayErrorCode {
	public static final String INVALID_TOKEN = "INVALID_TOKEN";
	public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
	public static final String USER_REVOKED = "USER_REVOKED";
	public static final String FORBIDDEN = "FORBIDDEN";
	public static final String NOT_FOUND = "NOT_FOUND";
	public static final String RATE_LIMITED = "RATE_LIMITED";
	public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
	public static final String CORS_ORIGIN_DENIED = "CORS_ORIGIN_DENIED";

	private GatewayErrorCode() {
	}
}
