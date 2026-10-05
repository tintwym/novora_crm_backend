package dev.tintwym.novora.modules.automations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.Cuid;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.domain.entity.Activity;
import dev.tintwym.novora.domain.entity.Deal;
import dev.tintwym.novora.domain.enums.ActivityType;
import dev.tintwym.novora.domain.enums.DealStage;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.repositories.ActivityRepository;
import dev.tintwym.novora.repositories.ContactRepository;
import dev.tintwym.novora.repositories.CrmAccountRepository;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.UserPrincipal;

@Service
public class AutomationService {

	public static final int MAX_RULES = 50;

	private static final Set<String> TASK_TYPES = Set.of("TASK", "CALL", "MEETING", "EMAIL");
	private static final Set<String> ASSIGNEES = Set.of("DEAL_OWNER", "ACTOR");

	private static final String SELECT = """
			select r.id, r.name, r.trigger_stage, r.task_type, r.task_subject, r.task_note, r.due_in_days,
			  r.assign_to, r.is_active, r.run_count, r.last_run_at, r.created_at, r.updated_at,
			  (select count(*) from activities a where a.automation_rule_id = r.id and a.completed_at is null) as open_tasks
			from automation_rules r
			""";

	private final NamedParameterJdbcTemplate jdbc;
	private final ActivityRepository activities;
	private final ContactRepository contacts;
	private final CrmAccountRepository accounts;
	private final UserEntityRepository users;
	private final AuditService audit;

	private static final String[] AUDIT_FIELDS = { "name", "triggerStage", "taskType", "taskSubject", "taskNote",
			"dueInDays", "assignTo", "isActive" };

