package dev.tintwym.novora.modules.integrations.hrms;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.modules.integrations.hrms.EmployeeSyncService.Outcome;
import dev.tintwym.novora.modules.integrations.hrms.EmployeeSyncService.Result;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.UserPrincipal;
import tools.jackson.databind.json.JsonMapper;

/** Links a CRM workspace to its Novora HRMS organization and keeps CRM users in step with HRMS employees. */
@Service
public class HrmsIntegrationService {

	public static final String EVENT_EMPLOYEE_CHANGED = "employee.changed";
	static final Duration PROCESSED_EVENT_RETENTION = Duration.ofDays(30);

	private static final Logger log = LoggerFactory.getLogger(HrmsIntegrationService.class);

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Event(String eventId, String type, String occurredAt, String organizationId, HrmsEmployee employee) {}

	private final HrmsSettings settings;
	private final HrmsClient client;
	private final EmployeeSyncService sync;
	private final NamedParameterJdbcTemplate jdbc;
	private final AuditService audit;
	private final UserEntityRepository users;
	private final TransactionTemplate tx;
	private final JsonMapper jsonMapper;
	private final Set<String> syncing = ConcurrentHashMap.newKeySet();

	public HrmsIntegrationService(HrmsSettings settings, HrmsClient client, EmployeeSyncService sync,
			NamedParameterJdbcTemplate jdbc, AuditService audit, UserEntityRepository users,
			PlatformTransactionManager transactionManager, JsonMapper jsonMapper) {
		this.settings = settings;
		this.client = client;
		this.sync = sync;
		this.jdbc = jdbc;
		this.audit = audit;
		this.users = users;
		this.tx = new TransactionTemplate(transactionManager);
		this.jsonMapper = jsonMapper;
	}

	// ------------------------------------------------------------------------- admin (signed-in CRM admin)

	public Map<String, Object> status(UserPrincipal actor) {
		HrmsConnection c = find(actor.getCompanyId()).orElse(null);
		Map<String, Object> dto = JsonMaps.of("available", settings.isReady(), "connected", c != null);
		if (c == null) return dto;
		dto.put("hrmsOrganizationId", c.hrmsOrgId());
		dto.put("hrmsOrganizationName", c.hrmsOrgName());
		dto.put("connectedAt", JsonMaps.iso(c.connectedAt()));
		dto.put("salesDepartmentCodes", List.copyOf(c.salesCodes()));
		dto.put("supportDepartmentCodes", List.copyOf(c.supportCodes()));
		dto.put("lastSyncAt", JsonMaps.iso(c.lastSyncAt()));
		dto.put("lastSyncStatus", c.lastSyncStatus());
		dto.put("lastSyncMessage", c.lastSyncMessage());
		dto.put("lastEventAt", JsonMaps.iso(c.lastEventAt()));
		dto.put("syncing", syncing.contains(c.companyId()));
		dto.put("linkedUsers", users.countByCompanyIdAndHrmsEmployeeIdIsNotNull(c.companyId()));
		return dto;
	}

	/**
	 * Redeems a one-time code an HR admin created in Novora HRMS. Doesn't import anyone yet, so the admin can
	 * review the department mapping before running the first sync.
	 */
	public Map<String, Object> connect(UserPrincipal actor, String rawCode) {
		requireReady();
		String code = rawCode == null ? "" : rawCode.trim();
		if (code.isEmpty() || code.length() > 20) {
			throw new AppException("Enter the code from Novora HRMS", HttpStatus.BAD_REQUEST);
		}
		if (find(actor.getCompanyId()).isPresent()) {
			throw new AppException("This workspace is already connected. Disconnect it first.", HttpStatus.CONFLICT);
		}
		HrmsClient.Organization org = client.redeem(code, actor.getCompanyId());
		tx.executeWithoutResult(s -> {
			MapSqlParameterSource p = new MapSqlParameterSource("c", actor.getCompanyId())
					.addValue("org", org.organizationId())
					.addValue("name", org.organizationName())
					.addValue("by", actor.getId())
					.addValue("now", Timestamp.from(Instant.now()));
			// HRMS links an organization to one CRM workspace at a time; a newer link replaces an older one.
			jdbc.update("delete from hrms_connections where hrms_org_id = :org and company_id <> :c", p);
			jdbc.update("""
					insert into hrms_connections (company_id, hrms_org_id, hrms_org_name, connected_at, connected_by)
					values (:c, :org, :name, :now, :by)
					""", p);
			audit.record(actor, "HRMS_CONNECTED", "WORKSPACE", actor.getCompanyId(), org.organizationName(),
					JsonMaps.of("hrmsOrganizationId", org.organizationId()));
		});
		return status(actor);
	}

