package dev.tintwym.novora.modules.contacts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
import dev.tintwym.novora.common.LeadSource;
import dev.tintwym.novora.domain.entity.Contact;
import dev.tintwym.novora.domain.entity.Tag;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.repositories.ContactRepository;
import dev.tintwym.novora.repositories.CrmAccountRepository;
import dev.tintwym.novora.repositories.TagRepository;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.UserPrincipal;

/** Bulk edits, duplicate detection and merging for contacts. */
@Service
public class ContactToolsService {

	private static final int MAX_BULK = 500;
	private static final int MAX_MERGE = 10;

	private final NamedParameterJdbcTemplate jdbc;
	private final ContactRepository contacts;
	private final CrmAccountRepository accounts;
	private final UserEntityRepository users;
	private final TagRepository tags;
	private final AuditService audit;

	public ContactToolsService(NamedParameterJdbcTemplate jdbc, ContactRepository contacts,
			CrmAccountRepository accounts, UserEntityRepository users, TagRepository tags, AuditService audit) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.contacts = contacts;
		this.accounts = accounts;
		this.users = users;
		this.tags = tags;
	}

	// ---- duplicate keys ----

	static String emailKey(String email) {
		if (email == null) return null;
		String e = email.trim().toLowerCase();
		return e.isEmpty() ? null : e;
	}

	/** Compares the last 9 digits so +95 9xxx and 09xxx forms of the same number match. */
	static String phoneKey(String phone) {
		if (phone == null) return null;
		String digits = phone.replaceAll("\\D", "");
		if (digits.length() < 7) return null;
		return digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;
	}

	static String nameKey(String name) {
		if (name == null) return null;
		String n = name.trim().replaceAll("\\s+", " ").toLowerCase();
		return n.length() < 3 ? null : n;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> check(UserPrincipal user, String email, String phone, String name,
			String excludeId) {
		String ek = emailKey(email), pk = phoneKey(phone), nk = nameKey(name);
		if (ek == null && pk == null && nk == null) return List.of();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Contact c : contacts.findByCompanyIdOrderByUpdatedAtDesc(user.getCompanyId())) {
			if (c.getId().equals(excludeId)) continue;
			List<String> reasons = new ArrayList<>();
			if (ek != null && ek.equals(emailKey(c.getEmail()))) reasons.add("email");
			if (pk != null && pk.equals(phoneKey(c.getPhone()))) reasons.add("phone");
			if (nk != null && nk.equals(nameKey(c.getName()))) reasons.add("name");
			if (!reasons.isEmpty()) {
				Map<String, Object> m = brief(c);
				m.put("matchedOn", reasons);
				out.add(m);
			}
			if (out.size() >= 5) break;
		}
		return out;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> duplicateGroups(UserPrincipal user) {
		List<Contact> all = contacts.findByCompanyIdOrderByUpdatedAtDesc(user.getCompanyId());
		int n = all.size();
		int[] parent = new int[n];
		for (int i = 0; i < n; i++) parent[i] = i;
		Map<String, Integer> firstByKey = new HashMap<>();
		Map<Integer, Set<String>> reasons = new HashMap<>();
		for (int i = 0; i < n; i++) {
			Contact c = all.get(i);
			String[][] keys = { { "email", emailKey(c.getEmail()) }, { "phone", phoneKey(c.getPhone()) },
					{ "name", nameKey(c.getName()) } };
			for (String[] k : keys) {
				if (k[1] == null) continue;
				Integer j = firstByKey.putIfAbsent(k[0] + ":" + k[1], i);
				if (j != null) {
					union(parent, i, j);
					reasons.computeIfAbsent(i, x -> new LinkedHashSet<>()).add(k[0]);
					reasons.computeIfAbsent(j, x -> new LinkedHashSet<>()).add(k[0]);
				}
			}
		}
		Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), x -> new ArrayList<>()).add(i);
		List<Map<String, Object>> out = new ArrayList<>();
		for (List<Integer> members : groups.values()) {
			if (members.size() < 2) continue;
			Set<String> why = new LinkedHashSet<>();
			List<Contact> cs = members.stream().map(all::get)
					.sorted(Comparator.comparing(Contact::getCreatedAt)).toList();
			members.forEach(i -> why.addAll(reasons.getOrDefault(i, Set.of())));
			Map<String, Long> counts = linkedCounts(cs.stream().map(Contact::getId).toList());
			List<Map<String, Object>> rows = new ArrayList<>();
			for (Contact c : cs) {
				Map<String, Object> m = brief(c);
				m.put("linkedRecords", counts.getOrDefault(c.getId(), 0L));
				rows.add(m);
			}
			out.add(JsonMaps.of("matchedOn", List.copyOf(why), "contacts", rows));
		}
		out.sort(Comparator.comparingInt(g -> -((List<?>) g.get("contacts")).size()));
		return out;
	}

	private static int find(int[] p, int i) {
		while (p[i] != i) {
			p[i] = p[p[i]];
			i = p[i];
		}
		return i;
	}

	private static void union(int[] p, int a, int b) {
		p[find(p, a)] = find(p, b);
	}

	private Map<String, Long> linkedCounts(List<String> ids) {
		Map<String, Long> out = new HashMap<>();
		jdbc.query("""
				select id, (select count(*) from deals d where d.contact_id = c.id)
				  + (select count(*) from activities a where a.contact_id = c.id)
				  + (select count(*) from notes n where n.entity_type = 'CONTACT' and n.entity_id = c.id)
				  + (select count(*) from tickets t where t.contact_id = c.id)
				  + (select count(*) from quotes q where q.contact_id = c.id) as linked
				from contacts c where c.id in (:ids)
				""", new MapSqlParameterSource("ids", ids), rs -> {
			out.put(rs.getString("id"), rs.getLong("linked"));
		});
		return out;
	}

	// ---- merge ----

	@Transactional
	public Map<String, Object> merge(UserPrincipal user, String primaryId, Object rawIds) {
		List<String> sourceIds = idList(rawIds, MAX_MERGE).stream().filter(id -> !id.equals(primaryId)).toList();
		if (sourceIds.isEmpty()) throw new AppException("Pick at least one other contact to merge", HttpStatus.BAD_REQUEST);
		String companyId = user.getCompanyId();
		Contact primary = contacts.findByIdAndCompanyId(primaryId, companyId)
				.orElseThrow(() -> new AppException("Contact not found", HttpStatus.NOT_FOUND));
		List<Contact> sources = new ArrayList<>();
		for (String id : sourceIds) {
			sources.add(contacts.findByIdAndCompanyId(id, companyId)
					.orElseThrow(() -> new AppException("Contact not found", HttpStatus.NOT_FOUND)));
		}

		for (Contact s : sources) {
			if (primary.getEmail() == null) primary.setEmail(s.getEmail());
			if (primary.getPhone() == null) primary.setPhone(s.getPhone());
			if (primary.getTitle() == null) primary.setTitle(s.getTitle());
			if (primary.getSource() == null) primary.setSource(s.getSource());
			if (primary.getAccountId() == null) primary.setAccountId(s.getAccountId());
			if (primary.getOwnerId() == null) primary.setOwnerId(s.getOwnerId());
			if (s.getCustomFields() != null) {
				Map<String, Object> cf = new HashMap<>(primary.getCustomFields() == null ? Map.of() : primary.getCustomFields());
				s.getCustomFields().forEach(cf::putIfAbsent);
				primary.setCustomFields(cf);
			}
		}

		MapSqlParameterSource p = new MapSqlParameterSource("primary", primaryId).addValue("ids", sourceIds)
				.addValue("companyId", companyId);
		int moved = 0;
		for (String table : List.of("deals", "activities", "quotes", "tickets")) {
			moved += jdbc.update("update " + table + " set contact_id = :primary where company_id = :companyId and contact_id in (:ids)", p);
		}
		moved += jdbc.update("""
				update notes set entity_id = :primary
				where company_id = :companyId and entity_type = 'CONTACT' and entity_id in (:ids)
				""", p);
		List<String> tagIds = jdbc.queryForList("""
				select distinct tag_id from tag_assignments where entity_type = 'CONTACT' and entity_id in (:ids)
				""", p, String.class);
		for (String tagId : tagIds) {
			assignTag(tagId, primaryId);
		}
		jdbc.update("delete from tag_assignments where entity_type = 'CONTACT' and entity_id in (:ids)", p);

		contacts.save(primary);
		contacts.deleteAll(sources);
		audit.record(user, "MERGE", "CONTACT", primaryId, primary.getName(), JsonMaps.of(
				"mergedContacts", sources.stream().map(Contact::getName).toList(),
				"movedRecords", moved));
		return JsonMaps.of("primaryId", primaryId, "merged", sources.size(), "movedRecords", moved);
	}

	// ---- bulk ----

	@Transactional
	public Map<String, Object> bulk(UserPrincipal user, Map<String, Object> body) {
		List<String> ids = idList(body.get("ids"), MAX_BULK);
		if (ids.isEmpty()) throw new AppException("Select at least one contact", HttpStatus.BAD_REQUEST);
		String action = body.get("action") == null ? "" : String.valueOf(body.get("action"));
		Object value = body.get("value");
		String companyId = user.getCompanyId();
		List<Map<String, Object>> found = jdbc.queryForList(
				"select id, name from contacts where company_id = :c and id in (:ids) order by name",
				new MapSqlParameterSource("c", companyId).addValue("ids", ids));
		List<String> scoped = found.stream().map(r -> String.valueOf(r.get("id"))).toList();
		if (scoped.isEmpty()) return JsonMaps.of("updated", 0);
		Map<String, Object> auditDetails = JsonMaps.of("count", scoped.size(),
				"contacts", found.stream().limit(20).map(r -> String.valueOf(r.get("name"))).toList());
		MapSqlParameterSource p = new MapSqlParameterSource("ids", scoped).addValue("c", companyId);
		int updated;
		switch (action) {
			case "delete" -> {
				jdbc.update("delete from tag_assignments where entity_type = 'CONTACT' and entity_id in (:ids)", p);
				jdbc.update("delete from notes where company_id = :c and entity_type = 'CONTACT' and entity_id in (:ids)", p);
				updated = jdbc.update("delete from contacts where company_id = :c and id in (:ids)", p);
			}
			case "assignOwner" -> {
				String ownerId = value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
				if (ownerId != null) {
					users.findByIdAndCompanyId(ownerId, companyId)
							.orElseThrow(() -> new AppException("Owner not found", HttpStatus.BAD_REQUEST));
				}
				updated = jdbc.update("update contacts set owner_id = :owner, updated_at = now() where company_id = :c and id in (:ids)",
						p.addValue("owner", ownerId));
			}
			case "setSource" -> updated = jdbc.update(
					"update contacts set source = :source, updated_at = now() where company_id = :c and id in (:ids)",
					p.addValue("source", LeadSource.normalize(value)));
			case "setAccount" -> {
				String accountId = value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
				if (accountId != null) {
					accounts.findByIdAndCompanyId(accountId, companyId)
							.orElseThrow(() -> new AppException("Company not found", HttpStatus.BAD_REQUEST));
				}
				updated = jdbc.update("update contacts set account_id = :acc, updated_at = now() where company_id = :c and id in (:ids)",
						p.addValue("acc", accountId));
			}
			case "addTag" -> {
				String name = value == null ? "" : String.valueOf(value).trim();
				if (name.isEmpty()) throw new AppException("Enter a tag name", HttpStatus.BAD_REQUEST);
				Tag tag = tags.findByCompanyIdAndName(companyId, name).orElseGet(() -> {
					Tag t = new Tag();
					t.setName(name);
					t.setCompanyId(companyId);
					return tags.saveAndFlush(t);
				});
				updated = 0;
				for (String id : scoped) updated += assignTag(tag.getId(), id);
			}
			case "removeTag" -> {
				String tagId = value == null ? "" : String.valueOf(value);
				updated = jdbc.update("""
						delete from tag_assignments where entity_type = 'CONTACT' and entity_id in (:ids)
						  and tag_id in (select id from tags where company_id = :c and id = :tag)
						""", p.addValue("tag", tagId));
			}
			default -> throw new AppException("Unknown bulk action", HttpStatus.BAD_REQUEST);
		}
		auditDetails.put("change", bulkChangeLabel(action, value, companyId));
		audit.record(user, "delete".equals(action) ? "BULK_DELETE" : "BULK_UPDATE", "CONTACT", null,
				scoped.size() + (scoped.size() == 1 ? " contact" : " contacts"), auditDetails);
		return JsonMaps.of("updated", updated);
	}

	private String bulkChangeLabel(String action, Object value, String companyId) {
		String v = value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
		return switch (action) {
			case "delete" -> "Deleted";
			case "assignOwner" -> "Owner set to " + (v == null ? "nobody"
					: users.findByIdAndCompanyId(v, companyId).map(u -> u.getName()).orElse("unknown"));
			case "setSource" -> "Source set to " + (LeadSource.normalize(value) == null ? "none" : LeadSource.normalize(value));
			case "setAccount" -> "Company set to " + (v == null ? "none"
					: accounts.findByIdAndCompanyId(v, companyId).map(a -> a.getName()).orElse("unknown"));
			case "addTag" -> "Tag added: " + v;
			case "removeTag" -> "Tag removed: " + (v == null ? "" : tags.findById(v).map(Tag::getName).orElse(v));
			default -> action;
		};
	}

	private int assignTag(String tagId, String contactId) {
		return jdbc.update("""
				insert into tag_assignments (id, tag_id, entity_type, entity_id, created_at)
				values (:id, :tag, 'CONTACT', :contact, now())
				on conflict (tag_id, entity_type, entity_id) do nothing
				""", new MapSqlParameterSource("id", Cuid.generate()).addValue("tag", tagId).addValue("contact", contactId));
	}

	private static List<String> idList(Object raw, int max) {
		if (!(raw instanceof List<?> list)) throw new AppException("ids is required", HttpStatus.BAD_REQUEST);
		Set<String> ids = new LinkedHashSet<>();
		for (Object o : list) {
			if (o != null && !String.valueOf(o).isBlank()) ids.add(String.valueOf(o));
		}
		if (ids.size() > max) throw new AppException("You can change up to " + max + " contacts at once", HttpStatus.BAD_REQUEST);
		return new ArrayList<>(ids);
	}

	private Map<String, Object> brief(Contact c) {
		Map<String, Object> m = JsonMaps.of(
				"id", c.getId(),
				"name", c.getName(),
				"email", c.getEmail(),
				"phone", c.getPhone(),
				"title", c.getTitle(),
				"source", c.getSource(),
				"createdAt", JsonMaps.iso(c.getCreatedAt()));
		m.put("account", c.getAccountId() == null ? null
				: accounts.findById(c.getAccountId()).map(a -> JsonMaps.of("id", a.getId(), "name", a.getName())).orElse(null));
		m.put("owner", c.getOwnerId() == null ? null
				: users.findById(c.getOwnerId()).map(u -> JsonMaps.of("id", u.getId(), "name", u.getName())).orElse(null));
		return m;
	}

	/** Keys for import de-duplication: existing contacts' emails and phones. */
	Set<String> existingKeys(String companyId) {
		Set<String> keys = new HashSet<>();
		for (Contact c : contacts.findByCompanyIdOrderByUpdatedAtDesc(companyId)) {
			String e = emailKey(c.getEmail()), ph = phoneKey(c.getPhone());
			if (e != null) keys.add("e:" + e);
			if (ph != null) keys.add("p:" + ph);
		}
		return keys;
	}
}
