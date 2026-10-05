package dev.tintwym.novora.modules.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.domain.entity.RefreshToken;
import dev.tintwym.novora.domain.entity.TenantCompany;
import dev.tintwym.novora.domain.entity.UserEntity;
import dev.tintwym.novora.domain.enums.Role;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.repositories.RefreshTokenRepository;
import dev.tintwym.novora.repositories.TenantCompanyRepository;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.JwtService;
import dev.tintwym.novora.security.UserPrincipal;

import io.jsonwebtoken.Claims;

@Service
public class AuthService {

	private final UserEntityRepository users;
	private final TenantCompanyRepository companies;
	private final RefreshTokenRepository refreshTokens;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final AuditService audit;

	public AuthService(UserEntityRepository users, TenantCompanyRepository companies,
			RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder, JwtService jwtService,
			AuditService audit) {
		this.audit = audit;
		this.users = users;
		this.companies = companies;
		this.refreshTokens = refreshTokens;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
	}

	@Transactional
	public Map<String, Object> register(Map<String, Object> body) {
		String email = str(body, "email").toLowerCase();
		if (users.existsByEmailIgnoreCase(email)) {
			throw new AppException("Email already registered", HttpStatus.CONFLICT);
		}

		String companyName = str(body, "companyName");
		String baseSlug = companyName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
		if (baseSlug.length() > 40) {
			baseSlug = baseSlug.substring(0, 40);
		}
		if (baseSlug.isBlank()) {
			baseSlug = "workspace";
		}
		String suffix = Long.toString(System.currentTimeMillis(), 36);
		if (suffix.length() > 4) {
			suffix = suffix.substring(suffix.length() - 4);
		}
		String portalSlug = baseSlug + "-" + suffix;

		TenantCompany company = new TenantCompany();
		company.setName(companyName);
		company.setIndustry(optStr(body, "industry"));
		company.setSize(optStr(body, "size"));
		company.setPortalSlug(portalSlug);
		companies.save(company);

		UserEntity user = new UserEntity();
		user.setName(str(body, "name"));
		user.setEmail(email);
		user.setPasswordHash(passwordEncoder.encode(str(body, "password")));
		user.setRole(Role.ADMIN);
		user.setCompanyId(company.getId());
		users.save(user);

		Map<String, Object> tokens = issueTokens(user);
		return JsonMaps.of(
				"user", toAuthUser(user),
				"company", JsonMaps.of("id", company.getId(), "name", company.getName()),
				"tokens", tokens);
	}

