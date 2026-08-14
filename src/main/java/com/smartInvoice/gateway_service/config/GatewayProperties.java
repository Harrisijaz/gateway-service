package com.smartInvoice.gateway_service.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {
	private List<String> allowedOrigins = new ArrayList<>();
	@Valid
	private Jwt jwt = new Jwt();
	private RateLimit rateLimit = new RateLimit();
	@Valid
	private Resilience resilience = new Resilience();
	@NotEmpty
	private List<@Valid Route> routes = new ArrayList<>();

	public List<String> getAllowedOrigins() { return allowedOrigins; }
	public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
	public Jwt getJwt() { return jwt; }
	public void setJwt(Jwt jwt) { this.jwt = jwt; }
	public RateLimit getRateLimit() { return rateLimit; }
	public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
	public Resilience getResilience() { return resilience; }
	public void setResilience(Resilience resilience) { this.resilience = resilience; }
	public List<Route> getRoutes() { return routes; }
	public void setRoutes(List<Route> routes) { this.routes = routes; }

	public static class Jwt {
		@NotBlank
		private String jwksUrl = "http://localhost:9090/auth/.well-known/jwks.json";
		@NotBlank
		private String issuer = "smart-invoice-auth";
		private Duration jwksTtl = Duration.ofHours(1);
		private Duration staleKeyGrace = Duration.ofHours(4);
		private Duration clockSkew = Duration.ofSeconds(30);
		private boolean tokenEncryptionEnabled = true;
		private String jweSecret = "";

		public String getJwksUrl() { return jwksUrl; }
		public void setJwksUrl(String jwksUrl) { this.jwksUrl = jwksUrl; }
		public String getIssuer() { return issuer; }
		public void setIssuer(String issuer) { this.issuer = issuer; }
		public Duration getJwksTtl() { return jwksTtl; }
		public void setJwksTtl(Duration jwksTtl) { this.jwksTtl = jwksTtl; }
		public Duration getStaleKeyGrace() { return staleKeyGrace; }
		public void setStaleKeyGrace(Duration staleKeyGrace) { this.staleKeyGrace = staleKeyGrace; }
		public Duration getClockSkew() { return clockSkew; }
		public void setClockSkew(Duration clockSkew) { this.clockSkew = clockSkew; }
		public boolean isTokenEncryptionEnabled() { return tokenEncryptionEnabled; }
		public void setTokenEncryptionEnabled(boolean tokenEncryptionEnabled) { this.tokenEncryptionEnabled = tokenEncryptionEnabled; }
		public String getJweSecret() { return jweSecret; }
		public void setJweSecret(String jweSecret) { this.jweSecret = jweSecret; }
	}

	public static class RateLimit {
		@Positive
		private int globalLimit = 100;
		private Duration globalWindow = Duration.ofMinutes(1);
		private List<@Valid Override> overrides = new ArrayList<>();

		public int getGlobalLimit() { return globalLimit; }
		public void setGlobalLimit(int globalLimit) { this.globalLimit = globalLimit; }
		public Duration getGlobalWindow() { return globalWindow; }
		public void setGlobalWindow(Duration globalWindow) { this.globalWindow = globalWindow; }
		public List<Override> getOverrides() { return overrides; }
		public void setOverrides(List<Override> overrides) { this.overrides = overrides; }
	}

	public static class Override {
		@NotBlank
		private String path;
		@Positive
		private int limit;
		private Duration window;

		public String getPath() { return path; }
		public void setPath(String path) { this.path = path; }
		public int getLimit() { return limit; }
		public void setLimit(int limit) { this.limit = limit; }
		public Duration getWindow() { return window; }
		public void setWindow(Duration window) { this.window = window; }
	}

	public static class Resilience {
		private Duration defaultTimeout = Duration.ofSeconds(5);
		@Positive
		private int minimumCalls = 10;
		@Positive
		private int failureRateThresholdPercent = 50;
		private Duration openStateCooldown = Duration.ofSeconds(30);
		@Positive
		private int halfOpenTrialCalls = 3;

		public Duration getDefaultTimeout() { return defaultTimeout; }
		public void setDefaultTimeout(Duration defaultTimeout) { this.defaultTimeout = defaultTimeout; }
		public int getMinimumCalls() { return minimumCalls; }
		public void setMinimumCalls(int minimumCalls) { this.minimumCalls = minimumCalls; }
		public int getFailureRateThresholdPercent() { return failureRateThresholdPercent; }
		public void setFailureRateThresholdPercent(int failureRateThresholdPercent) { this.failureRateThresholdPercent = failureRateThresholdPercent; }
		public Duration getOpenStateCooldown() { return openStateCooldown; }
		public void setOpenStateCooldown(Duration openStateCooldown) { this.openStateCooldown = openStateCooldown; }
		public int getHalfOpenTrialCalls() { return halfOpenTrialCalls; }
		public void setHalfOpenTrialCalls(int halfOpenTrialCalls) { this.halfOpenTrialCalls = halfOpenTrialCalls; }
	}

	public static class Route {
		@NotBlank
		private String id;
		@NotBlank
		private String path;
		@NotBlank
		private String uri;
		private String serviceId;
		private boolean authRequired = true;
		private String tokenType = "access";
		private List<String> roles = new ArrayList<>();
		private String rewriteTo;
		private Duration timeout;
		private int retries = 1;

		public String getId() { return id; }
		public void setId(String id) { this.id = id; }
		public String getPath() { return path; }
		public void setPath(String path) { this.path = path; }
		public String getUri() { return uri; }
		public void setUri(String uri) { this.uri = uri; }
		public String getServiceId() { return serviceId == null || serviceId.isBlank() ? id : serviceId; }
		public void setServiceId(String serviceId) { this.serviceId = serviceId; }
		public boolean isAuthRequired() { return authRequired; }
		public void setAuthRequired(boolean authRequired) { this.authRequired = authRequired; }
		public String getTokenType() { return tokenType; }
		public void setTokenType(String tokenType) { this.tokenType = tokenType; }
		public List<String> getRoles() { return roles; }
		public void setRoles(List<String> roles) { this.roles = roles; }
		public String getRewriteTo() { return rewriteTo; }
		public void setRewriteTo(String rewriteTo) { this.rewriteTo = rewriteTo; }
		public Duration getTimeout() { return timeout; }
		public void setTimeout(Duration timeout) { this.timeout = timeout; }
		public int getRetries() { return retries; }
		public void setRetries(int retries) { this.retries = retries; }
	}
}
