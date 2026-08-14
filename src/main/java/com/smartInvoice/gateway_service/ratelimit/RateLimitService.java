package com.smartInvoice.gateway_service.ratelimit;

import com.smartInvoice.gateway_service.config.GatewayProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

@Service
public class RateLimitService {
	private final GatewayProperties properties;
	private final StringRedisTemplate redis;

	public RateLimitService(GatewayProperties properties, StringRedisTemplate redis) {
		this.properties = properties;
		this.redis = redis;
	}

	public Mono<RateLimitDecision> check(ServerWebExchange exchange) {
		String ip = clientIp(exchange);
		String path = exchange.getRequest().getURI().getRawPath();
		LimitRule rule = ruleFor(path);
		String key = "gateway:ratelimit:" + rule.name() + ":" + ip;
		return Mono.fromCallable(() -> evaluate(key, rule.limit(), rule.window()))
				.subscribeOn(Schedulers.boundedElastic());
	}

	private RateLimitDecision evaluate(String key, int limit, Duration window) {
		long now = Instant.now().toEpochMilli();
		long min = now - window.toMillis();
		String member = now + ":" + UUID.randomUUID();
		redis.opsForZSet().removeRangeByScore(key, 0, min);
		redis.opsForZSet().add(key, member, now);
		redis.expire(key, window.plusSeconds(1));
		Long count = redis.opsForZSet().zCard(key);
		long used = count == null ? 1 : count;
		if (used <= limit) {
			return new RateLimitDecision(true, limit, limit - used, 0);
		}
		Long oldest = Optional.ofNullable(redis.opsForZSet().rangeWithScores(key, 0, 0))
				.flatMap(values -> values.stream().findFirst())
				.map(tuple -> tuple.getScore() == null ? now : tuple.getScore().longValue())
				.orElse(now);
		long retryAfter = Math.max(1, ((oldest + window.toMillis()) - now + 999) / 1000);
		return new RateLimitDecision(false, limit, 0, retryAfter);
	}

	private LimitRule ruleFor(String path) {
		return properties.getRateLimit().getOverrides().stream()
				.filter(rule -> path.equals(rule.getPath()) || path.startsWith(rule.getPath() + "/"))
				.max(Comparator.comparingInt(rule -> rule.getPath().length()))
				.map(rule -> new LimitRule(rule.getPath(), rule.getLimit(), rule.getWindow()))
				.orElseGet(() -> new LimitRule("global", properties.getRateLimit().getGlobalLimit(),
						properties.getRateLimit().getGlobalWindow()));
	}

	private String clientIp(ServerWebExchange exchange) {
		String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			return forwardedFor.split(",")[0].trim();
		}
		return exchange.getRequest().getRemoteAddress() == null
				? "unknown"
				: exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
	}

	private record LimitRule(String name, int limit, Duration window) {
	}
}
