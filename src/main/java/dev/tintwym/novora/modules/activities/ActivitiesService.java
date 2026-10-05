package dev.tintwym.novora.modules.activities;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.domain.entity.Activity;
import dev.tintwym.novora.domain.enums.ActivityType;
import dev.tintwym.novora.repositories.ActivityRepository;
import dev.tintwym.novora.repositories.ContactRepository;
import dev.tintwym.novora.repositories.DealRepository;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.SecurityUtils;
import dev.tintwym.novora.security.UserPrincipal;

@Service
public class ActivitiesService {

	private final ActivityRepository activities;
	private final ContactRepository contacts;
	private final DealRepository deals;
	private final UserEntityRepository users;

	public ActivitiesService(ActivityRepository activities, ContactRepository contacts, DealRepository deals,
			UserEntityRepository users) {
		this.activities = activities;
		this.contacts = contacts;
		this.deals = deals;
		this.users = users;
	}

	@Transactional
	public Map<String, Object> create(UserPrincipal user, Map<String, Object> body) {
		String contactId = emptyToNull(opt(body, "contactId"));
		String dealId = emptyToNull(opt(body, "dealId"));
		if (contactId == null && dealId == null) {
			throw new AppException("Activity must link to a contact or deal", HttpStatus.BAD_REQUEST);
		}
		ensureLinks(user, contactId, dealId);
		Activity a = new Activity();
		a.setType(ActivityType.valueOf(req(body, "type")));
		a.setSubject(emptyToNull(opt(body, "subject")));
		a.setNote(emptyToNull(opt(body, "note")));
		a.setDueAt(parseDate(opt(body, "dueAt")));
		a.setDate(parseDate(opt(body, "date")) != null ? parseDate(opt(body, "date")) : Instant.now());
		a.setCompanyId(user.getCompanyId());
		a.setUserId(user.getId());
		a.setContactId(contactId);
		a.setDealId(dealId);
		activities.save(a);
		return toDto(a);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UserPrincipal user, String type, String contactId, String dealId,
			Boolean completed, Integer limit) {
		ActivityType t = type == null || type.isBlank() ? null : ActivityType.valueOf(type);
		int take = Math.min(limit == null ? 100 : limit, 200);
		return activities.filter(user.getCompanyId(), t, emptyToNull(contactId), emptyToNull(dealId), completed,
				PageRequest.of(0, take)).stream().map(this::toDto).toList();
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> recent(UserPrincipal user, Integer limit) {
		int take = Math.min(limit == null ? 20 : limit, 100);
		return activities.findByCompanyIdOrderByDateDesc(user.getCompanyId(), PageRequest.of(0, take)).stream()
				.map(this::toDto).toList();
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(UserPrincipal user, String id) {
		return toDto(find(user, id));
	}

	@Transactional
	public Map<String, Object> update(UserPrincipal user, String id, Map<String, Object> body) {
		Activity a = find(user, id);
		ensureLinks(user, opt(body, "contactId"), opt(body, "dealId"));
		if (body.containsKey("type")) a.setType(ActivityType.valueOf(String.valueOf(body.get("type"))));
		if (body.containsKey("subject")) a.setSubject(emptyToNull(opt(body, "subject")));
		if (body.containsKey("note")) a.setNote(emptyToNull(opt(body, "note")));
		if (body.containsKey("dueAt")) a.setDueAt(parseDate(opt(body, "dueAt")));
		if (body.containsKey("date")) {
			Instant d = parseDate(opt(body, "date"));
			a.setDate(d != null ? d : Instant.now());
		}
		if (body.containsKey("contactId")) a.setContactId(emptyToNull(opt(body, "contactId")));
		if (body.containsKey("dealId")) a.setDealId(emptyToNull(opt(body, "dealId")));
		if (body.containsKey("completed")) {
			boolean completed = Boolean.parseBoolean(String.valueOf(body.get("completed")));
			a.setCompletedAt(completed ? Instant.now() : null);
		}
		activities.save(a);
		return toDto(a);
	}

	@Transactional
	public Map<String, Object> complete(UserPrincipal user, String id) {
		return update(user, id, Map.of("completed", true));
	}

	@Transactional
	public void delete(UserPrincipal user, String id) {
		Activity a = find(user, id);
		if (!user.getId().equals(a.getUserId()) && !SecurityUtils.canViewAllDeals(user)) {
			throw new AppException("Only the person who logged this activity or a manager can delete it",
					HttpStatus.FORBIDDEN);
		}
		activities.delete(a);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> calendar(UserPrincipal user, String from, String to) {
		Instant fromDate;
		Instant toDate;
		try {
			fromDate = Instant.parse(from.contains("T") ? from : from + "T00:00:00Z");
			toDate = Instant.parse(to.contains("T") ? to : to + "T23:59:59Z");
		} catch (Exception e) {
			throw new AppException("Invalid calendar range", HttpStatus.BAD_REQUEST);
		}
		return activities.calendar(user.getCompanyId(), fromDate, toDate).stream().map(a -> {
			Map<String, Object> dto = toDto(a);
			dto.put("eventAt", JsonMaps.iso(a.getDueAt() != null ? a.getDueAt() : a.getDate()));
			return dto;
		}).toList();
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> reminders(UserPrincipal user) {
		Instant now = Instant.now();
		Instant soon = now.plusSeconds(48 * 3600);
		return activities
				.findByCompanyIdAndCompletedAtIsNullAndDueAtIsNotNullAndDueAtLessThanEqualOrderByDueAtAsc(
						user.getCompanyId(), soon, PageRequest.of(0, 50))
				.stream()
				.map(a -> {
					Map<String, Object> dto = toDto(a);
					dto.put("status", a.getDueAt() != null && a.getDueAt().isBefore(now) ? "overdue" : "upcoming");
					return dto;
				}).toList();
	}

	private Activity find(UserPrincipal user, String id) {
		return activities.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Activity not found", HttpStatus.NOT_FOUND));
	}

	private void ensureLinks(UserPrincipal user, String contactId, String dealId) {
		if (contactId != null && !contactId.isBlank() && !"null".equals(contactId)) {
			contacts.findByIdAndCompanyId(contactId, user.getCompanyId())
					.orElseThrow(() -> new AppException("Contact not found", HttpStatus.NOT_FOUND));
		}
		if (dealId != null && !dealId.isBlank() && !"null".equals(dealId)) {
			deals.findByIdAndCompanyId(dealId, user.getCompanyId())
					.orElseThrow(() -> new AppException("Deal not found", HttpStatus.NOT_FOUND));
		}
	}

	private Map<String, Object> toDto(Activity a) {
		return JsonMaps.of(
				"id", a.getId(),
				"type", a.getType().name(),
				"subject", a.getSubject(),
				"note", a.getNote(),
				"dueAt", JsonMaps.iso(a.getDueAt()),
				"completedAt", JsonMaps.iso(a.getCompletedAt()),
				"date", JsonMaps.iso(a.getDate()),
				"companyId", a.getCompanyId(),
				"userId", a.getUserId(),
				"contactId", a.getContactId(),
				"dealId", a.getDealId(),
				"automated", a.getAutomationRuleId() != null,
				"createdAt", JsonMaps.iso(a.getCreatedAt()),
				"updatedAt", JsonMaps.iso(a.getUpdatedAt()),
				"user", a.getUserId() == null ? null : users.findById(a.getUserId())
						.map(u -> JsonMaps.of("id", u.getId(), "name", u.getName())).orElse(null),
				"contact", a.getContactId() == null ? null : contacts.findById(a.getContactId())
						.map(c -> JsonMaps.of("id", c.getId(), "name", c.getName())).orElse(null),
				"deal", a.getDealId() == null ? null : deals.findById(a.getDealId())
						.map(d -> JsonMaps.of("id", d.getId(), "title", d.getTitle())).orElse(null));
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
		if (v == null || "null".equals(v)) return null;
		String t = v.trim();
		return t.isEmpty() ? null : t;
	}

	private static Instant parseDate(String value) {
		if (value == null || value.isBlank() || "null".equals(value)) return null;
		try {
			return Instant.parse(value.contains("T") ? value : value + "T00:00:00Z");
		} catch (Exception e) {
			return Instant.parse(value);
		}
	}
}
