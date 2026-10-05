package dev.tintwym.novora.modules.users;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.domain.entity.UserEntity;
import dev.tintwym.novora.domain.enums.Role;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.modules.auth.PasswordResetService;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.UserPrincipal;

@Service
public class UsersService {

	private final UserEntityRepository users;
	private final PasswordEncoder passwordEncoder;
	private final AuditService audit;
	private final PasswordResetService passwordResets;
	private final SecureRandom random = new SecureRandom();

	public UsersService(UserEntityRepository users, PasswordEncoder passwordEncoder, AuditService audit,
			PasswordResetService passwordResets) {
		this.audit = audit;
		this.passwordResets = passwordResets;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UserPrincipal actor) {
		return users.findAll().stream()
				.filter(u -> actor.getCompanyId().equals(u.getCompanyId()))
				.sorted(Comparator.<UserEntity, Boolean>comparing(u -> u.isActive()).reversed()
						.thenComparing(u -> u.getName() != null ? u.getName() : ""))
				.map(this::toDto)
				.toList();
	}

	@Transactional
	public Map<String, Object> create(UserPrincipal actor, Map<String, Object> body) {
		String email = str(body, "email").toLowerCase();
		if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
			throw new AppException("Enter a valid email address", HttpStatus.BAD_REQUEST);
		}
		if (users.existsByEmailIgnoreCase(email)) {
			throw new AppException("Email already in use", HttpStatus.CONFLICT);
		}
		boolean sendInvite = asBool(body.get("sendInvite"));
		String rawPassword = body.get("password") == null ? "" : String.valueOf(body.get("password"));
		if (rawPassword.isBlank() && !sendInvite) {
			throw new AppException("Set a temporary password or send an email invite", HttpStatus.BAD_REQUEST);
		}
		UserEntity user = new UserEntity();
		user.setName(str(body, "name"));
		user.setEmail(email);
		user.setPasswordHash(passwordEncoder.encode(rawPassword.isBlank() ? unguessable() : password(rawPassword)));
		user.setRole(assignableRole(actor, str(body, "role")));
		user.setCompanyId(actor.getCompanyId());
		users.saveAndFlush(user);
		audit.record(actor, AuditService.CREATE, "USER", user.getId(), user.getName(),
				JsonMaps.of("email", user.getEmail(), "role", user.getRole().name(), "invited", sendInvite));
		Map<String, Object> dto = toDto(user);
		if (sendInvite) dto.put("invite", passwordResets.invite(actor, user));
		return dto;
	}

	@Transactional
	public Map<String, Object> resendInvite(UserPrincipal actor, String userId) {
		UserEntity target = users.findByIdAndCompanyId(userId, actor.getCompanyId())
				.orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));
		if (!target.isActive()) {
			throw new AppException("Reactivate this teammate before sending an invite", HttpStatus.BAD_REQUEST);
		}
		if (target.getId().equals(actor.getId())) {
			throw new AppException("You're already signed in as this user", HttpStatus.BAD_REQUEST);
		}
		Map<String, Object> invite = passwordResets.invite(actor, target);
		audit.record(actor, "INVITE", "USER", target.getId(), target.getName(), JsonMaps.of("email", target.getEmail()));
		return invite;
	}

	private String unguessable() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}

	@Transactional
	public Map<String, Object> update(UserPrincipal actor, String userId, Map<String, Object> body) {
		UserEntity target = users.findByIdAndCompanyId(userId, actor.getCompanyId())
				.orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));
		if (target.getRole() == Role.SUPER_ADMIN && actor.getRole() != Role.SUPER_ADMIN) {
			throw new AppException("Cannot modify this user", HttpStatus.FORBIDDEN);
		}
		if (target.getId().equals(actor.getId()) && body.containsKey("isActive")
				&& Boolean.FALSE.equals(asBool(body.get("isActive")))) {
			throw new AppException("You cannot deactivate your own account", HttpStatus.BAD_REQUEST);
		}
		if (target.getId().equals(actor.getId()) && body.containsKey("role")
				&& !target.getRole().name().equals(String.valueOf(body.get("role")))) {
			throw new AppException("You cannot change your own role", HttpStatus.BAD_REQUEST);
		}
		Map<String, Object> before = toDto(target);
		if (body.containsKey("name") && body.get("name") != null) {
			target.setName(str(body, "name"));
		}
		if (body.containsKey("role") && body.get("role") != null) {
			target.setRole(assignableRole(actor, String.valueOf(body.get("role"))));
		}
		if (body.containsKey("isActive") && body.get("isActive") != null) {
			target.setActive(asBool(body.get("isActive")));
		}
		boolean passwordReset = body.get("password") != null && !String.valueOf(body.get("password")).isBlank();
		if (passwordReset) {
			target.setPasswordHash(passwordEncoder.encode(password(String.valueOf(body.get("password")))));
		}
		users.save(target);
		Map<String, Object> after = toDto(target);
		Map<String, Object> changes = AuditService.diff(before, after, "name", "role", "isActive");
		if (passwordReset) changes.put("password", JsonMaps.of("from", null, "to", "reset"));
		if (!changes.isEmpty()) {
			audit.record(actor, AuditService.UPDATE, "USER", target.getId(), target.getName(),
					JsonMaps.of("changes", changes));
		}
		return after;
	}

	private static Role assignableRole(UserPrincipal actor, String raw) {
		Role role;
		try {
			role = Role.valueOf(raw.trim());
		} catch (IllegalArgumentException e) {
			throw new AppException("Choose a valid role", HttpStatus.BAD_REQUEST);
		}
		if (role == Role.SUPER_ADMIN && actor.getRole() != Role.SUPER_ADMIN) {
			throw new AppException("Only a super admin can grant the super admin role", HttpStatus.FORBIDDEN);
		}
		return role;
	}

	private static String password(String raw) {
		if (raw.length() < 8) {
			throw new AppException("Password must be at least 8 characters", HttpStatus.BAD_REQUEST);
		}
		return raw;
	}

	private Map<String, Object> toDto(UserEntity user) {
		return JsonMaps.of(
				"id", user.getId(),
				"name", user.getName(),
				"email", user.getEmail(),
				"role", user.getRole().name(),
				"companyId", user.getCompanyId(),
				"isActive", user.isActive(),
				"createdAt", JsonMaps.iso(user.getCreatedAt()),
				"updatedAt", JsonMaps.iso(user.getUpdatedAt()));
	}

	private static String str(Map<String, Object> body, String key) {
		Object v = body.get(key);
		if (v == null || String.valueOf(v).isBlank()) {
			throw new AppException(key + " is required", HttpStatus.BAD_REQUEST);
		}
		return String.valueOf(v).trim();
	}

	private static boolean asBool(Object v) {
		if (v instanceof Boolean b) {
			return b;
		}
		return Boolean.parseBoolean(String.valueOf(v));
	}
}
