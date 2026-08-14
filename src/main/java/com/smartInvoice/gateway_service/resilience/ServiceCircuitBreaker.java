package com.smartInvoice.gateway_service.resilience;

import com.smartInvoice.gateway_service.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ServiceCircuitBreaker {
	private final GatewayProperties properties;
	private final Map<String, BreakerState> states = new ConcurrentHashMap<>();

	public ServiceCircuitBreaker(GatewayProperties properties) {
		this.properties = properties;
	}

	public boolean allowRequest(String serviceId) {
		BreakerState state = states.computeIfAbsent(serviceId, ignored -> new BreakerState());
		synchronized (state) {
			if (state.openUntil != null) {
				if (state.openUntil.isAfter(Instant.now())) {
					return false;
				}
				state.openUntil = null;
				state.halfOpenRemaining = properties.getResilience().getHalfOpenTrialCalls();
				state.halfOpenSuccesses = 0;
			}
			if (state.halfOpenRemaining > 0) {
				state.halfOpenRemaining--;
			}
			return true;
		}
	}

	public void record(String serviceId, boolean success) {
		BreakerState state = states.computeIfAbsent(serviceId, ignored -> new BreakerState());
		synchronized (state) {
			if (state.halfOpenRemaining >= 0) {
				if (!success) {
					open(state);
					return;
				}
				state.halfOpenSuccesses++;
				if (state.halfOpenSuccesses >= properties.getResilience().getHalfOpenTrialCalls()) {
					state.halfOpenRemaining = -1;
					state.halfOpenSuccesses = 0;
					state.openUntil = null;
					state.results.clear();
				}
				return;
			}
			state.results.addLast(success);
			while (state.results.size() > properties.getResilience().getMinimumCalls()) {
				state.results.removeFirst();
			}
			if (state.results.size() >= properties.getResilience().getMinimumCalls()) {
				long failures = state.results.stream().filter(result -> !result).count();
				if ((failures * 100 / state.results.size()) >= properties.getResilience().getFailureRateThresholdPercent()) {
					open(state);
				}
			}
		}
	}

	private void open(BreakerState state) {
		state.openUntil = Instant.now().plus(properties.getResilience().getOpenStateCooldown());
		state.halfOpenRemaining = -1;
	}

	private static class BreakerState {
		private final Deque<Boolean> results = new ArrayDeque<>();
		private Instant openUntil;
		private int halfOpenRemaining = -1;
		private int halfOpenSuccesses;
	}
}