	public AutomationService(NamedParameterJdbcTemplate jdbc, ActivityRepository activities,
			ContactRepository contacts, CrmAccountRepository accounts, UserEntityRepository users,
			AuditService audit) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.activities = activities;
		this.contacts = contacts;
		this.accounts = accounts;
		this.users = users;
	}

	private record Rule(String id, String name, String taskType, String subject, String note, int dueInDays,
			String assignTo) {}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UserPrincipal user) {
		return jdbc.query(SELECT + " where r.company_id = :c order by r.is_active desc, r.created_at",
				new MapSqlParameterSource("c", user.getCompanyId()), this::row);
	}

	@Transactional
	public Map<String, Object> create(UserPrincipal user, Map<String, Object> body) {
		Integer count = jdbc.queryForObject("select count(*) from automation_rules where company_id = :c",
				new MapSqlParameterSource("c", user.getCompanyId()), Integer.class);
		if (count != null && count >= MAX_RULES) {
			throw new AppException("You can have up to " + MAX_RULES + " automation rules", HttpStatus.BAD_REQUEST);
		}
		String id = Cuid.generate();
		jdbc.update("""
				insert into automation_rules (id, company_id, name, trigger_stage, task_type, task_subject, task_note,
				  due_in_days, assign_to, is_active, created_by, created_at, updated_at)
				values (:id, :c, :name, cast(:stage as "DealStage"), cast(:type as "ActivityType"), :subject, :note,
				  :days, :assignTo, :active, :by, now(), now())
				""", new MapSqlParameterSource()
				.addValue("id", id)
				.addValue("c", user.getCompanyId())
				.addValue("name", text(body.get("name"), "Rule name", 120, true))
				.addValue("stage", stage(body.get("triggerStage")))
				.addValue("type", taskType(body.get("taskType")))
				.addValue("subject", text(body.get("taskSubject"), "Task title", 200, true))
				.addValue("note", text(body.get("taskNote"), "Task note", 2000, false))
				.addValue("days", days(body.get("dueInDays")))
				.addValue("assignTo", assignTo(body.get("assignTo")))
				.addValue("active", body.get("isActive") == null || bool(body.get("isActive")))
				.addValue("by", user.getId()));
		Map<String, Object> created = find(user.getCompanyId(), id);
		audit.created(user, "AUTOMATION", id, String.valueOf(created.get("name")));
		return created;
	}

	@Transactional
	public Map<String, Object> update(UserPrincipal user, String id, Map<String, Object> body) {
		Map<String, Object> before = find(user.getCompanyId(), id);
		MapSqlParameterSource p = new MapSqlParameterSource("id", id).addValue("c", user.getCompanyId());
		StringBuilder sets = new StringBuilder("updated_at = now()");
		if (body.containsKey("name")) {
			sets.append(", name = :name");
			p.addValue("name", text(body.get("name"), "Rule name", 120, true));
		}
		if (body.containsKey("triggerStage")) {
			sets.append(", trigger_stage = cast(:stage as \"DealStage\")");
			p.addValue("stage", stage(body.get("triggerStage")));
		}
		if (body.containsKey("taskType")) {
			sets.append(", task_type = cast(:type as \"ActivityType\")");
			p.addValue("type", taskType(body.get("taskType")));
		}
		if (body.containsKey("taskSubject")) {
			sets.append(", task_subject = :subject");
			p.addValue("subject", text(body.get("taskSubject"), "Task title", 200, true));
		}
		if (body.containsKey("taskNote")) {
			sets.append(", task_note = :note");
			p.addValue("note", text(body.get("taskNote"), "Task note", 2000, false));
		}
		if (body.containsKey("dueInDays")) {
			sets.append(", due_in_days = :days");
			p.addValue("days", days(body.get("dueInDays")));
		}
		if (body.containsKey("assignTo")) {
			sets.append(", assign_to = :assignTo");
			p.addValue("assignTo", assignTo(body.get("assignTo")));
		}
		if (body.containsKey("isActive")) {
			sets.append(", is_active = :active");
			p.addValue("active", bool(body.get("isActive")));
		}
		jdbc.update("update automation_rules set " + sets + " where id = :id and company_id = :c", p);
		Map<String, Object> after = find(user.getCompanyId(), id);
		audit.updated(user, "AUTOMATION", id, String.valueOf(after.get("name")), before, after, AUDIT_FIELDS);
		return after;
	}

	@Transactional
	public void delete(UserPrincipal user, String id) {
		Map<String, Object> existing = find(user.getCompanyId(), id);
		jdbc.update("delete from automation_rules where id = :id and company_id = :c",
				new MapSqlParameterSource("id", id).addValue("c", user.getCompanyId()));
		audit.deleted(user, "AUTOMATION", id, String.valueOf(existing.get("name")));
	}

	/**
	 * Runs the active rules for a deal that was just created in, or moved into, {@code stage}. Must be called
	 * inside the caller's transaction so the tasks roll back together with the deal change.
	 */
	@Transactional
	public List<Map<String, Object>> onStageEntered(UserPrincipal actor, Deal deal, DealStage stage) {
		MapSqlParameterSource p = new MapSqlParameterSource("c", deal.getCompanyId())
				.addValue("stage", stage.name())
				.addValue("deal", deal.getId());
		List<Rule> rules = jdbc.query("""
				select r.id, r.name, r.task_type, r.task_subject, r.task_note, r.due_in_days, r.assign_to
				from automation_rules r
				where r.company_id = :c and r.is_active
				  and (r.trigger_stage is null or r.trigger_stage = cast(:stage as "DealStage"))
				  and not exists (select 1 from activities a
				    where a.automation_rule_id = r.id and a.deal_id = :deal and a.completed_at is null)
				order by r.created_at
				""", p, (rs, i) -> new Rule(rs.getString("id"), rs.getString("name"), rs.getString("task_type"),
				rs.getString("task_subject"), rs.getString("task_note"), rs.getInt("due_in_days"),
				rs.getString("assign_to")));
		if (rules.isEmpty()) return List.of();

		String contactName = deal.getContactId() == null ? null
				: contacts.findById(deal.getContactId()).map(c -> c.getName()).orElse(null);
		String accountName = deal.getAccountId() == null ? null
				: accounts.findById(deal.getAccountId()).map(a -> a.getName()).orElse(null);
		String ownerId = deal.getOwnerId() != null
				&& users.findByIdAndCompanyId(deal.getOwnerId(), deal.getCompanyId()).isPresent()
						? deal.getOwnerId()
						: actor.getId();

		Instant now = Instant.now();
		List<Map<String, Object>> created = new ArrayList<>();
		for (Rule rule : rules) {
			Activity a = new Activity();
			a.setType(ActivityType.valueOf(rule.taskType()));
			a.setSubject(fill(rule.subject(), deal, stage, contactName, accountName, 200));
			a.setNote(rule.note() == null ? null : fill(rule.note(), deal, stage, contactName, accountName, 2000));
			a.setDueAt(now.plus(rule.dueInDays(), ChronoUnit.DAYS));
			a.setDate(now);
			a.setCompanyId(deal.getCompanyId());
			a.setUserId("ACTOR".equals(rule.assignTo()) ? actor.getId() : ownerId);
			a.setDealId(deal.getId());
			a.setContactId(deal.getContactId());
			a.setAutomationRuleId(rule.id());
			activities.save(a);
			created.add(JsonMaps.of("id", a.getId(), "subject", a.getSubject(), "rule", rule.name()));
		}
		jdbc.update("update automation_rules set run_count = run_count + 1, last_run_at = now() where id in (:ids)",
				new MapSqlParameterSource("ids", rules.stream().map(Rule::id).toList()));
		return created;
	}

	public static String stageLabel(DealStage stage) {
		String n = stage.name().toLowerCase(Locale.ROOT);
		return Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}

	private static String fill(String template, Deal deal, DealStage stage, String contact, String account, int max) {
		String out = template
				.replace("{deal}", deal.getTitle() == null ? "" : deal.getTitle())
				.replace("{stage}", stageLabel(stage))
				.replace("{contact}", contact == null ? "the contact" : contact)
				.replace("{company}", account == null ? "the company" : account)
				.trim();
		if (out.isEmpty()) out = "Follow up";
		return out.length() > max ? out.substring(0, max) : out;
	}

	private Map<String, Object> find(String companyId, String id) {
		return jdbc.query(SELECT + " where r.id = :id and r.company_id = :c",
				new MapSqlParameterSource("id", id).addValue("c", companyId), this::row)
				.stream().findFirst()
				.orElseThrow(() -> new AppException("Automation rule not found", HttpStatus.NOT_FOUND));
	}

	private Map<String, Object> row(ResultSet rs, int i) throws SQLException {
		return JsonMaps.of(
				"id", rs.getString("id"),
				"name", rs.getString("name"),
				"triggerStage", rs.getString("trigger_stage"),
				"taskType", rs.getString("task_type"),
				"taskSubject", rs.getString("task_subject"),
				"taskNote", rs.getString("task_note"),
				"dueInDays", rs.getInt("due_in_days"),
				"assignTo", rs.getString("assign_to"),
				"isActive", rs.getBoolean("is_active"),
				"runCount", rs.getInt("run_count"),
				"openTasks", rs.getLong("open_tasks"),
				"lastRunAt", iso(rs.getTimestamp("last_run_at")),
				"createdAt", iso(rs.getTimestamp("created_at")),
				"updatedAt", iso(rs.getTimestamp("updated_at")));
	}

	private static String iso(Timestamp ts) {
		return ts == null ? null : JsonMaps.iso(ts.toInstant());
	}

	private static String text(Object v, String label, int max, boolean required) {
		String s = v == null ? null : String.valueOf(v).trim();
		if (s == null || s.isEmpty() || "null".equals(s)) {
			if (required) throw new AppException(label + " is required", HttpStatus.BAD_REQUEST);
			return null;
		}
		if (s.length() > max) {
			throw new AppException(label + " must be " + max + " characters or fewer", HttpStatus.BAD_REQUEST);
		}
		return s;
	}

	private static String stage(Object v) {
		String s = v == null ? "" : String.valueOf(v).trim().toUpperCase(Locale.ROOT);
		if (s.isEmpty() || "ANY".equals(s) || "NULL".equals(s)) return null;
		try {
			return DealStage.valueOf(s).name();
		} catch (IllegalArgumentException e) {
			throw new AppException("Choose a valid deal stage", HttpStatus.BAD_REQUEST);
		}
	}

	private static String taskType(Object v) {
		String s = v == null ? "TASK" : String.valueOf(v).trim().toUpperCase(Locale.ROOT);
		if (s.isEmpty()) return "TASK";
		if (!TASK_TYPES.contains(s)) {
			throw new AppException("Task type must be a task, call, meeting or email", HttpStatus.BAD_REQUEST);
		}
		return s;
	}

	private static String assignTo(Object v) {
		String s = v == null ? "DEAL_OWNER" : String.valueOf(v).trim().toUpperCase(Locale.ROOT);
		if (s.isEmpty()) return "DEAL_OWNER";
		if (!ASSIGNEES.contains(s)) {
			throw new AppException("Assign the task to the deal owner or the person who moved the deal",
					HttpStatus.BAD_REQUEST);
		}
		return s;
	}

	private static int days(Object v) {
		if (v == null || String.valueOf(v).isBlank()) return 1;
		int d;
		try {
			double raw = v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).trim());
			if (raw != Math.floor(raw) || Double.isInfinite(raw)) throw new NumberFormatException();
			d = (int) raw;
		} catch (NumberFormatException e) {
			throw new AppException("Due in days must be a whole number", HttpStatus.BAD_REQUEST);
		}
		if (d < 0 || d > 365) throw new AppException("Due in days must be between 0 and 365", HttpStatus.BAD_REQUEST);
		return d;
	}

	private static boolean bool(Object v) {
		return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
	}
}
