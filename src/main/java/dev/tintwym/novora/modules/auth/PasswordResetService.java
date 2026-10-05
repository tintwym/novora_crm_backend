package dev.tintwym.novora.modules.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.Cuid;
import dev.tintwym.novora.common.EmailService;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.domain.entity.UserEntity;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.repositories.TenantCompanyRepository;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.UserPrincipal;

/** Single-use links for "forgot password" and for invited teammates to set their first password. */
@Service
public class PasswordResetService {

	public static final String RESET = "RESET";
	public static final String INVITE = "INVITE";

	private static final Duration RESET_TTL = Duration.ofHours(1);
	private static final Duration INVITE_TTL = Duration.ofDays(7);
	private static final int MAX_RESETS_PER_WINDOW = 3;
	private static final Duration RESET_WINDOW = Duration.ofMinutes(15);
	private static final String INVALID_LINK = "This link is invalid or has expired. Ask for a new one.";

	private final SecureRandom random = new SecureRandom();
	private final NamedParameterJdbcTemplate jdbc;
	private final UserEntityRepository users;
	private final TenantCompanyRepository companies;
	private final PasswordEncoder passwordEncoder;
	private final EmailService email;
	private final AuditService audit;

	public PasswordResetService(NamedParameterJdbcTemplate jdbc, UserEntityRepository users,
			TenantCompanyRepository companies, PasswordEncoder passwordEncoder, EmailService email, AuditService audit) {
		this.jdbc = jdbc;
		this.users = users;
		this.companies = companies;
		this.passwordEncoder = passwordEncoder;
		this.email = email;
		this.audit = audit;
	}

	/**
	 * Emails a reset link if the address belongs to an active user. Always succeeds from the caller's point of
	 * view so the response doesn't reveal which emails have accounts.
	 */
	@Transactional
	public void requestReset(String rawEmail) {
		String address = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
		if (!EmailService.isValidAddress(address)) {
			throw new AppException("Enter a valid email address", HttpStatus.BAD_REQUEST);
		}
		UserEntity user = users.findByEmailIgnoreCase(address).orElse(null);
		if (user == null || !user.isActive()) return;
		Integer recent = jdbc.queryForObject("""
				select count(*) from password_reset_tokens
				where user_id = :u and purpose = 'RESET' and created_at > :since
				""", new MapSqlParameterSource("u", user.getId())
				.addValue("since", Timestamp.from(Instant.now().minus(RESET_WINDOW))), Integer.class);
		if (recent != null && recent >= MAX_RESETS_PER_WINDOW) return;

		String link = email.appUrl() + "/reset-password?token=" + issue(user.getId(), RESET, RESET_TTL);
		String name = firstName(user.getName());
		email.sendLater(new EmailService.Email(user.getEmail(), "Reset your Novora CRM password",
				"Hi " + name + ",\n\nSomeone asked to reset the password for your Novora CRM account.\n"
						+ "Open this link within 1 hour to choose a new password:\n" + link
						+ "\n\nIf you didn't ask for this, you can ignore this email; your password won't change.",
				email.layout("Reset your password", List.of("Hi " + name + ",",
						"Someone asked to reset the password for your Novora CRM account. The link below works for 1 hour.",
						"If you didn't ask for this, you can ignore this email; your password won't change."),
						"Choose a new password", link)));
	}