	/** Stops syncing. CRM users keep their accounts and their link, so reconnecting the same HRMS is seamless. */
	public Map<String, Object> disconnect(UserPrincipal actor) {
		tx.executeWithoutResult(s -> {
			HrmsConnection c = find(actor.getCompanyId())
					.orElseThrow(() -> new AppException("This workspace isn't connected to Novora HRMS", HttpStatus.NOT_FOUND));
			jdbc.update("delete from hrms_connections where company_id = :c",
					new MapSqlParameterSource("c", actor.getCompanyId()));
			audit.record(actor, "HRMS_DISCONNECTED", "WORKSPACE", actor.getCompanyId(), c.hrmsOrgName(),
					JsonMaps.of("hrmsOrganizationId", c.hrmsOrgId()));
		});
		return status(actor);
	}

	public Map<String, Object> updateSettings(UserPrincipal actor, Map<String, Object> body) {
		tx.executeWithoutResult(s -> {
			HrmsConnection c = find(actor.getCompanyId())
					.orElseThrow(() -> new AppException("This workspace isn't connected to Novora HRMS", HttpStatus.NOT_FOUND));
			Set<String> sales = body.containsKey("salesDepartmentCodes")
					? codes(body.get("salesDepartmentCodes"), "Sales") : c.salesCodes();
			Set<String> support = body.containsKey("supportDepartmentCodes")
					? codes(body.get("supportDepartmentCodes"), "Support") : c.supportCodes();
			for (String code : sales) {
				if (support.contains(code)) {
					throw new AppException("Department " + code + " can't be both sales and support", HttpStatus.BAD_REQUEST);
				}
			}
			jdbc.update("""
					update hrms_connections set sales_department_codes = :sales, support_department_codes = :support
					where company_id = :c
					""", new MapSqlParameterSource("c", actor.getCompanyId())
					.addValue("sales", String.join(",", sales))
					.addValue("support", String.join(",", support)));
			audit.record(actor, AuditService.UPDATE, "WORKSPACE", actor.getCompanyId(), c.hrmsOrgName(),
					JsonMaps.of("changes", AuditService.diff(
							JsonMaps.of("hrmsSalesDepartments", String.join(", ", c.salesCodes()),
									"hrmsSupportDepartments", String.join(", ", c.supportCodes())),
							JsonMaps.of("hrmsSalesDepartments", String.join(", ", sales),
									"hrmsSupportDepartments", String.join(", ", support)),
							"hrmsSalesDepartments", "hrmsSupportDepartments")));
		});
		return status(actor);
	}

	public Map<String, Object> syncNow(UserPrincipal actor) {
		requireReady();
		Map<String, Object> summary = reconcile(actor.getCompanyId());
		Map<String, Object> dto = status(actor);
		dto.put("summary", summary);
		return dto;
	}

	// -------------------------------------------------------------------------------------------- reconcile

