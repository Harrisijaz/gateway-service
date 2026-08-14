package com.smartInvoice.gateway_service.resilience;

import com.smartInvoice.gateway_service.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceCircuitBreakerTests {
	@Test
	void opensAfterConfiguredFailureRateOverMinimumWindow() {
		GatewayProperties properties = new GatewayProperties();
		properties.getResilience().setMinimumCalls(10);
		properties.getResilience().setFailureRateThresholdPercent(50);
		properties.getResilience().setOpenStateCooldown(Duration.ofSeconds(30));
		ServiceCircuitBreaker breaker = new ServiceCircuitBreaker(properties);

		for (int i = 0; i < 5; i++) {
			breaker.record("invoice-service", false);
		}
		for (int i = 0; i < 5; i++) {
			breaker.record("invoice-service", true);
		}

		assertThat(breaker.allowRequest("invoice-service")).isFalse();
		assertThat(breaker.allowRequest("auth-service")).isTrue();
	}
}
