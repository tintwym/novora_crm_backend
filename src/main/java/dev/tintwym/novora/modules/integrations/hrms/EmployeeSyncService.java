package dev.tintwym.novora.modules.integrations.hrms;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.EmailService;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.domain.entity.UserEntity;
import dev.tintwym.novora.domain.enums.Role;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.modules.auth.PasswordResetService;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.UserPrincipal;

/**
 * Applies one HRMS employee snapshot to the CRM: creates and invites new sales/support staff, keeps names,
 * emails and roles current, and removes access for people who left (handing their open work to someone else).
 *
 * <p>Admins are never demoted or given a new role automatically, and the last active admin of a workspace is never
 * deactivated. Moving out of sales/support only removes access from accounts the sync created; admins and accounts
 * created by hand lose access only when the person leaves the company. An account an admin deactivated by hand is
 * never re-enabled by the sync.
 */
@Service
public class EmployeeSyncService {

	public static final String ACTOR_NAME = "HRMS sync";
	static final String INVITER_NAME = "Your HR team";

	public enum Outcome { CREATED, LINKED, UPDATED, DISABLED, REACTIVATED, UNCHANGED, SKIPPED, STALE }

	public record Result(Outcome outcome, String userId, String note) {}

	private final SecureRandom random = new SecureRandom();
	private final UserEntityRepository users;
	private final NamedParameterJdbcTemplate jdbc;
	private final PasswordEncoder passwordEncoder;
	private final PasswordResetService passwordResets;
	private final AuditService audit;

	public EmployeeSyncService(UserEntityRepository users, NamedParameterJdbcTemplate jdbc,
			PasswordEncoder passwordEncoder, PasswordResetService passwordResets, AuditService audit) {
		this.users = users;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.passwordResets = passwordResets;
		this.audit = audit;
	}

	/**
	 * @param asOf when HRMS produced this snapshot; a snapshot older than the last one applied is ignored, so
	 *             a delayed event can't undo a newer change
	 */
	@Transactional
	public Result apply(HrmsConnection connection, HrmsEmployee employee, Instant asOf) {
		if (employee == null || employee.employeeId() == null || employee.employeeId().isBlank()) {
			return new Result(Outcome.SKIPPED, null, "missing employee id");
		}
		String companyId = connection.companyId();
		UserEntity user = users.findByCompanyIdAndHrmsEmployeeId(companyId, employee.employeeId()).orElse(null);
		if (user != null && user.getHrmsSyncedAt() != null && asOf.isBefore(user.getHrmsSyncedAt())) {
			return new Result(Outcome.STALE, user.getId(), null);
		}
		Optional<Role> mapped = HrmsRoleMapper.roleFor(employee, connection.salesCodes(), connection.supportCodes());
		UserPrincipal actor = systemActor(companyId);

		boolean linked = false;
		if (user == null) {
			if (!employee.isEmployed()) return new Result(Outcome.SKIPPED, null, "not currently employed");
			String email = normalizeEmail(employee.email());
			if (email == null) return new Result(Outcome.SKIPPED, null, "no valid email address in HRMS");
			UserEntity existing = users.findByEmailIgnoreCase(email).orElse(null);
			if (existing == null) {
				if (mapped.isEmpty()) return new Result(Outcome.SKIPPED, null, "not in a sales or support department");
				return create(companyId, employee, mapped.get(), email, asOf, actor);
			}
			// Existing accounts are linked whatever the department, so they still lose access when the person leaves.
			if (!companyId.equals(existing.getCompanyId())) {
				return new Result(Outcome.SKIPPED, null, email + " already belongs to another workspace");
			}
			if (existing.getHrmsEmployeeId() != null) {
				return new Result(Outcome.SKIPPED, existing.getId(), email + " is linked to a different HRMS employee");
			}
			user = existing;
			user.setHrmsEmployeeId(employee.employeeId());
			linked = true;
		}
		return update(user, employee, mapped, linked, asOf, actor);
	}

	private Result create(String companyId, HrmsEmployee employee, Role role, String email, Instant asOf,
			UserPrincipal actor) {
		UserEntity user = new UserEntity();
		user.setName(employee.fullName().isEmpty() ? email : employee.fullName());
		user.setEmail(email);
		user.setPasswordHash(passwordEncoder.encode(unguessable()));
		user.setRole(role);
		user.setCompanyId(companyId);
		user.setHrmsEmployeeId(employee.employeeId());
		user.setHrmsSyncedAt(asOf);
		user.setHrmsProvisioned(true);
		users.saveAndFlush(user);
		audit.record(actor, AuditService.CREATE, "USER", user.getId(), user.getName(),
				JsonMaps.of("email", email, "role", role.name(), "source", "HRMS", "invited", true));
		passwordResets.invite(INVITER_NAME, user);
		return new Result(Outcome.CREATED, user.getId(), null);
	}