	/**
	 * Pulls the full HRMS roster and applies every employee. This catches anything a missed event left behind.
	 * People missing from the roster are left alone: only an explicit HRMS status removes access.
	 */
	public Map<String, Object> reconcile(String companyId) {
		HrmsConnection c = find(companyId)
				.orElseThrow(() -> new AppException("This workspace isn't connected to Novora HRMS", HttpStatus.NOT_FOUND));
		if (!syncing.add(companyId)) {
			throw new AppException("A sync is already running. Try again in a minute.", HttpStatus.CONFLICT);
		}
		try {
			Instant asOf = Instant.now();
			List<HrmsEmployee> roster;
			try {
				roster = client.employees(c.hrmsOrgId(), companyId);
			} catch (AppException ex) {
				recordSync(companyId, "FAILED", ex.getMessage());
				throw ex;
			}
			if (roster.isEmpty()) {
				String message = "Novora HRMS returned no employees, so nothing was changed.";
				recordSync(companyId, "FAILED", message);
				return JsonMaps.of("status", "FAILED", "message", message);
			}
			Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
			List<String> notes = new ArrayList<>();
			int errors = 0;
			for (HrmsEmployee employee : roster) {
				if (employee.organizationId() != null && !employee.organizationId().equals(c.hrmsOrgId())) continue;
				try {
					Result r = sync.apply(c, employee, asOf);
					counts.merge(r.outcome(), 1, Integer::sum);
					if (r.note() != null && r.outcome() != Outcome.SKIPPED && notes.size() < 10) {
						notes.add(employee.fullName() + ": " + r.note());
					}
				} catch (RuntimeException ex) {
					errors++;
					log.warn("HRMS sync of employee {} for company {} failed", employee.employeeId(), companyId, ex);
				}
			}
			String status = errors > 0 ? "PARTIAL" : "OK";
			String message = describe(counts, errors);
			recordSync(companyId, status, message);
			Map<String, Object> summary = JsonMaps.of("status", status, "message", message, "notes", notes);
			for (Outcome o : Outcome.values()) summary.put(o.name().toLowerCase(), counts.getOrDefault(o, 0));
			summary.put("errors", errors);
			return summary;
		} finally {
			syncing.remove(companyId);
		}
	}

	public List<String> connectedCompanyIds() {
		return jdbc.queryForList("select company_id from hrms_connections order by connected_at",
				new MapSqlParameterSource(), String.class);
	}

	public void pruneProcessedEvents() {
		jdbc.update("delete from hrms_processed_events where processed_at < :before",
				new MapSqlParameterSource("before", Timestamp.from(Instant.now().minus(PROCESSED_EVENT_RETENTION))));
	}

	// ------------------------------------------------------------------- events (signed request from HRMS)

	/**
	 * Applies one delivered event. Returns normally whenever HRMS should stop retrying, including for duplicates
	 * and for organizations this CRM no longer links to; throws only when a retry could succeed.
	 */
	public Map<String, Object> handleEvent(String body) {
		Event event;
		try {
			event = jsonMapper.readValue(body, Event.class);
		} catch (RuntimeException e) {
			throw new AppException("Malformed event", HttpStatus.BAD_REQUEST);
		}
		if (event.eventId() == null || event.eventId().isBlank() || event.eventId().length() > 64) {
			throw new AppException("Malformed event", HttpStatus.BAD_REQUEST);
		}
		if (!EVENT_EMPLOYEE_CHANGED.equals(event.type()) || event.employee() == null) {
			return JsonMaps.of("status", "ignored");
		}
		HrmsConnection c = findByOrganization(event.organizationId()).orElse(null);
		if (c == null) return JsonMaps.of("status", "ignored", "reason", "not linked");
		Instant occurredAt = parseInstant(event.occurredAt());

		return tx.execute(s -> {
			int fresh = jdbc.update("""
					insert into hrms_processed_events (event_id, company_id, processed_at) values (:e, :c, now())
					on conflict (event_id) do nothing
					""", new MapSqlParameterSource("e", event.eventId()).addValue("c", c.companyId()));
			if (fresh == 0) return JsonMaps.of("status", "duplicate");
			Result r = sync.apply(c, event.employee(), occurredAt);
			jdbc.update("update hrms_connections set last_event_at = :now where company_id = :c",
					new MapSqlParameterSource("c", c.companyId()).addValue("now", Timestamp.from(Instant.now())));
			return JsonMaps.of("status", r.outcome().name().toLowerCase());
		});
	}

	// ---------------------------------------------------------------------------------------------- helpers

	public Optional<HrmsConnection> find(String companyId) {
		return jdbc.query("select * from hrms_connections where company_id = :c",
				new MapSqlParameterSource("c", companyId), this::row).stream().findFirst();
	}

