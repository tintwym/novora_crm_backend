package dev.tintwym.novora.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import dev.tintwym.novora.config.NovoraProperties;
import dev.tintwym.novora.domain.enums.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

	public static final String TYPE_ACCESS = "access";
	public static final String TYPE_REFRESH = "refresh";

	private final NovoraProperties props;
	private final SecretKey accessKey;
	private final SecretKey refreshKey;

	public JwtService(NovoraProperties props) {
		this.props = props;
		this.accessKey = Keys.hmacShaKeyFor(requireSecret("JWT_ACCESS_SECRET", props.jwt().accessSecret()));
		this.refreshKey = Keys.hmacShaKeyFor(requireSecret("JWT_REFRESH_SECRET", props.jwt().refreshSecret()));
	}

	private static byte[] requireSecret(String name, String value) {
		if (value == null || value.isBlank() || value.startsWith("${") || value.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException(name + " must be set to a random value of at least 32 characters");
		}
		return value.getBytes(StandardCharsets.UTF_8);
	}

	public String signAccessToken(String userId, String email, Role role, String companyId) {
		return sign(userId, email, role, companyId, TYPE_ACCESS, props.jwt().accessExpires(), accessKey);
	}

	public String signRefreshToken(String userId, String email, Role role, String companyId) {
		return sign(userId, email, role, companyId, TYPE_REFRESH, props.jwt().refreshExpires(), refreshKey);
	}

	private String sign(String userId, String email, Role role, String companyId, String type, String expires,
			SecretKey key) {
		Instant now = Instant.now();
		Instant exp = now.plus(parseDuration(expires));
		return Jwts.builder()
				.id(UUID.randomUUID().toString())
				.subject(userId)
				.claims(Map.of(
						"email", email,
						"role", role.name(),
						"companyId", companyId,
						"type", type))
				.issuedAt(Date.from(now))
				.expiration(Date.from(exp))
				.signWith(key)
				.compact();
	}

	public Claims parseAccess(String token) {
		return Jwts.parser().verifyWith(accessKey).build().parseSignedClaims(token).getPayload();
	}

	public Claims parseRefresh(String token) {
		return Jwts.parser().verifyWith(refreshKey).build().parseSignedClaims(token).getPayload();
	}

	public Instant refreshExpiryDate() {
		return Instant.now().plus(parseDuration(props.jwt().refreshExpires()));
	}

	static Duration parseDuration(String value) {
		if (value == null || value.isBlank()) {
			return Duration.ofMinutes(15);
		}
		String v = value.trim().toLowerCase();
		if (v.endsWith("ms")) {
			return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2)));
		}
		char unit = v.charAt(v.length() - 1);
		long amount = Long.parseLong(v.substring(0, v.length() - 1));
		return switch (unit) {
			case 's' -> Duration.ofSeconds(amount);
			case 'm' -> Duration.ofMinutes(amount);
			case 'h' -> Duration.ofHours(amount);
			case 'd' -> Duration.ofDays(amount);
			default -> Duration.parse("PT" + v.toUpperCase());
		};
	}
}
