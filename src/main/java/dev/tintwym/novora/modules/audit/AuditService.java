package dev.tintwym.novora.modules.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.Cuid;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.security.UserPrincipal;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records who changed what. Calls join the caller's transaction, so an entry only exists if the change it
 * describes was committed.
 */
@Service
public class AuditService {

	public static final String CREATE = "CREATE";
	public static final String UPDATE = "UPDATE";
	public static final String DELETE = "DELETE";

	public static final Set<String> ENTITY_TYPES = Set.of("CONTACT", "COMPANY", "DEAL", "QUOTE", "TICKET",
			"PRODUCT", "USER", "WORKSPACE", "AUTOMATION", "ATTACHMENT", "SAMPLE_DATA");

	private static final int PAGE_SIZE = 50;
	private static final int MAX_TEXT = 300;
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

	private final NamedParameterJdbcTemplate jdbc;

	public AuditService(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public void record(UserPrincipal actor, String action, String entityType, String entityId, String label,
			Map<String, Object> details) {
		String json = null;
		if (details != null && !details.isEmpty()) {
			try {
				json = MAPPER.writeValueAsString(details);
			} catch (Exception e) {
				json = null;
			}
		}
		jdbc.update("""
				insert into audit_logs (id, company_id, user_id, user_name, action, entity_type, entity_id,
				  entity_label, details, created_at)
				values (:id, :c, :u, :uname, :action, :type, :eid, :label, cast(:details as jsonb), now())
				""", new MapSqlParameterSource()
				.addValue("id", Cuid.generate())
				.addValue("c", actor.getCompanyId())
				.addValue("u", actor.getId())
				.addValue("uname", actor.getName())
				.addValue("action", action)
				.addValue("type", entityType)
				.addValue("eid", entityId)
				.addValue("label", clip(label))
				.addValue("details", json));
	}

	public void created(UserPrincipal actor, String entityType, String entityId, String label) {
		record(actor, CREATE, entityType, entityId, label, null);
	}

	public void deleted(UserPrincipal actor, String entityType, String entityId, String label) {
		record(actor, DELETE, entityType, entityId, label, null);
	}

	/**
	 * Logs an update listing only the fields whose value changed between two DTO snapshots. Nested objects
	 * such as {@code owner} are compared by their {@code name}. Nothing is written when nothing changed.
	 */
	public void updated(UserPrincipal actor, String entityType, String entityId, String label,
			Map<String, Object> before, Map<String, Object> after, String... fields) {
		Map<String, Object> changes = diff(before, after, fields);
		if (!changes.isEmpty()) {
			record(actor, UPDATE, entityType, entityId, label, JsonMaps.of("changes", changes));
		}
	}

	public static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after, String... fields) {
		Map<String, Object> changes = new LinkedHashMap<>();
		for (String f : fields) {
			Object from = readable(before.get(f));
			Object to = readable(after.get(f));
			if (!same(from, to)) {
				changes.put(f, JsonMaps.of("from", from, "to", to));
			}
		}
		return changes;
	}

	private static Object readable(Object v) {
		if (v instanceof Map<?, ?> m) return m.containsKey("name") ? m.get("name") : null;
		if (v instanceof List<?> list) {
			return list.stream().map(AuditService::readable).filter(Objects::nonNull).map(String::valueOf)
					.sorted().toList();
		}
		if (v instanceof String s) {
			if (s.isBlank()) return null;
			return clip(s);
		}
		return v;
	}

	private static boolean same(Object a, Object b) {
		if (a instanceof Number x && b instanceof Number y) {
			return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
		}
		return Objects.equals(a, b);
	}

	private static String clip(String s) {
		if (s == null) return null;
		return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "…" : s;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> list(UserPrincipal user, String entityType, String action, String userId,
			String entityId, String q, Integer page) {
		int p = page == null || page < 1 ? 1 : page;
		StringBuilder where = new StringBuilder(" where l.company_id = :c");
		MapSqlParameterSource params = new MapSqlParameterSource("c", user.getCompanyId());
		if (entityType != null && !entityType.isBlank()) {
			String t = entityType.trim().toUpperCase(Locale.ROOT);
			if (!ENTITY_TYPES.contains(t)) throw new AppException("Unknown record type", HttpStatus.BAD_REQUEST);
			where.append(" and l.entity_type = :type");
			params.addValue("type", t);
		}
		if (action != null && !action.isBlank()) {
			where.append(" and l.action = :action");
			params.addValue("action", action.trim().toUpperCase(Locale.ROOT));
		}
		if (userId != null && !userId.isBlank()) {
			where.append(" and l.user_id = :uid");
			params.addValue("uid", userId.trim());
		}
		if (entityId != null && !entityId.isBlank()) {
			where.append(" and l.entity_id = :eid");
			params.addValue("eid", entityId.trim());
		}
		if (q != null && !q.isBlank()) {
			where.append(" and (l.entity_label ilike :q or l.user_name ilike :q)");
			params.addValue("q", "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
		}
		params.addValue("limit", PAGE_SIZE + 1).addValue("offset", (p - 1) * PAGE_SIZE);
		List<Map<String, Object>> rows = jdbc.query("""
				select l.id, l.user_id, coalesce(u.name, l.user_name) as user_name, l.action, l.entity_type,
				  l.entity_id, l.entity_label, l.details::text as details, l.created_at
				from audit_logs l left join users u on u.id = l.user_id
				""" + where + " order by l.created_at desc, l.id desc limit :limit offset :offset", params, this::row);
		boolean hasMore = rows.size() > PAGE_SIZE;
		List<Map<String, Object>> people = jdbc.query("""
				select distinct l.user_id, coalesce(u.name, l.user_name) as name
				from audit_logs l left join users u on u.id = l.user_id
				where l.company_id = :c and l.user_id is not null order by name
				""", new MapSqlParameterSource("c", user.getCompanyId()),
				(rs, i) -> JsonMaps.of("id", rs.getString("user_id"), "name", rs.getString("name")));
		return JsonMaps.of(
				"items", hasMore ? rows.subList(0, PAGE_SIZE) : rows,
				"page", p,
				"hasMore", hasMore,
				"people", people);
	}

	private Map<String, Object> row(ResultSet rs, int i) throws SQLException {
		Map<String, Object> details = null;
		String raw = rs.getString("details");
		if (raw != null) {
			try {
				details = MAPPER.readValue(raw, MAP_TYPE);
			} catch (Exception e) {
				details = null;
			}
		}
		Timestamp ts = rs.getTimestamp("created_at");
		return JsonMaps.of(
				"id", rs.getString("id"),
				"userId", rs.getString("user_id"),
				"userName", rs.getString("user_name"),
				"action", rs.getString("action"),
				"entityType", rs.getString("entity_type"),
				"entityId", rs.getString("entity_id"),
				"entityLabel", rs.getString("entity_label"),
				"details", details,
				"createdAt", ts == null ? null : JsonMaps.iso(ts.toInstant()));
	}
}
