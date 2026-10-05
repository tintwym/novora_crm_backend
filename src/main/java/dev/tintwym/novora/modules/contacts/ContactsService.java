package dev.tintwym.novora.modules.contacts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.common.LeadSource;
import dev.tintwym.novora.domain.entity.Contact;
import dev.tintwym.novora.domain.entity.Note;
import dev.tintwym.novora.domain.entity.Tag;
import dev.tintwym.novora.domain.entity.TagAssignment;
import dev.tintwym.novora.domain.enums.NoteEntityType;
import dev.tintwym.novora.domain.enums.TagEntityType;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.repositories.ActivityRepository;
import dev.tintwym.novora.repositories.ContactRepository;
import dev.tintwym.novora.repositories.CrmAccountRepository;
import dev.tintwym.novora.repositories.DealRepository;
import dev.tintwym.novora.repositories.NoteRepository;
import dev.tintwym.novora.repositories.TagAssignmentRepository;
import dev.tintwym.novora.repositories.TagRepository;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.UserPrincipal;

@Service
public class ContactsService {

	private final ContactRepository contacts;
	private final CrmAccountRepository accounts;
	private final UserEntityRepository users;
	private final TagRepository tags;
	private final TagAssignmentRepository tagAssignments;
	private final NoteRepository notes;
	private final ActivityRepository activities;
	private final DealRepository deals;
	private final ContactToolsService tools;
	private final AuditService audit;

	private static final String[] AUDIT_FIELDS = { "name", "email", "phone", "title", "source", "account", "owner",
			"tags" };