	private Optional<HrmsConnection> findByOrganization(String hrmsOrgId) {
		if (hrmsOrgId == null || hrmsOrgId.isBlank()) return Optional.empty();
		return jdbc.query("select * from hrms_connections where hrms_org_id = :o",
				new MapSqlParameterSource("o", hrmsOrgId), this::row).stream().findFirst();
	}

	private void recordSync(String companyId, String status, String message) {
		jdbc.update("""
				update hrms_connections set last_sync_at = :now, last_sync_status = :s, last_sync_message = :m
				where company_id = :c
				""", new MapSqlParameterSource("c", companyId).addValue("s", status)
				.addValue("now", Timestamp.from(Instant.now()))
				.addValue("m", message != null && message.length() > 500 ? message.substring(0, 500) : message));
	}

	private void requireReady() {
		if (!settings.isReady()) {
			throw new AppException("The HRMS connection isn't set up on this server yet", HttpStatus.SERVICE_UNAVAILABLE);
		}
	}

	private static Set<String> codes(Object raw, String label) {
		Collection<?> items;
		if (raw instanceof Collection<?> list) {
			items = list;
		} else if (raw instanceof String s) {
			items = List.of(s.split(","));
		} else if (raw == null) {
			items = List.of();
		} else {
			throw new AppException(label + " departments must be a list of codes", HttpStatus.BAD_REQUEST);
		}
		Set<String> codes = new LinkedHashSet<>();
		for (Object item : items) {
			String text = item == null ? "" : String.valueOf(item).trim();
			if (text.isEmpty()) continue;
			String code = HrmsRoleMapper.normalize(text);
			if (code == null) {
				throw new AppException("\"" + text + "\" isn't a valid department code. Use letters, numbers, - or _ (up to "
						+ HrmsRoleMapper.MAX_CODE_LENGTH + " characters).", HttpStatus.BAD_REQUEST);
			}
			codes.add(code);
		}
		if (codes.size() > HrmsRoleMapper.MAX_CODES) {
			throw new AppException("Use at most " + HrmsRoleMapper.MAX_CODES + " " + label.toLowerCase()
					+ " department codes", HttpStatus.BAD_REQUEST);
		}
		return codes;
	}

	private static Instant parseInstant(String raw) {
		if (raw == null) return Instant.now();
		try {
			return Instant.parse(raw);
		} catch (DateTimeParseException e) {
			return Instant.now();
		}
	}

	private static String describe(Map<Outcome, Integer> counts, int errors) {
		List<String> parts = new ArrayList<>();
		add(parts, counts.get(Outcome.CREATED), "added and invited");
		add(parts, counts.get(Outcome.LINKED), "matched to existing accounts");
		add(parts, counts.get(Outcome.UPDATED), "updated");
		add(parts, counts.get(Outcome.REACTIVATED), "reactivated");
		add(parts, counts.get(Outcome.DISABLED), "deactivated");
		add(parts, counts.get(Outcome.UNCHANGED), "already up to date");
		add(parts, counts.get(Outcome.SKIPPED), "without CRM access");
		add(parts, errors, "failed");
		return parts.isEmpty() ? "Nothing to change." : String.join(", ", parts) + ".";
	}

	private static void add(List<String> parts, Integer count, String label) {
		if (count != null && count > 0) parts.add(count + " " + label);
	}

	private HrmsConnection row(ResultSet rs, int i) throws SQLException {
		return new HrmsConnection(
				rs.getString("company_id"),
				rs.getString("hrms_org_id"),
				rs.getString("hrms_org_name"),
				HrmsRoleMapper.parseCodes(rs.getString("sales_department_codes")),
				HrmsRoleMapper.parseCodes(rs.getString("support_department_codes")),
				instant(rs.getTimestamp("connected_at")),
				instant(rs.getTimestamp("last_sync_at")),
				rs.getString("last_sync_status"),
				rs.getString("last_sync_message"),
				instant(rs.getTimestamp("last_event_at")));
	}

	private static Instant instant(Timestamp ts) {
		return ts == null ? null : ts.toInstant();
	}
}