	/** Creates an invite link for a new teammate and emails it. Returns the link for display when email is off. */
	@Transactional
	public Map<String, Object> invite(UserPrincipal actor, UserEntity user) {
		String link = email.appUrl() + "/reset-password?token=" + issue(user.getId(), INVITE, INVITE_TTL);
		String workspace = companies.findById(user.getCompanyId()).map(c -> c.getName()).orElse("Novora CRM");
		String name = firstName(user.getName());
		email.sendLater(new EmailService.Email(user.getEmail(), actor.getName() + " invited you to " + workspace,
				"Hi " + name + ",\n\n" + actor.getName() + " invited you to join " + workspace + " on Novora CRM.\n"
						+ "Open this link within 7 days to set your password:\n" + link
						+ "\n\nYou'll sign in with " + user.getEmail() + ".",
				email.layout("You're invited to " + workspace, List.of("Hi " + name + ",",
						actor.getName() + " invited you to join " + workspace + " on Novora CRM.",
						"Set a password to get started. You'll sign in with " + user.getEmail()
								+ ". The link works for 7 days."),
						"Set your password", link)));
		return JsonMaps.of("emailed", email.isEnabled(), "link", email.isEnabled() ? null : link);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> check(String token) {
		Map<String, Object> row = find(token);
		if (row == null) return JsonMaps.of("valid", false);
		return JsonMaps.of("valid", true, "purpose", row.get("purpose"), "email", row.get("email"),
				"name", row.get("name"));
	}

	@Transactional
	public Map<String, Object> reset(String token, String password) {
		Map<String, Object> row = find(token);
		if (row == null) throw new AppException(INVALID_LINK, HttpStatus.BAD_REQUEST);
		if (password == null || password.length() < 8) {
			throw new AppException("Password must be at least 8 characters", HttpStatus.BAD_REQUEST);
		}
		if (password.length() > 128) {
			throw new AppException("Password must be 128 characters or fewer", HttpStatus.BAD_REQUEST);
		}
		String userId = String.valueOf(row.get("user_id"));
		int claimed = jdbc.update("update password_reset_tokens set used_at = now() where id = :id and used_at is null",
				new MapSqlParameterSource("id", row.get("id")));
		if (claimed == 0) throw new AppException(INVALID_LINK, HttpStatus.BAD_REQUEST);

		UserEntity user = users.findById(userId).orElseThrow(() -> new AppException(INVALID_LINK, HttpStatus.BAD_REQUEST));
		user.setPasswordHash(passwordEncoder.encode(password));
		users.save(user);
		MapSqlParameterSource u = new MapSqlParameterSource("u", userId);
		jdbc.update("update password_reset_tokens set used_at = now() where user_id = :u and used_at is null", u);
		jdbc.update("update refresh_tokens set revoked_at = now() where user_id = :u and revoked_at is null", u);

		boolean invite = INVITE.equals(row.get("purpose"));
		UserPrincipal self = new UserPrincipal(user.getId(), user.getEmail(), user.getName(), user.getPasswordHash(),
				user.getRole(), user.getCompanyId(), user.isActive());
		audit.record(self, invite ? "INVITE_ACCEPTED" : "PASSWORD_RESET", "USER", user.getId(), user.getName(), null);
		return JsonMaps.of("email", user.getEmail());
	}

	private String issue(String userId, String purpose, Duration ttl) {
		MapSqlParameterSource p = new MapSqlParameterSource("u", userId).addValue("purpose", purpose);
		jdbc.update("update password_reset_tokens set used_at = now() where user_id = :u and purpose = :purpose and used_at is null", p);
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		jdbc.update("""
				insert into password_reset_tokens (id, user_id, token_hash, purpose, expires_at, created_at)
				values (:id, :u, :hash, :purpose, :expires, now())
				""", p.addValue("id", Cuid.generate()).addValue("hash", hash(token))
				.addValue("expires", Timestamp.from(Instant.now().plus(ttl))));
		return token;
	}

	private Map<String, Object> find(String token) {
		if (token == null || token.isBlank() || token.length() > 200) return null;
		return jdbc.queryForList("""
				select t.id, t.user_id, t.purpose, u.email, u.name
				from password_reset_tokens t join users u on u.id = t.user_id
				where t.token_hash = :hash and t.used_at is null and t.expires_at > now() and u.is_active
				""", new MapSqlParameterSource("hash", hash(token.trim()))).stream().findFirst().orElse(null);
	}

	private static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String firstName(String name) {
		if (name == null || name.isBlank()) return "there";
		return name.trim().split("\\s+")[0];
	}
}
