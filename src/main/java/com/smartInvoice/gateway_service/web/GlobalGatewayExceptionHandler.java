package com.smartInvoice.gateway_service.web;

import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GlobalGatewayExceptionHandler implements ErrorWebExceptionHandler {
	private static final Logger log = LoggerFactory.getLogger(GlobalGatewayExceptionHandler.class);

	private final ErrorResponseWriter errors;

	public GlobalGatewayExceptionHandler(ErrorResponseWriter errors) {
		this.errors = errors;
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
		if (ex instanceof GatewayException gatewayException) {
			return errors.write(exchange, gatewayException.getStatus(), gatewayException.getCode(), gatewayException.getMessage());
		}
		log.error("Unhandled gateway exception before proxy routing", ex);
		return errors.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, GatewayErrorCode.SERVICE_UNAVAILABLE,
				"Gateway failed before routing. Check gateway logs for the root cause.");
	}
}
