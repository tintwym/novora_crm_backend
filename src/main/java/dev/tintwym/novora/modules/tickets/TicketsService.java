package dev.tintwym.novora.modules.tickets;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.domain.entity.Contact;
import dev.tintwym.novora.domain.entity.KbArticle;
import dev.tintwym.novora.domain.entity.TenantCompany;
import dev.tintwym.novora.domain.entity.Ticket;
import dev.tintwym.novora.domain.entity.TicketMessage;
import dev.tintwym.novora.domain.enums.TicketPriority;
import dev.tintwym.novora.domain.enums.TicketStatus;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.repositories.ContactRepository;
import dev.tintwym.novora.repositories.KbArticleRepository;
import dev.tintwym.novora.repositories.TenantCompanyRepository;
import dev.tintwym.novora.repositories.TicketMessageRepository;
import dev.tintwym.novora.repositories.TicketRepository;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.UserPrincipal;

@Service
public class TicketsService {

	public static final Map<TicketPriority, Integer> SLA_HOURS = Map.of(
			TicketPriority.URGENT, 4,
			TicketPriority.HIGH, 8,
			TicketPriority.MEDIUM, 24,
			TicketPriority.LOW, 72);

	private final TicketRepository tickets;
	private final TicketMessageRepository messages;
	private final ContactRepository contacts;
	private final UserEntityRepository users;
	private final KbArticleRepository kbArticles;
	private final TenantCompanyRepository companies;
	private final AuditService audit;

	private static final String[] AUDIT_FIELDS = { "title", "description", "status", "priority", "contact",
			"assignee" };

