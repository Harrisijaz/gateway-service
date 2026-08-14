package com.smartInvoice.gateway_service.security;

import com.smartInvoice.gateway_service.web.GatewayErrorCode;
import com.smartInvoice.gateway_service.web.GatewayException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class BlacklistService {
	private final StringRedisTemplate redis;

	public BlacklistService(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public Mono<Void> requireNotBlacklisted(String userId) {
		return Mono.fromCallable(() -> Boolean.TRUE.equals(redis.hasKey("blacklist:user:" + userId)))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(blacklisted -> blacklisted
						? Mono.error(new GatewayException(HttpStatus.UNAUTHORIZED, GatewayErrorCode.USER_REVOKED,
								"User access has been revoked"))
						: Mono.empty());
	}
}
