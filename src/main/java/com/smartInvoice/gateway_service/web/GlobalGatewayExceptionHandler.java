package com.smartInvoice.gateway_service.web;

import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GlobalGatewayExceptionHandler implements ErrorWebExceptionHandler {
	private final ErrorResponseWriter errors;

	public GlobalGatewayExceptionHandler(ErrorResponseWriter errors) {
		this.errors = errors;
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
		if (ex instanceof GatewayException gatewayException) {
			return errors.write(exchange, gatewayException.getStatus(), gatewayException.getCode(), gatewayException.getMessage());
		}
		return errors.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, GatewayErrorCode.SERVICE_UNAVAILABLE,
				"Service temporarily unavailable");
	}
}