	public TicketsService(TicketRepository tickets, TicketMessageRepository messages, ContactRepository contacts,
			UserEntityRepository users, KbArticleRepository kbArticles, TenantCompanyRepository companies,
			AuditService audit) {
		this.audit = audit;
		this.tickets = tickets;
		this.messages = messages;
		this.contacts = contacts;
		this.users = users;
		this.kbArticles = kbArticles;
		this.companies = companies;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UserPrincipal user, String status, String priority, boolean breachedOnly) {
		List<Ticket> list;
		boolean hasPriority = priority != null && !priority.isBlank();
		if (status != null && !status.isBlank()) {
			list = tickets.findByCompanyIdAndStatusOrderByPriorityDescUpdatedAtDesc(user.getCompanyId(),
					TicketStatus.valueOf(status));
			if (hasPriority) {
				TicketPriority p = TicketPriority.valueOf(priority);
				list = list.stream().filter(t -> t.getPriority() == p).toList();
			}
		} else if (hasPriority) {
			list = tickets.findByCompanyIdAndPriorityOrderByUpdatedAtDesc(user.getCompanyId(),
					TicketPriority.valueOf(priority));
		} else {
			list = tickets.findByCompanyIdOrderByPriorityDescUpdatedAtDesc(user.getCompanyId());
		}
		List<Map<String, Object>> mapped = list.stream().map(t -> {
			Map<String, Object> dto = withSla(listDto(t));
			dto.put("_count", JsonMaps.of("messages", messages.countByTicketId(t.getId())));
			return dto;
		}).toList();
		if (breachedOnly) {
			return mapped.stream().filter(t -> Boolean.TRUE.equals(t.get("slaBreached"))).toList();
		}
		return mapped;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(UserPrincipal user, String id) {
		Ticket t = tickets.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Ticket not found", HttpStatus.NOT_FOUND));
		Map<String, Object> dto = withSla(detailDto(t));
		dto.put("messages", messages.findByTicketIdOrderByCreatedAtAsc(id).stream().map(this::messageDto).toList());
		return dto;
	}

	@Transactional
	public Map<String, Object> create(UserPrincipal user, Map<String, Object> body) {
		ensureContact(user, opt(body, "contactId"));
		ensureAssignee(user, opt(body, "assigneeId"));
		TicketPriority priority = body.get("priority") != null
				? TicketPriority.valueOf(String.valueOf(body.get("priority")))
				: TicketPriority.MEDIUM;
		Ticket t = new Ticket();
		t.setTicketNumber(nextTicketNumber(user.getCompanyId()));
		t.setTitle(req(body, "title"));
		t.setDescription(req(body, "description"));
		t.setPriority(priority);
		t.setCompanyId(user.getCompanyId());
		t.setContactId(emptyToNull(opt(body, "contactId")));
		t.setAssigneeId(emptyToNull(opt(body, "assigneeId")));
		t.setCreatedById(user.getId());
		t.setSlaDueAt(slaDueAt(priority, Instant.now()));
		tickets.save(t);
		TicketMessage msg = new TicketMessage();
		msg.setTicketId(t.getId());
		msg.setBody(t.getDescription());
		msg.setInternal(false);
		msg.setAuthorId(user.getId());
		messages.save(msg);
		audit.created(user, "TICKET", t.getId(), label(t));
		return withSla(detailDto(t));
	}

	@Transactional
	public Map<String, Object> update(UserPrincipal user, String id, Map<String, Object> body) {
		Ticket existing = tickets.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Ticket not found", HttpStatus.NOT_FOUND));
		TicketPriority priority = body.get("priority") != null
				? TicketPriority.valueOf(String.valueOf(body.get("priority")))
				: existing.getPriority();
		if (body.containsKey("contactId")) ensureContact(user, opt(body, "contactId"));
		if (body.containsKey("assigneeId")) ensureAssignee(user, opt(body, "assigneeId"));
		Map<String, Object> before = listDto(existing);
		if (body.containsKey("title")) existing.setTitle(req(body, "title"));
		if (body.containsKey("description")) existing.setDescription(req(body, "description"));
		if (body.containsKey("priority")) {
			existing.setPriority(priority);
			if (existing.getFirstRespondedAt() == null) {
				existing.setSlaDueAt(slaDueAt(priority, existing.getCreatedAt()));
			}
		}
		if (body.containsKey("status")) {
			TicketStatus status = TicketStatus.valueOf(String.valueOf(body.get("status")));
			existing.setStatus(status);
			if (status == TicketStatus.RESOLVED && existing.getResolvedAt() == null) {
				existing.setResolvedAt(Instant.now());
			}
			if (status == TicketStatus.CLOSED) {
				existing.setClosedAt(Instant.now());
			}
			if (status == TicketStatus.IN_PROGRESS && existing.getFirstRespondedAt() == null) {
				existing.setFirstRespondedAt(Instant.now());
			}
		}
		if (body.containsKey("contactId")) existing.setContactId(emptyToNull(opt(body, "contactId")));
		if (body.containsKey("assigneeId")) existing.setAssigneeId(emptyToNull(opt(body, "assigneeId")));
		tickets.save(existing);
		audit.updated(user, "TICKET", id, label(existing), before, listDto(existing), AUDIT_FIELDS);
		Map<String, Object> dto = withSla(detailDto(existing));
		dto.put("messages", messages.findByTicketIdOrderByCreatedAtAsc(id).stream().map(this::messageDto).toList());
		return dto;
	}

	@Transactional
	public Map<String, Object> addMessage(UserPrincipal user, String ticketId, Map<String, Object> body) {
		Ticket ticket = tickets.findByIdAndCompanyId(ticketId, user.getCompanyId())
				.orElseThrow(() -> new AppException("Ticket not found", HttpStatus.NOT_FOUND));
		boolean isInternal = body.get("isInternal") != null && Boolean.parseBoolean(String.valueOf(body.get("isInternal")));
		TicketMessage msg = new TicketMessage();
		msg.setTicketId(ticketId);
		msg.setBody(req(body, "body"));
		msg.setInternal(isInternal);
		msg.setAuthorId(user.getId());
		messages.save(msg);
		if (ticket.getFirstRespondedAt() == null && !isInternal) {
			ticket.setFirstRespondedAt(Instant.now());
		}
		if (ticket.getStatus() == TicketStatus.OPEN && !isInternal) {
			ticket.setStatus(TicketStatus.IN_PROGRESS);
		}
		tickets.save(ticket);
		return messageDto(msg);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> slaAlerts(UserPrincipal user) {
		return list(user, null, null, false).stream()
				.filter(t -> Boolean.TRUE.equals(t.get("slaBreached")) || Boolean.TRUE.equals(t.get("slaDueSoon")))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> listKb(UserPrincipal user) {
		return kbArticles.findByCompanyIdOrderByUpdatedAtDesc(user.getCompanyId()).stream()
				.map(this::kbDto).toList();
	}

	@Transactional
	public Map<String, Object> createKb(UserPrincipal user, Map<String, Object> body) {
		String title = req(body, "title");
		String slugBase = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
		if (slugBase.length() > 80) slugBase = slugBase.substring(0, 80);
		String suffix = Long.toString(System.currentTimeMillis(), 36);
		if (suffix.length() > 4) suffix = suffix.substring(suffix.length() - 4);
		KbArticle a = new KbArticle();
		a.setTitle(title);
		a.setSlug(slugBase + "-" + suffix);
		a.setBody(req(body, "body"));
		a.setPublished(body.get("published") == null || Boolean.parseBoolean(String.valueOf(body.get("published"))));
		a.setCompanyId(user.getCompanyId());
		a.setCreatedById(user.getId());
		kbArticles.save(a);
		return kbDto(a);
	}

	@Transactional
	public Map<String, Object> portalInfo(UserPrincipal user) {
		TenantCompany company = companies.findById(user.getCompanyId())
				.orElseThrow(() -> new AppException("Company not found", HttpStatus.NOT_FOUND));
		if (company.getPortalSlug() == null || company.getPortalSlug().isBlank()) {
			String portalSlug = "workspace-" + company.getId().substring(Math.max(0, company.getId().length() - 6));
			company.setPortalSlug(portalSlug);
			companies.save(company);
		}
		return JsonMaps.of("id", company.getId(), "name", company.getName(), "portalSlug", company.getPortalSlug());
	}

	@Transactional
	public Map<String, Object> createPortalTicket(String portalSlug, Map<String, Object> body) {
		TenantCompany company = companies.findByPortalSlug(portalSlug)
				.orElseThrow(() -> new AppException("Portal not found", HttpStatus.NOT_FOUND));
		String email = req(body, "requesterEmail").toLowerCase();
		Contact contact = contacts.findFirstByCompanyIdAndEmailIgnoreCase(company.getId(), email).orElseGet(() -> {
			Contact c = new Contact();
			c.setName(req(body, "requesterName"));
			c.setEmail(email);
			c.setCompanyId(company.getId());
			return contacts.save(c);
		});
		TicketPriority priority = body.get("priority") != null
				? TicketPriority.valueOf(String.valueOf(body.get("priority")))
				: TicketPriority.MEDIUM;
		Ticket t = new Ticket();
		t.setTicketNumber(nextTicketNumber(company.getId()));
		t.setTitle(req(body, "title"));
		t.setDescription(req(body, "description"));
		t.setPriority(priority);
		t.setCompanyId(company.getId());
		t.setContactId(contact.getId());
		t.setRequesterEmail(email);
		t.setRequesterName(req(body, "requesterName"));
		t.setSlaDueAt(slaDueAt(priority, Instant.now()));
		tickets.save(t);
		TicketMessage msg = new TicketMessage();
		msg.setTicketId(t.getId());
		msg.setBody(t.getDescription());
		msg.setInternal(false);
		msg.setAuthorName(t.getRequesterName());
		messages.save(msg);
		return JsonMaps.of(
				"id", t.getId(),
				"ticketNumber", t.getTicketNumber(),
				"title", t.getTitle(),
				"status", t.getStatus().name(),
				"priority", t.getPriority().name(),
				"createdAt", JsonMaps.iso(t.getCreatedAt()));
	}

	@Transactional(readOnly = true)
	public Map<String, Object> trackPortalTicket(String portalSlug, String email, String ticketNumber) {
		TenantCompany company = companies.findByPortalSlug(portalSlug)
				.orElseThrow(() -> new AppException("Portal not found", HttpStatus.NOT_FOUND));
		Ticket ticket = tickets.findByCompanyIdAndTicketNumber(company.getId(), ticketNumber)
				.orElseThrow(() -> new AppException("Ticket not found", HttpStatus.NOT_FOUND));
		String lower = email.toLowerCase();
		boolean allowed = lower.equalsIgnoreCase(ticket.getRequesterEmail())
				|| (ticket.getContactId() != null && contacts.findById(ticket.getContactId())
						.map(c -> lower.equalsIgnoreCase(c.getEmail())).orElse(false));
		if (!allowed) {
			throw new AppException("Ticket not found", HttpStatus.NOT_FOUND);
		}
		Map<String, Object> dto = withSla(detailDto(ticket));
		dto.put("messages", messages.findByTicketIdAndIsInternalFalseOrderByCreatedAtAsc(ticket.getId()).stream()
				.map(m -> JsonMaps.of("id", m.getId(), "body", m.getBody(), "authorName", m.getAuthorName(),
						"createdAt", JsonMaps.iso(m.getCreatedAt())))
				.toList());
		return dto;
	}

	@Transactional
	public Map<String, Object> addPortalReply(String portalSlug, String email, String ticketNumber, String body) {
		Map<String, Object> tracked = trackPortalTicket(portalSlug, email, ticketNumber);
		if ("CLOSED".equals(tracked.get("status"))) {
			throw new AppException("Ticket is closed", HttpStatus.BAD_REQUEST);
		}
		String ticketId = String.valueOf(tracked.get("id"));
		Ticket ticket = tickets.findById(ticketId).orElseThrow();
		TicketMessage msg = new TicketMessage();
		msg.setTicketId(ticketId);
		msg.setBody(body);
		msg.setInternal(false);
		msg.setAuthorName(ticket.getRequesterName() != null ? ticket.getRequesterName() : email);
		messages.save(msg);
		if (ticket.getStatus() == TicketStatus.RESOLVED) {
			ticket.setStatus(TicketStatus.OPEN);
		}
		tickets.save(ticket);
		return JsonMaps.of(
				"id", msg.getId(),
				"ticketId", msg.getTicketId(),
				"body", msg.getBody(),
				"isInternal", msg.isInternal(),
				"authorName", msg.getAuthorName(),
				"createdAt", JsonMaps.iso(msg.getCreatedAt()));
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> listPublicKb(String portalSlug) {
		TenantCompany company = companies.findByPortalSlug(portalSlug)
				.orElseThrow(() -> new AppException("Portal not found", HttpStatus.NOT_FOUND));
		return kbArticles.findByCompanyIdAndPublishedTrueOrderByTitleAsc(company.getId()).stream()
				.map(a -> JsonMaps.of("id", a.getId(), "title", a.getTitle(), "slug", a.getSlug(), "body", a.getBody(),
						"updatedAt", JsonMaps.iso(a.getUpdatedAt())))
				.toList();
	}

	private String nextTicketNumber(String companyId) {
		return "T-" + String.format("%05d", tickets.maxTicketSequence(companyId) + 1);
	}

	private Instant slaDueAt(TicketPriority priority, Instant from) {
		return from.plusSeconds(SLA_HOURS.get(priority) * 3600L);
	}

	private Map<String, Object> withSla(Map<String, Object> ticket) {
		Instant now = Instant.now();
		String status = String.valueOf(ticket.get("status"));
		boolean open = "OPEN".equals(status) || "IN_PROGRESS".equals(status);
		Instant slaDueAt = ticket.get("slaDueAt") == null ? null : Instant.parse(String.valueOf(ticket.get("slaDueAt")));
		boolean breached = open && slaDueAt != null && slaDueAt.isBefore(now) && ticket.get("firstRespondedAt") == null;
		boolean dueSoon = open && !breached && slaDueAt != null && ticket.get("firstRespondedAt") == null
				&& slaDueAt.toEpochMilli() - now.toEpochMilli() <= 4 * 3600_000L;
		ticket.put("slaBreached", breached);
		ticket.put("slaDueSoon", dueSoon);
		return ticket;
	}

	private static String label(Ticket t) {
		return t.getTicketNumber() + " – " + t.getTitle();
	}

	private void ensureContact(UserPrincipal user, String contactId) {
		String id = emptyToNull(contactId);
		if (id == null) return;
		contacts.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Contact not found", HttpStatus.NOT_FOUND));
	}

	private void ensureAssignee(UserPrincipal user, String assigneeId) {
		String id = emptyToNull(assigneeId);
		if (id == null) return;
		users.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Assignee not found", HttpStatus.BAD_REQUEST));
	}

	private Map<String, Object> listDto(Ticket t) {
		Map<String, Object> dto = base(t);
		dto.put("contact", briefContact(t.getContactId()));
		dto.put("assignee", briefUser(t.getAssigneeId()));
		return dto;
	}

	private Map<String, Object> detailDto(Ticket t) {
		Map<String, Object> dto = listDto(t);
		dto.put("createdBy", briefUser(t.getCreatedById()));
		return dto;
	}

	private Map<String, Object> base(Ticket t) {
		return JsonMaps.of(
				"id", t.getId(),
				"ticketNumber", t.getTicketNumber(),
				"title", t.getTitle(),
				"description", t.getDescription(),
				"status", t.getStatus().name(),
				"priority", t.getPriority().name(),
				"companyId", t.getCompanyId(),
				"contactId", t.getContactId(),
				"assigneeId", t.getAssigneeId(),
				"createdById", t.getCreatedById(),
				"requesterEmail", t.getRequesterEmail(),
				"requesterName", t.getRequesterName(),
				"slaDueAt", JsonMaps.iso(t.getSlaDueAt()),
				"firstRespondedAt", JsonMaps.iso(t.getFirstRespondedAt()),
				"resolvedAt", JsonMaps.iso(t.getResolvedAt()),
				"closedAt", JsonMaps.iso(t.getClosedAt()),
				"createdAt", JsonMaps.iso(t.getCreatedAt()),
				"updatedAt", JsonMaps.iso(t.getUpdatedAt()));
	}

	private Map<String, Object> messageDto(TicketMessage m) {
		return JsonMaps.of(
				"id", m.getId(),
				"ticketId", m.getTicketId(),
				"body", m.getBody(),
				"isInternal", m.isInternal(),
				"authorId", m.getAuthorId(),
				"authorName", m.getAuthorName(),
				"createdAt", JsonMaps.iso(m.getCreatedAt()),
				"author", briefUser(m.getAuthorId()));
	}

	private Map<String, Object> kbDto(KbArticle a) {
		return JsonMaps.of(
				"id", a.getId(),
				"title", a.getTitle(),
				"slug", a.getSlug(),
				"body", a.getBody(),
				"published", a.isPublished(),
				"companyId", a.getCompanyId(),
				"createdById", a.getCreatedById(),
				"createdAt", JsonMaps.iso(a.getCreatedAt()),
				"updatedAt", JsonMaps.iso(a.getUpdatedAt()));
	}

	private Map<String, Object> briefUser(String id) {
		if (id == null) return null;
		return users.findById(id).map(u -> JsonMaps.of("id", u.getId(), "name", u.getName(), "email", u.getEmail())).orElse(null);
	}

	private Map<String, Object> briefContact(String id) {
		if (id == null) return null;
		return contacts.findById(id).map(c -> JsonMaps.of("id", c.getId(), "name", c.getName(), "email", c.getEmail())).orElse(null);
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
}