	public ContactsService(ContactRepository contacts, CrmAccountRepository accounts, UserEntityRepository users,
			TagRepository tags, TagAssignmentRepository tagAssignments, NoteRepository notes,
			ActivityRepository activities, DealRepository deals, ContactToolsService tools, AuditService audit) {
		this.tools = tools;
		this.audit = audit;
		this.contacts = contacts;
		this.accounts = accounts;
		this.users = users;
		this.tags = tags;
		this.tagAssignments = tagAssignments;
		this.notes = notes;
		this.activities = activities;
		this.deals = deals;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UserPrincipal user, String search, String accountId, String tag) {
		List<Contact> list = accountId != null && !accountId.isBlank()
				? contacts.findByCompanyIdAndAccountIdOrderByUpdatedAtDesc(user.getCompanyId(), accountId)
				: contacts.findByCompanyIdOrderByUpdatedAtDesc(user.getCompanyId());
		if (search != null && !search.isBlank()) {
			String s = search.toLowerCase();
			list = list.stream().filter(c ->
					(c.getName() != null && c.getName().toLowerCase().contains(s))
							|| (c.getEmail() != null && c.getEmail().toLowerCase().contains(s))
							|| (c.getPhone() != null && c.getPhone().toLowerCase().contains(s)))
					.toList();
		}
		Map<String, List<Map<String, Object>>> tagMap = getContactTags(list.stream().map(c -> c.getId()).toList());
		List<Map<String, Object>> result = new ArrayList<>();
		for (Contact c : list) {
			Map<String, Object> dto = toDto(c, tagMap.getOrDefault(c.getId(), List.of()));
			if (tag != null && !tag.isBlank()) {
				String needle = tag.toLowerCase();
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> t = (List<Map<String, Object>>) dto.get("tags");
				boolean match = t.stream().anyMatch(x -> String.valueOf(x.get("name")).toLowerCase().equals(needle));
				if (!match) {
					continue;
				}
			}
			result.add(dto);
		}
		return result;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(UserPrincipal user, String id) {
		Contact c = contacts.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Contact not found", HttpStatus.NOT_FOUND));
		Map<String, Object> dto = toDto(c, getContactTags(List.of(id)).getOrDefault(id, List.of()));
		if (c.getAccountId() != null) {
			accounts.findById(c.getAccountId()).ifPresent(a ->
					dto.put("account", JsonMaps.of("id", a.getId(), "name", a.getName(), "industry", a.getIndustry())));
		}
		dto.put("deals", deals.findByContactIdAndCompanyIdOrderByUpdatedAtDesc(id, user.getCompanyId()).stream()
				.map(d -> JsonMaps.of("id", d.getId(), "title", d.getTitle(), "value", JsonMaps.num(d.getValue()),
						"stage", d.getStage().name(), "closeDate", JsonMaps.iso(d.getCloseDate())))
				.toList());
		return dto;
	}

	@Transactional
	public Map<String, Object> create(UserPrincipal user, Map<String, Object> body) {
		ensureAccount(user, opt(body, "accountId"));
		Contact c = new Contact();
		c.setName(req(body, "name"));
		c.setEmail(emptyToNull(opt(body, "email")));
		c.setPhone(emptyToNull(opt(body, "phone")));
		c.setTitle(emptyToNull(opt(body, "title")));
		c.setSource(LeadSource.normalize(body.get("source")));
		c.setAccountId(emptyToNull(opt(body, "accountId")));
		c.setOwnerId(body.get("ownerId") != null ? ensureOwner(user, opt(body, "ownerId")) : user.getId());
		c.setCompanyId(user.getCompanyId());
		if (body.get("customFields") instanceof Map<?, ?> cf) {
			@SuppressWarnings("unchecked")
			Map<String, Object> map = (Map<String, Object>) cf;
			c.setCustomFields(new HashMap<>(map));
		}
		contacts.save(c);
		syncTags(user, c.getId(), body);
		audit.created(user, "CONTACT", c.getId(), c.getName());
		return toDto(c, getContactTags(List.of(c.getId())).getOrDefault(c.getId(), List.of()));
	}

	@Transactional
	public Map<String, Object> update(UserPrincipal user, String id, Map<String, Object> body) {
		Contact c = contacts.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Contact not found", HttpStatus.NOT_FOUND));
		Map<String, Object> before = toDto(c, getContactTags(List.of(id)).getOrDefault(id, List.of()));
		if (body.containsKey("accountId")) {
			ensureAccount(user, opt(body, "accountId"));
			c.setAccountId(emptyToNull(opt(body, "accountId")));
		}
		if (body.containsKey("name")) c.setName(req(body, "name"));
		if (body.containsKey("email")) c.setEmail(emptyToNull(opt(body, "email")));
		if (body.containsKey("phone")) c.setPhone(emptyToNull(opt(body, "phone")));
		if (body.containsKey("title")) c.setTitle(emptyToNull(opt(body, "title")));
		if (body.containsKey("source")) c.setSource(LeadSource.normalize(body.get("source")));
		if (body.containsKey("ownerId")) c.setOwnerId(ensureOwner(user, opt(body, "ownerId")));
		if (body.get("customFields") instanceof Map<?, ?> cf) {
			@SuppressWarnings("unchecked")
			Map<String, Object> map = (Map<String, Object>) cf;
			c.setCustomFields(new HashMap<>(map));
		}
		contacts.save(c);
		if (body.containsKey("tagIds") || body.containsKey("tags")) {
			syncTags(user, id, body);
		}
		Map<String, Object> after = toDto(c, getContactTags(List.of(id)).getOrDefault(id, List.of()));
		audit.updated(user, "CONTACT", id, c.getName(), before, after, AUDIT_FIELDS);
		return after;
	}

	@Transactional
	public void delete(UserPrincipal user, String id) {
		Contact c = contacts.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Contact not found", HttpStatus.NOT_FOUND));
		tagAssignments.deleteByEntityTypeAndEntityId(TagEntityType.CONTACT, id);
		notes.deleteByEntityTypeAndEntityIdAndCompanyId(NoteEntityType.CONTACT, id, user.getCompanyId());
		contacts.delete(c);
		audit.deleted(user, "CONTACT", id, c.getName());
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> timeline(UserPrincipal user, String contactId) {
		contacts.findByIdAndCompanyId(contactId, user.getCompanyId())
				.orElseThrow(() -> new AppException("Contact not found", HttpStatus.NOT_FOUND));
		List<Map<String, Object>> items = new ArrayList<>();
		activities.findByContactIdAndCompanyIdOrderByDateDesc(contactId, user.getCompanyId()).forEach(a ->
				items.add(JsonMaps.of(
						"id", a.getId(),
						"kind", "activity",
						"type", a.getType().name(),
						"subject", a.getSubject(),
						"note", a.getNote(),
						"date", JsonMaps.iso(a.getDate()),
						"user", userBrief(a.getUserId()))));
		notes.findByEntityTypeAndEntityIdAndCompanyIdOrderByCreatedAtDesc(NoteEntityType.CONTACT, contactId, user.getCompanyId())
				.forEach(n -> items.add(JsonMaps.of(
						"id", n.getId(),
						"kind", "note",
						"type", "NOTE",
						"subject", null,
						"note", n.getBody(),
						"date", JsonMaps.iso(n.getCreatedAt()),
						"user", userBrief(n.getAuthorId()))));
		items.sort((a, b) -> String.valueOf(b.get("date")).compareTo(String.valueOf(a.get("date"))));
		return items;
	}

	@Transactional
	public Map<String, Object> addNote(UserPrincipal user, String contactId, Map<String, Object> body) {
		contacts.findByIdAndCompanyId(contactId, user.getCompanyId())
				.orElseThrow(() -> new AppException("Contact not found", HttpStatus.NOT_FOUND));
		Note n = new Note();
		n.setBody(req(body, "body"));
		n.setEntityType(NoteEntityType.CONTACT);
		n.setEntityId(contactId);
		n.setCompanyId(user.getCompanyId());
		n.setAuthorId(user.getId());
		notes.save(n);
		return JsonMaps.of(
				"id", n.getId(),
				"body", n.getBody(),
				"entityType", n.getEntityType().name(),
				"entityId", n.getEntityId(),
				"companyId", n.getCompanyId(),
				"authorId", n.getAuthorId(),
				"createdAt", JsonMaps.iso(n.getCreatedAt()),
				"updatedAt", JsonMaps.iso(n.getUpdatedAt()),
				"author", userBrief(n.getAuthorId()));
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> listTags(UserPrincipal user) {
		return tags.findByCompanyIdOrderByNameAsc(user.getCompanyId()).stream()
				.map(t -> JsonMaps.of("id", t.getId(), "name", t.getName(), "color", t.getColor(),
						"companyId", t.getCompanyId(), "createdAt", JsonMaps.iso(t.getCreatedAt())))
				.toList();
	}

	@Transactional
	public Map<String, Object> createTag(UserPrincipal user, Map<String, Object> body) {
		try {
			Tag t = new Tag();
			t.setName(req(body, "name").trim());
			t.setColor(body.get("color") != null ? String.valueOf(body.get("color")) : "#3278ff");
			t.setCompanyId(user.getCompanyId());
			tags.save(t);
			return JsonMaps.of("id", t.getId(), "name", t.getName(), "color", t.getColor(),
					"companyId", t.getCompanyId(), "createdAt", JsonMaps.iso(t.getCreatedAt()));
		} catch (DataIntegrityViolationException e) {
			throw new AppException("Tag already exists", HttpStatus.CONFLICT);
		}
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> importContacts(UserPrincipal user, Map<String, Object> body) {
		Object raw = body.get("contacts");
		if (!(raw instanceof List<?> rows)) {
			throw new AppException("contacts is required", HttpStatus.BAD_REQUEST);
		}
		List<String> created = new ArrayList<>();
		List<Map<String, Object>> errors = new ArrayList<>();
		List<Map<String, Object>> skipped = new ArrayList<>();
		boolean skipDuplicates = !Boolean.FALSE.equals(body.get("skipDuplicates"));
		Set<String> seen = skipDuplicates ? tools.existingKeys(user.getCompanyId()) : new HashSet<>();
		for (int i = 0; i < rows.size(); i++) {
			try {
				Map<String, Object> row = (Map<String, Object>) rows.get(i);
				if (skipDuplicates) {
					String ek = ContactToolsService.emailKey(opt(row, "email"));
					String pk = ContactToolsService.phoneKey(opt(row, "phone"));
					String dup = ek != null && seen.contains("e:" + ek) ? "email"
							: pk != null && seen.contains("p:" + pk) ? "phone" : null;
					if (dup != null) {
						skipped.add(JsonMaps.of("row", i + 1, "name", opt(row, "name"), "reason", "Same " + dup + " as an existing contact"));
						continue;
					}
					if (ek != null) seen.add("e:" + ek);
					if (pk != null) seen.add("p:" + pk);
				}
				String accountId = null;
				String accountName = emptyToNull(opt(row, "accountName"));
				if (accountName != null) {
					accountId = accounts.findFirstByCompanyIdAndNameIgnoreCase(user.getCompanyId(), accountName)
							.map(a -> a.getId())
							.orElseGet(() -> {
								var a = new dev.tintwym.novora.domain.entity.CrmAccount();
								a.setName(accountName);
								a.setCompanyId(user.getCompanyId());
								a.setOwnerId(user.getId());
								accounts.save(a);
								return a.getId();
							});
				}
				Contact c = new Contact();
				c.setName(req(row, "name"));
				c.setEmail(emptyToNull(opt(row, "email")));
				c.setPhone(emptyToNull(opt(row, "phone")));
				c.setTitle(emptyToNull(opt(row, "title")));
				c.setSource(LeadSource.normalize(row.get("source")));
				c.setAccountId(accountId);
				c.setOwnerId(user.getId());
				c.setCompanyId(user.getCompanyId());
				contacts.save(c);
				String tagsStr = opt(row, "tags");
				if (tagsStr != null && !tagsStr.isBlank()) {
					List<String> names = java.util.Arrays.stream(tagsStr.split("[,;|]"))
							.map(part -> part.trim()).filter(s -> !s.isEmpty()).toList();
					Map<String, Object> tagBody = new HashMap<>();
					tagBody.put("tags", names);
					syncTags(user, c.getId(), tagBody);
				}
				created.add(c.getId());
			} catch (Exception ex) {
				errors.add(JsonMaps.of("row", i + 1, "message", ex.getMessage() != null ? ex.getMessage() : "Failed"));
			}
		}
		if (!created.isEmpty()) {
			audit.record(user, "IMPORT", "CONTACT", null, created.size() + (created.size() == 1 ? " contact" : " contacts"),
					JsonMaps.of("imported", created.size(), "skipped", skipped.size(), "failed", errors.size()));
		}
		return JsonMaps.of("imported", created.size(), "createdIds", created, "errors", errors, "skipped", skipped);
	}

	private void syncTags(UserPrincipal user, String contactId, Map<String, Object> body) {
		if (!body.containsKey("tagIds") && !body.containsKey("tags")) {
			return;
		}
		tagAssignments.deleteByEntityTypeAndEntityId(TagEntityType.CONTACT, contactId);
		Set<String> resolved = new HashSet<>();
		if (body.get("tagIds") instanceof List<?> ids) {
			ids.forEach(id -> resolved.add(String.valueOf(id)));
		}
		if (body.get("tags") instanceof List<?> names) {
			for (Object nameObj : names) {
				String trimmed = String.valueOf(nameObj).trim();
				if (trimmed.isEmpty()) continue;
				Tag tag = tags.findByCompanyIdAndName(user.getCompanyId(), trimmed).orElseGet(() -> {
					Tag t = new Tag();
					t.setName(trimmed);
					t.setCompanyId(user.getCompanyId());
					return tags.save(t);
				});
				resolved.add(tag.getId());
			}
		}
		if (resolved.isEmpty()) return;
		List<Tag> valid = tags.findByIdInAndCompanyId(resolved, user.getCompanyId());
		for (Tag t : valid) {
			TagAssignment a = new TagAssignment();
			a.setTagId(t.getId());
			a.setEntityType(TagEntityType.CONTACT);
			a.setEntityId(contactId);
			tagAssignments.save(a);
		}
	}

	private Map<String, List<Map<String, Object>>> getContactTags(List<String> contactIds) {
		Map<String, List<Map<String, Object>>> map = new HashMap<>();
		if (contactIds.isEmpty()) return map;
		List<TagAssignment> assignments = tagAssignments.findByEntityTypeAndEntityIdIn(TagEntityType.CONTACT, contactIds);
		Map<String, Tag> tagById = new HashMap<>();
		tags.findAllById(assignments.stream().map(a -> a.getTagId()).distinct().toList())
				.forEach(t -> tagById.put(t.getId(), t));
		for (TagAssignment a : assignments) {
			Tag t = tagById.get(a.getTagId());
			if (t == null) continue;
			map.computeIfAbsent(a.getEntityId(), k -> new ArrayList<>())
					.add(JsonMaps.of("id", t.getId(), "name", t.getName(), "color", t.getColor()));
		}
		return map;
	}

	private Map<String, Object> toDto(Contact c, List<Map<String, Object>> tagList) {
		Map<String, Object> dto = JsonMaps.of(
				"id", c.getId(),
				"name", c.getName(),
				"email", c.getEmail(),
				"phone", c.getPhone(),
				"title", c.getTitle(),
				"source", c.getSource(),
				"companyId", c.getCompanyId(),
				"accountId", c.getAccountId(),
				"ownerId", c.getOwnerId(),
				"customFields", c.getCustomFields(),
				"createdAt", JsonMaps.iso(c.getCreatedAt()),
				"updatedAt", JsonMaps.iso(c.getUpdatedAt()),
				"tags", tagList,
				"owner", userBrief(c.getOwnerId()));
		if (c.getAccountId() != null) {
			accounts.findById(c.getAccountId()).ifPresent(a ->
					dto.put("account", JsonMaps.of("id", a.getId(), "name", a.getName())));
		} else {
			dto.put("account", null);
		}
		return dto;
	}

	private Map<String, Object> userBrief(String id) {
		if (id == null) return null;
		return users.findById(id)
				.map(u -> JsonMaps.of("id", u.getId(), "name", u.getName(), "email", u.getEmail()))
				.orElse(null);
	}

	private String ensureOwner(UserPrincipal user, String ownerId) {
		String id = emptyToNull(ownerId);
		if (id == null) return null;
		return users.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Owner not found", HttpStatus.BAD_REQUEST)).getId();
	}

	private void ensureAccount(UserPrincipal user, String accountId) {
		if (accountId == null || accountId.isBlank()) return;
		accounts.findByIdAndCompanyId(accountId, user.getCompanyId())
				.orElseThrow(() -> new AppException("Linked company not found", HttpStatus.NOT_FOUND));
	}

	private static String req(Map<String, Object> body, String key) {
		Object v = body.get(key);
		if (v == null || String.valueOf(v).isBlank()) throw new AppException(key + " is required", HttpStatus.BAD_REQUEST);
		return String.valueOf(v).trim();
	}

	private static String opt(Map<String, Object> body, String key) {
		Object v = body.get(key);
		return v == null ? null : String.valueOf(v);
	}

	private static String emptyToNull(String v) {
		if (v == null) return null;
		String t = v.trim();
		return t.isEmpty() ? null : t;
	}
}
