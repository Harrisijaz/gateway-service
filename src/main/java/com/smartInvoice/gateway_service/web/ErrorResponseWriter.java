package com.smartInvoice.gateway_service.web;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
public class ErrorResponseWriter {
	public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
		return write(exchange, status, code, message, null);
	}

	public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message, String retryAfter) {
		if (exchange.getResponse().isCommitted()) {
			return Mono.empty();
		}
		String correlationId = exchange.getAttributeOrDefault("correlationId", "");
		byte[] bytes = json(code, message, correlationId).getBytes(StandardCharsets.UTF_8);
		exchange.getResponse().setStatusCode(status);
		HttpHeaders headers = exchange.getResponse().getHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		applySecurityHeaders(headers);
		if (retryAfter != null) {
			headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
		}
		DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
		return exchange.getResponse().writeWith(Mono.just(buffer));
	}

	private String json(String code, String message, String correlationId) {
		return "{\"error\":{\"code\":\"" + escape(code)
				+ "\",\"message\":\"" + escape(message)
				+ "\",\"correlationId\":\"" + escape(correlationId)
				+ "\",\"timestamp\":\"" + Instant.now() + "\"}}";
	}

	private String escape(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private void applySecurityHeaders(HttpHeaders headers) {
		headers.set("X-Content-Type-Options", "nosniff");
		headers.set("X-Frame-Options", "DENY");
		headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
		headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
		headers.remove("Server");
		headers.remove("X-Powered-By");
	}
}