	@Transactional
	public Map<String, Object> login(Map<String, Object> body) {
		String email = str(body, "email").toLowerCase();
		UserEntity user = users.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new AppException("Invalid email or password", HttpStatus.UNAUTHORIZED));
		if (!user.isActive() || !passwordEncoder.matches(str(body, "password"), user.getPasswordHash())) {
			throw new AppException("Invalid email or password", HttpStatus.UNAUTHORIZED);
		}
		return JsonMaps.of("user", toAuthUser(user), "tokens", issueTokens(user));
	}

	@Transactional
	public Map<String, Object> refresh(String refreshToken) {
		Claims claims;
		try {
			claims = jwtService.parseRefresh(refreshToken);
		} catch (Exception e) {
			throw new AppException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED);
		}
		if (!JwtService.TYPE_REFRESH.equals(claims.get("type", String.class))) {
			throw new AppException("Invalid refresh token", HttpStatus.UNAUTHORIZED);
		}
		RefreshToken stored = refreshTokens.findByToken(refreshToken)
				.orElseThrow(() -> new AppException("Refresh token revoked or expired", HttpStatus.UNAUTHORIZED));
		if (stored.getRevokedAt() != null || stored.getExpiresAt().isBefore(java.time.Instant.now())) {
			throw new AppException("Refresh token revoked or expired", HttpStatus.UNAUTHORIZED);
		}
		UserEntity user = users.findById(claims.getSubject())
				.orElseThrow(() -> new AppException("User not found or inactive", HttpStatus.UNAUTHORIZED));
		if (!user.isActive()) {
			throw new AppException("User not found or inactive", HttpStatus.UNAUTHORIZED);
		}
		stored.setRevokedAt(java.time.Instant.now());
		refreshTokens.save(stored);
		return JsonMaps.of("user", toAuthUser(user), "tokens", issueTokens(user));
	}

	@Transactional
	public void logout(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			return;
		}
		refreshTokens.findByToken(refreshToken).ifPresent(rt -> {
			if (rt.getRevokedAt() == null) {
				rt.setRevokedAt(java.time.Instant.now());
				refreshTokens.save(rt);
			}
		});
	}

	@Transactional(readOnly = true)
	public Map<String, Object> me(String userId) {
		UserEntity user = users.findById(userId)
				.orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));
		if (!user.isActive()) {
			throw new AppException("User not found", HttpStatus.NOT_FOUND);
		}
		TenantCompany company = companies.findById(user.getCompanyId())
				.orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));
		Map<String, Object> dto = toAuthUser(user);
		dto.put("company", JsonMaps.of(
				"id", company.getId(),
				"name", company.getName(),
				"industry", company.getIndustry(),
				"size", company.getSize()));
		return dto;
	}

	@Transactional
	public Map<String, Object> updateProfile(String userId, Map<String, Object> body) {
		UserEntity user = users.findById(userId)
				.orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));
		if (body.containsKey("name")) {
			user.setName(str(body, "name"));
		}
		if (body.containsKey("email")) {
			String email = str(body, "email").toLowerCase();
			if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
				throw new AppException("Enter a valid email address", HttpStatus.BAD_REQUEST);
			}
			if (!email.equalsIgnoreCase(user.getEmail()) && users.existsByEmailIgnoreCase(email)) {
				throw new AppException("Email already in use", HttpStatus.CONFLICT);
			}
			user.setEmail(email);
		}
		users.save(user);
		return me(userId);
	}

	@Transactional
	public void changePassword(UserPrincipal actor, Map<String, Object> body) {
		UserEntity user = users.findById(actor.getId())
				.orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));
		if (!passwordEncoder.matches(str(body, "currentPassword"), user.getPasswordHash())) {
			throw new AppException("Current password is incorrect", HttpStatus.BAD_REQUEST);
		}
		String next = str(body, "newPassword");
		if (next.length() < 8) {
			throw new AppException("New password must be at least 8 characters", HttpStatus.BAD_REQUEST);
		}
		user.setPasswordHash(passwordEncoder.encode(next));
		users.save(user);
		audit.record(actor, "PASSWORD_CHANGE", "USER", user.getId(), user.getName(), null);
	}

	@Transactional
	public Map<String, Object> updateCompany(UserPrincipal actor, Map<String, Object> body) {
		TenantCompany company = companies.findById(actor.getCompanyId())
				.orElseThrow(() -> new AppException("Workspace not found", HttpStatus.NOT_FOUND));
		Map<String, Object> before = companyDto(company);
		if (body.containsKey("name")) company.setName(str(body, "name"));
		if (body.containsKey("industry")) company.setIndustry(optStr(body, "industry"));
		if (body.containsKey("size")) company.setSize(optStr(body, "size"));
		companies.save(company);
		Map<String, Object> after = companyDto(company);
		audit.updated(actor, "WORKSPACE", company.getId(), company.getName(), before, after, "name", "industry", "size");
		return after;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> company(String companyId) {
		return companyDto(companies.findById(companyId)
				.orElseThrow(() -> new AppException("Workspace not found", HttpStatus.NOT_FOUND)));
	}

	private Map<String, Object> companyDto(TenantCompany c) {
		return JsonMaps.of(
				"id", c.getId(),
				"name", c.getName(),
				"industry", c.getIndustry(),
				"size", c.getSize(),
				"portalSlug", c.getPortalSlug());
	}

	private Map<String, Object> issueTokens(UserEntity user) {
		String access = jwtService.signAccessToken(user.getId(), user.getEmail(), user.getRole(), user.getCompanyId());
		String refresh = jwtService.signRefreshToken(user.getId(), user.getEmail(), user.getRole(), user.getCompanyId());
		RefreshToken rt = new RefreshToken();
		rt.setToken(refresh);
		rt.setUserId(user.getId());
		rt.setExpiresAt(jwtService.refreshExpiryDate());
		refreshTokens.save(rt);
		return JsonMaps.of("accessToken", access, "refreshToken", refresh);
	}

	private Map<String, Object> toAuthUser(UserEntity user) {
		return JsonMaps.of(
				"id", user.getId(),
				"name", user.getName(),
				"email", user.getEmail(),
				"role", user.getRole().name(),
				"companyId", user.getCompanyId());
	}

	private static String str(Map<String, Object> body, String key) {
		Object v = body.get(key);
		if (v == null || String.valueOf(v).isBlank()) {
			throw new AppException(key + " is required", HttpStatus.BAD_REQUEST);
		}
		return String.valueOf(v).trim();
	}

	private static String optStr(Map<String, Object> body, String key) {
		Object v = body.get(key);
		if (v == null) {
			return null;
		}
		String s = String.valueOf(v).trim();
		return s.isEmpty() ? null : s;
	}
}
