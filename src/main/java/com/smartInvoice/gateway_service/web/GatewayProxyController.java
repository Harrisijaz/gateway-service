package com.smartInvoice.gateway_service.web;

import com.smartInvoice.gateway_service.config.GatewayProperties;
import com.smartInvoice.gateway_service.ratelimit.RateLimitDecision;
import com.smartInvoice.gateway_service.ratelimit.RateLimitService;
import com.smartInvoice.gateway_service.resilience.ServiceCircuitBreaker;
import com.smartInvoice.gateway_service.security.AuthenticatedPrincipal;
import com.smartInvoice.gateway_service.security.BlacklistService;
import com.smartInvoice.gateway_service.security.JwtVerificationService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@RestController
public class GatewayProxyController {
	private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
			"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
			"te", "trailer", "transfer-encoding", "upgrade", "host");

	private final RouteLocator routeLocator;
	private final JwtVerificationService jwtVerification;
	private final BlacklistService blacklist;
	private final RateLimitService rateLimit;
	private final ServiceCircuitBreaker circuitBreaker;
	private final ErrorResponseWriter errors;
	private final GatewayProperties properties;
	private final WebClient webClient;

	public GatewayProxyController(RouteLocator routeLocator, JwtVerificationService jwtVerification,
			BlacklistService blacklist, RateLimitService rateLimit, ServiceCircuitBreaker circuitBreaker,
			ErrorResponseWriter errors, GatewayProperties properties, WebClient webClient) {
		this.routeLocator = routeLocator;
		this.jwtVerification = jwtVerification;
		this.blacklist = blacklist;
		this.rateLimit = rateLimit;
		this.circuitBreaker = circuitBreaker;
		this.errors = errors;
		this.properties = properties;
		this.webClient = webClient;
	}

	@RequestMapping("/health")
	public Mono<Void> health(ServerWebExchange exchange) {
		exchange.getResponse().setStatusCode(HttpStatus.OK);
		exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
		DataBuffer buffer = exchange.getResponse().bufferFactory().wrap("{\"status\":\"UP\"}".getBytes());
		return exchange.getResponse().writeWith(Mono.just(buffer));
	}

	@RequestMapping("/actuator/health")
	public Mono<Void> actuatorHealth(ServerWebExchange exchange) {
		return health(exchange);
	}

	@RequestMapping("/**")
	public Mono<Void> proxy(ServerWebExchange exchange) {
		String path = exchange.getRequest().getURI().getRawPath();
		return rateLimit.check(exchange)
				.flatMap(decision -> decision.allowed()
						? continueAfterRateLimit(exchange, path, decision)
						: rejectRateLimited(exchange, decision));
	}

	private Mono<Void> continueAfterRateLimit(ServerWebExchange exchange, String path, RateLimitDecision decision) {
		writeRateHeaders(exchange, decision);
		return routeLocator.find(path)
				.map(route -> authorize(exchange, route).flatMap(principal -> route(exchange, route, principal)))
				.orElseGet(() -> errors.write(exchange, HttpStatus.NOT_FOUND, GatewayErrorCode.NOT_FOUND, "Route not found"));
	}

	private Mono<Void> rejectRateLimited(ServerWebExchange exchange, RateLimitDecision decision) {
		writeRateHeaders(exchange, decision);
		return errors.write(exchange, HttpStatus.TOO_MANY_REQUESTS, GatewayErrorCode.RATE_LIMITED,
				"Too many requests. Please retry later.", String.valueOf(decision.retryAfterSeconds()));
	}

	private Mono<AuthenticatedPrincipal> authorize(ServerWebExchange exchange, GatewayProperties.Route route) {
		if (!route.isAuthRequired()) {
			return Mono.just(new AuthenticatedPrincipal("", "", "", "", ""));
		}
		return jwtVerification.verify(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION), route.getTokenType())
				.flatMap(principal -> blacklist.requireNotBlacklisted(principal.userId()).thenReturn(principal))
				.flatMap(principal -> {
					if (!route.getRoles().isEmpty() && !route.getRoles().contains(principal.role())) {
						return Mono.error(new GatewayException(HttpStatus.FORBIDDEN, GatewayErrorCode.FORBIDDEN,
								"Authenticated user is not allowed to access this route"));
					}
					return Mono.just(principal);
				});
	}

	private Mono<Void> route(ServerWebExchange exchange, GatewayProperties.Route route, AuthenticatedPrincipal principal) {
		if (!circuitBreaker.allowRequest(route.getServiceId())) {
			return errors.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, GatewayErrorCode.SERVICE_UNAVAILABLE,
					"Service temporarily unavailable");
		}
		URI target = buildTargetUri(exchange, route);
		Mono<Void> attempt = forward(exchange, route, principal, target)
				.timeout(route.getTimeout() == null ? properties.getResilience().getDefaultTimeout() : route.getTimeout())
				.doOnSuccess(ignored -> circuitBreaker.record(route.getServiceId(), isSuccessful(exchange.getResponse())))
				.doOnError(ex -> circuitBreaker.record(route.getServiceId(), false));
		if (isRetryable(exchange.getRequest().getMethod()) && route.getRetries() > 0) {
			attempt = attempt.retryWhen(Retry.max(route.getRetries()).filter(this::shouldRetry));
		}
		return attempt.onErrorResume(ex -> errors.write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
				GatewayErrorCode.SERVICE_UNAVAILABLE, "Service temporarily unavailable"));
	}

	private Mono<Void> forward(ServerWebExchange exchange, GatewayProperties.Route route,
			AuthenticatedPrincipal principal, URI target) {
		ServerHttpRequest request = exchange.getRequest();
		WebClient.RequestBodySpec spec = webClient.method(request.getMethod())
				.uri(target)
				.headers(headers -> copyRequestHeaders(request.getHeaders(), headers, principal,
						exchange.getAttributeOrDefault("correlationId", "")));
		return spec.body(BodyInserters.fromDataBuffers(request.getBody()))
				.exchangeToMono(response -> writeDownstreamResponse(exchange, response));
	}

	private Mono<Void> writeDownstreamResponse(ServerWebExchange exchange, ClientResponse downstream) {
		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(downstream.statusCode());
		downstream.headers().asHttpHeaders().forEach((name, values) -> {
			if (!isInternalOrHopByHop(name)) {
				response.getHeaders().put(name, values);
			}
		});
		applySecurityHeaders(response.getHeaders());
		if (downstream.statusCode().is5xxServerError()) {
			return downstream.bodyToMono(Void.class)
					.then(Mono.error(new GatewayException(HttpStatus.SERVICE_UNAVAILABLE,
							GatewayErrorCode.SERVICE_UNAVAILABLE, "Service temporarily unavailable")));
		}
		return response.writeWith(downstream.bodyToFlux(DataBuffer.class));
	}

	private URI buildTargetUri(ServerWebExchange exchange, GatewayProperties.Route route) {
		String downstreamPath = routeLocator.downstreamPath(route, exchange.getRequest().getURI().getRawPath());
		String rawQuery = exchange.getRequest().getURI().getRawQuery();
		String target = route.getUri() + downstreamPath + (rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery);
		return URI.create(target);
	}

	private void copyRequestHeaders(HttpHeaders source, HttpHeaders target, AuthenticatedPrincipal principal, String correlationId) {
		source.forEach((name, values) -> {
			if (!isInternalOrHopByHop(name)
					&& !"x-user-id".equalsIgnoreCase(name)
					&& !"x-user-role".equalsIgnoreCase(name)
					&& !"x-user-email".equalsIgnoreCase(name)) {
				target.put(name, values);
			}
		});
		target.set("X-Correlation-Id", correlationId);
		if (!principal.userId().isBlank()) {
			target.set("X-User-Id", principal.userId());
			target.set("X-User-Role", principal.role());
			if (principal.email() != null && !principal.email().isBlank()) {
				target.set("X-User-Email", principal.email());
			}
		}
	}

	private boolean shouldRetry(Throwable ex) {
		return ex instanceof TimeoutException || ex instanceof GatewayException;
	}

	private boolean isRetryable(HttpMethod method) {
		return method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS;
	}

	private boolean isSuccessful(ServerHttpResponse response) {
		return response.getStatusCode() != null && !response.getStatusCode().is5xxServerError();
	}

	private boolean isInternalOrHopByHop(String name) {
		String normalized = name.toLowerCase();
		return HOP_BY_HOP_HEADERS.contains(normalized)
				|| "server".equals(normalized)
				|| "x-powered-by".equals(normalized)
				|| "x-application-context".equals(normalized);
	}

	private void writeRateHeaders(ServerWebExchange exchange, RateLimitDecision decision) {
		HttpHeaders headers = exchange.getResponse().getHeaders();
		headers.set("X-RateLimit-Limit", String.valueOf(decision.limit()));
		headers.set("X-RateLimit-Remaining", String.valueOf(Math.max(0, decision.remaining())));
		if (!decision.allowed()) {
			headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
		}
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
