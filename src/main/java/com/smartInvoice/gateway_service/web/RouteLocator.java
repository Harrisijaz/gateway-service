package com.smartInvoice.gateway_service.web;

import com.smartInvoice.gateway_service.config.GatewayProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class RouteLocator {
	private final GatewayProperties properties;
	private List<GatewayProperties.Route> routes;

	public RouteLocator(GatewayProperties properties) {
		this.properties = properties;
	}

	@PostConstruct
	void validateAndSort() {
		for (GatewayProperties.Route route : properties.getRoutes()) {
			validateRoutePath(route.getPath(), route.getId());
		}
		for (int i = 0; i < properties.getRoutes().size(); i++) {
			for (int j = i + 1; j < properties.getRoutes().size(); j++) {
				GatewayProperties.Route first = properties.getRoutes().get(i);
				GatewayProperties.Route second = properties.getRoutes().get(j);
				if (normalized(first.getPath()).equals(normalized(second.getPath()))) {
					throw new IllegalStateException("Ambiguous gateway route configuration: " + first.getId()
							+ " and " + second.getId() + " both match " + first.getPath());
				}
			}
		}
		this.routes = properties.getRoutes().stream()
				.sorted(Comparator.comparingInt((GatewayProperties.Route route) -> normalized(route.getPath()).length()).reversed())
				.toList();
	}

	public Optional<GatewayProperties.Route> find(String path) {
		return routes.stream().filter(route -> matches(route.getPath(), path)).findFirst();
	}

	public String downstreamPath(GatewayProperties.Route route, String requestPath) {
		if (route.getRewriteTo() == null || route.getRewriteTo().isBlank()) {
			return requestPath;
		}
		String from = normalized(route.getPath());
		String suffix = requestPath.length() > from.length() ? requestPath.substring(from.length()) : "";
		return route.getRewriteTo() + suffix;
	}

	private boolean matches(String configuredPath, String requestPath) {
		String prefix = normalized(configuredPath);
		if (configuredPath.endsWith("/**")) {
			return requestPath.equals(prefix) || requestPath.equals(prefix + "/") || requestPath.startsWith(prefix + "/");
		}
		return requestPath.equals(prefix);
	}

	private static String normalized(String path) {
		return path.endsWith("/**") ? path.substring(0, path.length() - 3) : path;
	}

	private void validateRoutePath(String path, String routeId) {
		if (!path.startsWith("/")) {
			throw new IllegalStateException("Gateway route " + routeId + " path must start with /");
		}
		if (path.contains("**") && !path.endsWith("/**")) {
			throw new IllegalStateException("Gateway route " + routeId + " may only use /** at the end");
		}
	}
}
