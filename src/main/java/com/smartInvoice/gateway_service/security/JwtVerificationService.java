package com.smartInvoice.gateway_service.security;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.smartInvoice.gateway_service.config.GatewayProperties;
import com.smartInvoice.gateway_service.web.GatewayErrorCode;
import com.smartInvoice.gateway_service.web.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class JwtVerificationService {
	private static final Logger log = LoggerFactory.getLogger(JwtVerificationService.class);

	private final GatewayProperties properties;
	private final WebClient webClient;
	private final AtomicReference<CachedJwks> cache = new AtomicReference<>();
	private final byte[] jweSecret;

	public JwtVerificationService(GatewayProperties properties, WebClient webClient) {
		this.properties = properties;
		this.webClient = webClient;
		this.jweSecret = createJweSecret(properties.getJwt());
	}

	public Mono<AuthenticatedPrincipal> verify(String bearerHeader, String expectedType) {
		if (bearerHeader == null || !bearerHeader.startsWith("Bearer ") || bearerHeader.length() <= 7) {
			return Mono.error(new GatewayException(HttpStatus.UNAUTHORIZED, GatewayErrorCode.INVALID_TOKEN,
					"Invalid or missing authentication token"));
		}
		String token = bearerHeader.substring(7).trim();
		return jwks().map(jwkSet -> verifyWithJwks(token, expectedType, jwkSet));
	}

	private Mono<JWKSet> jwks() {
		CachedJwks current = cache.get();
		Instant now = Instant.now();
		if (current != null && current.expiresAt().isAfter(now)) {
			return Mono.just(current.jwkSet());
		}
		return webClient.get()
				.uri(properties.getJwt().getJwksUrl())
				.retrieve()
				.bodyToMono(String.class)
				.map(this::parseJwks)
				.doOnNext(jwkSet -> cache.set(new CachedJwks(jwkSet, now.plus(properties.getJwt().getJwksTtl()),
						now.plus(properties.getJwt().getJwksTtl()).plus(properties.getJwt().getStaleKeyGrace()))))
				.onErrorResume(ex -> {
					CachedJwks stale = cache.get();
					if (stale != null && stale.staleUntil().isAfter(Instant.now())) {
						log.warn("JWKS refresh failed; using stale cached key within grace period", ex);
						return Mono.just(stale.jwkSet());
					}
					return Mono.error(new GatewayException(HttpStatus.SERVICE_UNAVAILABLE,
							GatewayErrorCode.SERVICE_UNAVAILABLE, "Authentication key service is temporarily unavailable"));
				});
	}

	private JWKSet parseJwks(String jwks) {
		try {
			return JWKSet.parse(jwks);
		} catch (Exception ex) {
			throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, GatewayErrorCode.SERVICE_UNAVAILABLE,
					"Authentication key service returned invalid keys");
		}
	}

	private AuthenticatedPrincipal verifyWithJwks(String token, String expectedType, JWKSet jwkSet) {
		try {
			SignedJWT jwt = SignedJWT.parse(decryptIfNeeded(token));
			if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
				throw invalid();
			}
			JWK jwk = jwt.getHeader().getKeyID() == null
					? jwkSet.getKeys().stream().findFirst().orElse(null)
					: jwkSet.getKeyByKeyId(jwt.getHeader().getKeyID());
			if (!(jwk instanceof RSAKey rsaKey) || !jwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey()))) {
				throw invalid();
			}
			JWTClaimsSet claims = jwt.getJWTClaimsSet();
			if (!properties.getJwt().getIssuer().equals(claims.getIssuer())) {
				throw invalid();
			}
			if (!expectedType.equals(claims.getStringClaim("token_type"))) {
				throw invalid();
			}
			Date expiration = claims.getExpirationTime();
			if (expiration == null || expiration.toInstant().plus(properties.getJwt().getClockSkew()).isBefore(Instant.now())) {
				throw new GatewayException(HttpStatus.UNAUTHORIZED, GatewayErrorCode.TOKEN_EXPIRED,
						"Your session has expired. Please log in again.");
			}
			Date issuedAt = claims.getIssueTime();
			if (issuedAt != null && issuedAt.toInstant().minus(properties.getJwt().getClockSkew()).isAfter(Instant.now())) {
				throw invalid();
			}
			String role = claims.getStringClaim("role");
			if (claims.getSubject() == null || claims.getSubject().isBlank() || role == null || role.isBlank()) {
				throw invalid();
			}
			return new AuthenticatedPrincipal(claims.getSubject(), claims.getStringClaim("email"), role,
					claims.getJWTID(), claims.getStringClaim("token_type"));
		} catch (GatewayException ex) {
			throw ex;
		} catch (Exception ex) {
			throw invalid();
		}
	}

	private String decryptIfNeeded(String token) throws Exception {
		if (!properties.getJwt().isTokenEncryptionEnabled()) {
			return token;
		}
		JWEObject jwe = JWEObject.parse(token);
		if (!JWEAlgorithm.DIR.equals(jwe.getHeader().getAlgorithm())
				|| !EncryptionMethod.A256GCM.equals(jwe.getHeader().getEncryptionMethod())) {
			throw invalid();
		}
		jwe.decrypt(new DirectDecrypter(jweSecret));
		return jwe.getPayload().toString();
	}

	private byte[] createJweSecret(GatewayProperties.Jwt jwt) {
		if (!jwt.isTokenEncryptionEnabled()) {
			return new byte[32];
		}
		if (jwt.getJweSecret() == null || jwt.getJweSecret().isBlank()) {
			log.warn("Gateway token decryption is enabled but GATEWAY_JWE_SECRET is empty; encrypted auth tokens cannot be verified");
			return new byte[32];
		}
		try {
			byte[] decoded;
			try {
				decoded = Base64.getUrlDecoder().decode(jwt.getJweSecret());
			} catch (IllegalArgumentException ex) {
				decoded = Base64.getDecoder().decode(jwt.getJweSecret());
			}
			if (decoded.length != 32) {
				throw new IllegalStateException("GATEWAY_JWE_SECRET must decode to 32 bytes");
			}
			return decoded;
		} catch (RuntimeException ex) {
			throw new IllegalStateException("Invalid GATEWAY_JWE_SECRET", ex);
		}
	}

	private GatewayException invalid() {
		return new GatewayException(HttpStatus.UNAUTHORIZED, GatewayErrorCode.INVALID_TOKEN,
				"Invalid or missing authentication token");
	}

	private record CachedJwks(JWKSet jwkSet, Instant expiresAt, Instant staleUntil) {
	}
}
