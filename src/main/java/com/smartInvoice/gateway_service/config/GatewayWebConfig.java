package com.smartInvoice.gateway_service.config;

import com.smartInvoice.gateway_service.web.ErrorResponseWriter;
import com.smartInvoice.gateway_service.web.GatewayErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Configuration
public class GatewayWebConfig {
	private static final Logger log = LoggerFactory.getLogger(GatewayWebConfig.class);

	@Bean
	WebClient.Builder webClientBuilder() {
		return WebClient.builder();
	}

	@Bean
	WebClient webClient(WebClient.Builder builder) {
		return builder.build();
	}

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	WebFilter originDefenseFilter(GatewayProperties properties, ErrorResponseWriter errors) {
		return (exchange, chain) -> {
			String origin = exchange.getRequest().getHeaders().getOrigin();
			if (origin != null && !isAllowedOrigin(origin, properties)) {
				return errors.write(exchange, HttpStatus.FORBIDDEN, GatewayErrorCode.CORS_ORIGIN_DENIED,
						"Origin " + origin + " is not allowed by the gateway. Add it to gateway.allowed-origins.");
			}
			return chain.filter(exchange);
		};
	}

	@Bean
	CorsWebFilter corsWebFilter(GatewayProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOriginPatterns(allowedOriginPatterns(properties));
		cors.setAllowedMethods(List.of(CorsConfiguration.ALL));
		cors.setAllowedHeaders(List.of(CorsConfiguration.ALL));
		cors.setExposedHeaders(List.of("X-Correlation-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining", HttpHeaders.RETRY_AFTER));
		cors.setAllowCredentials(true);
		cors.setMaxAge(Duration.ofHours(1));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return new CorsWebFilter(source);
	}

	private List<String> allowedOriginPatterns(GatewayProperties properties) {
		List<String> patterns = new java.util.ArrayList<>(properties.getAllowedOrigins());
		patterns.add("http://localhost:*");
		patterns.add("http://127.0.0.1:*");
		return patterns;
	}

	private boolean isAllowedOrigin(String origin, GatewayProperties properties) {
		if (properties.getAllowedOrigins().contains(origin)) {
			return true;
		}
		try {
			URI uri = URI.create(origin);
			String scheme = uri.getScheme();
			String host = uri.getHost();
			return "http".equalsIgnoreCase(scheme)
					&& ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}

	@Bean
	WebFilter correlationLoggingAndSecurityHeadersFilter() {
		return (exchange, chain) -> {
			long start = System.nanoTime();
			String correlationId = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
			if (correlationId == null || correlationId.isBlank()) {
				correlationId = UUID.randomUUID().toString();
			}
			exchange.getAttributes().put("correlationId", correlationId);
			exchange.getResponse().getHeaders().set("X-Correlation-Id", correlationId);
			applyResponseHeaders(exchange);
			String finalCorrelationId = correlationId;
			return chain.filter(exchange).doFinally(signal -> {
				HttpStatus status = HttpStatus.resolve(exchange.getResponse().getStatusCode() == null
						? 200 : exchange.getResponse().getStatusCode().value());
				long latencyMs = (System.nanoTime() - start) / 1_000_000;
				log.info("gateway request method={} path={} status={} latencyMs={} correlationId={}",
						exchange.getRequest().getMethod(), exchange.getRequest().getURI().getRawPath(),
						status == null ? "UNKNOWN" : status.value(), latencyMs, finalCorrelationId);
			});
		};
	}

	@Bean
	WebFilter requestSizeFilter(ErrorResponseWriter errors) {
		return (exchange, chain) -> {
			long contentLength = exchange.getRequest().getHeaders().getContentLength();
			if (contentLength > 5 * 1024 * 1024) {
				return errors.write(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE",
						"Request body exceeds the maximum allowed size");
			}
			return chain.filter(exchange);
		};
	}

	private void applyResponseHeaders(ServerWebExchange exchange) {
		HttpHeaders headers = exchange.getResponse().getHeaders();
		headers.set("X-Content-Type-Options", "nosniff");
		headers.set("X-Frame-Options", "DENY");
		headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
		headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
		headers.remove("Server");
		headers.remove("X-Powered-By");
	}
}
