package com.smartInvoice.gateway_service.web;

import com.smartInvoice.gateway_service.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteLocatorTests {
	@Test
	void choosesMostSpecificRouteAndRewritesConfiguredPrefix() {
		GatewayProperties properties = new GatewayProperties();
		GatewayProperties.Route wildcard = route("admin-service", "/admin/**", "http://admin", null);
		GatewayProperties.Route login = route("admin-auth-login", "/admin/auth/login", "http://auth", null);
		GatewayProperties.Route signup = route("auth-signup", "/auth/signup", "http://auth", "/user/auth/signup");
		properties.setRoutes(List.of(wildcard, login, signup));

		RouteLocator locator = new RouteLocator(properties);
		locator.validateAndSort();

		GatewayProperties.Route matched = locator.find("/admin/auth/login").orElseThrow();
		assertThat(matched.getId()).isEqualTo("admin-auth-login");
		assertThat(locator.downstreamPath(signup, "/auth/signup")).isEqualTo("/user/auth/signup");
		assertThat(locator.find("/missing")).isEmpty();
	}

	@Test
	void wildcardRouteMatchesCollectionRoot() {
		GatewayProperties properties = new GatewayProperties();
		GatewayProperties.Route invoices = route("invoices", "/invoices/**", "http://user", null);
		properties.setRoutes(List.of(invoices));

		RouteLocator locator = new RouteLocator(properties);
		locator.validateAndSort();

		assertThat(locator.find("/invoices")).containsSame(invoices);
		assertThat(locator.find("/invoices/")).containsSame(invoices);
		assertThat(locator.find("/invoices/42")).containsSame(invoices);
	}

	@Test
	void rejectsDuplicateRouteConfigurationAtStartup() {
		GatewayProperties properties = new GatewayProperties();
		properties.setRoutes(List.of(
				route("first", "/invoices/**", "http://invoice", null),
				route("second", "/invoices/**", "http://other", null)));

		RouteLocator locator = new RouteLocator(properties);

		assertThatThrownBy(locator::validateAndSort)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Ambiguous gateway route configuration");
	}

	private GatewayProperties.Route route(String id, String path, String uri, String rewriteTo) {
		GatewayProperties.Route route = new GatewayProperties.Route();
		route.setId(id);
		route.setPath(path);
		route.setUri(uri);
		route.setRewriteTo(rewriteTo);
		return route;
	}
}