	private Result update(UserEntity user, HrmsEmployee employee, Optional<Role> mapped, boolean linked,
			Instant asOf, UserPrincipal actor) {
		Map<String, Object> before = snapshot(user);
		boolean admin = isAdmin(user.getRole());
		Outcome outcome = linked ? Outcome.LINKED : Outcome.UNCHANGED;
		String note = null;

		String name = employee.fullName();
		if (!name.isEmpty() && !name.equals(user.getName())) user.setName(name);
		String email = normalizeEmail(employee.email());
		if (email != null && !email.equalsIgnoreCase(user.getEmail())) {
			if (users.existsByEmailIgnoreCase(email)) {
				note = "kept the current email because " + email + " is already in use";
			} else {
				user.setEmail(email);
			}
		}

		Map<String, Object> reassigned = null;
		boolean keepsAccess = employee.isEmployed() && (mapped.isPresent() || admin || !user.isHrmsProvisioned());
		if (!keepsAccess) {
			if (user.isActive()) {
				if (admin && isLastActiveAdmin(user)) {
					note = "kept active because this is the workspace's last admin";
				} else {
					deactivate(user);
					reassigned = reassignWork(user, employee);
					outcome = Outcome.DISABLED;
				}
			}
		} else {
			if (!admin && mapped.isPresent() && user.getRole() != mapped.get()) user.setRole(mapped.get());
			if (!user.isActive() && user.isHrmsDeactivated()) {
				user.setActive(true);
				user.setHrmsDeactivated(false);
				outcome = Outcome.REACTIVATED;
			}
		}

		user.setHrmsSyncedAt(asOf);
		users.save(user);

		Map<String, Object> changes = AuditService.diff(before, snapshot(user), "name", "email", "role", "isActive");
		if (outcome == Outcome.UNCHANGED && !changes.isEmpty()) outcome = Outcome.UPDATED;
		if (!changes.isEmpty() || linked) {
			Map<String, Object> details = JsonMaps.of("changes", changes, "source", "HRMS");
			if (linked) details.put("linkedToHrms", true);
			if (reassigned != null) details.put("reassigned", reassigned);
			audit.record(actor, AuditService.UPDATE, "USER", user.getId(), user.getName(), details);
		}
		return new Result(outcome, user.getId(), note);
	}

	private void deactivate(UserEntity user) {
		user.setActive(false);
		user.setHrmsDeactivated(true);
		MapSqlParameterSource u = new MapSqlParameterSource("u", user.getId());
		jdbc.update("update refresh_tokens set revoked_at = now() where user_id = :u and revoked_at is null", u);
		jdbc.update("update password_reset_tokens set used_at = now() where user_id = :u and used_at is null", u);
	}

	/**
	 * Hands a leaver's open deals and tickets, and all their contacts and companies, to their manager. Falls back
	 * to the department manager, then to the longest-standing active admin, so nothing is left without an owner.
	 */
	private Map<String, Object> reassignWork(UserEntity leaver, HrmsEmployee employee) {
		String companyId = leaver.getCompanyId();
		String to = activeUserFor(companyId, employee.managerEmployeeId(), leaver.getId())
				.or(() -> activeUserFor(companyId, employee.departmentManagerEmployeeId(), leaver.getId()))
				.or(() -> oldestActiveAdmin(companyId, leaver.getId()))
				.orElse(null);
		if (to == null) return JsonMaps.of("to", null);
		MapSqlParameterSource p = new MapSqlParameterSource("c", companyId).addValue("from", leaver.getId())
				.addValue("to", to);
		int deals = jdbc.update("""
				update deals set owner_id = :to, updated_at = now()
				where company_id = :c and owner_id = :from and stage not in ('WON', 'LOST')
				""", p);
		int tickets = jdbc.update("""
				update tickets set assignee_id = :to, updated_at = now()
				where company_id = :c and assignee_id = :from and status not in ('RESOLVED', 'CLOSED')
				""", p);
		int contacts = jdbc.update(
				"update contacts set owner_id = :to, updated_at = now() where company_id = :c and owner_id = :from", p);
		int accounts = jdbc.update(
				"update accounts set owner_id = :to, updated_at = now() where company_id = :c and owner_id = :from", p);
		String toName = users.findById(to).map(UserEntity::getName).orElse(null);
		return JsonMaps.of("to", toName, "toUserId", to, "deals", deals, "tickets", tickets, "contacts", contacts,
				"companies", accounts);
	}

	private Optional<String> activeUserFor(String companyId, String hrmsEmployeeId, String exclude) {
		if (hrmsEmployeeId == null || hrmsEmployeeId.isBlank()) return Optional.empty();
		List<String> ids = jdbc.queryForList("""
				select id from users
				where company_id = :c and hrms_employee_id = :e and is_active and id <> :self
				""", new MapSqlParameterSource("c", companyId).addValue("e", hrmsEmployeeId).addValue("self", exclude),
				String.class);
		return ids.stream().findFirst();
	}

	private Optional<String> oldestActiveAdmin(String companyId, String exclude) {
		List<String> ids = jdbc.queryForList("""
				select id from users
				where company_id = :c and is_active and role in ('ADMIN', 'SUPER_ADMIN') and id <> :self
				order by created_at asc, id asc limit 1
				""", new MapSqlParameterSource("c", companyId).addValue("self", exclude), String.class);
		return ids.stream().findFirst();
	}

	private boolean isLastActiveAdmin(UserEntity user) {
		return oldestActiveAdmin(user.getCompanyId(), user.getId()).isEmpty();
	}

	private static boolean isAdmin(Role role) {
		return role == Role.ADMIN || role == Role.SUPER_ADMIN;
	}

	private static Map<String, Object> snapshot(UserEntity user) {
		return JsonMaps.of("name", user.getName(), "email", user.getEmail(), "role", user.getRole().name(),
				"isActive", user.isActive());
	}

	static String normalizeEmail(String raw) {
		if (raw == null) return null;
		String email = raw.trim().toLowerCase(Locale.ROOT);
		return EmailService.isValidAddress(email) ? email : null;
	}

	/** Who audit entries written by the sync are attributed to. There is no matching user row. */
	static UserPrincipal systemActor(String companyId) {
		return new UserPrincipal(null, null, ACTOR_NAME, "", Role.ADMIN, companyId, true);
	}

	private String unguessable() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}
}
