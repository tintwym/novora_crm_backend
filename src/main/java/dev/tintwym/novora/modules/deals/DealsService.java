package dev.tintwym.novora.modules.deals;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.FileStorage;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.common.LeadSource;
import dev.tintwym.novora.domain.entity.Activity;
import dev.tintwym.novora.domain.entity.Attachment;
import dev.tintwym.novora.domain.entity.Deal;
import dev.tintwym.novora.domain.enums.ActivityType;
import dev.tintwym.novora.domain.enums.DealStage;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.modules.automations.AutomationService;
import dev.tintwym.novora.repositories.ActivityRepository;
import dev.tintwym.novora.repositories.AttachmentRepository;
import dev.tintwym.novora.repositories.ContactRepository;
import dev.tintwym.novora.repositories.CrmAccountRepository;
import dev.tintwym.novora.repositories.DealRepository;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.SecurityUtils;
import dev.tintwym.novora.security.UserPrincipal;

@Service
public class DealsService {

	public static final Map<DealStage, Integer> STAGE_PROBABILITY = Map.of(
			DealStage.LEAD, 10,
			DealStage.QUALIFIED, 25,
			DealStage.PROPOSAL, 50,
			DealStage.NEGOTIATION, 75,
			DealStage.WON, 100,
			DealStage.LOST, 0);

	private static final DealStage[] DEAL_STAGES = DealStage.values();

	private final DealRepository deals;
	private final ContactRepository contacts;
	private final CrmAccountRepository accounts;
	private final UserEntityRepository users;
	private final ActivityRepository activities;
	private final AttachmentRepository attachments;
	private final FileStorage storage;
	private final AutomationService automations;
	private final AuditService audit;

	private static final String[] AUDIT_FIELDS = { "title", "value", "stage", "probability", "closeDate", "contact",
			"account", "owner", "source" };

	/** Types the browser may show inline; everything else is forced to download. */
	private static final Set<String> INLINE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp",
			"application/pdf");

	public DealsService(DealRepository deals, ContactRepository contacts, CrmAccountRepository accounts,
			UserEntityRepository users, ActivityRepository activities, AttachmentRepository attachments,
			FileStorage storage, AutomationService automations, AuditService audit) {
		this.audit = audit;
		this.storage = storage;
		this.automations = automations;
		this.deals = deals;
		this.contacts = contacts;
		this.accounts = accounts;
		this.users = users;
		this.activities = activities;
		this.attachments = attachments;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UserPrincipal user, String stage) {
		List<Deal> list;
		boolean all = SecurityUtils.canViewAllDeals(user);
		if (stage != null && !stage.isBlank()) {
			DealStage s = DealStage.valueOf(stage);
			list = all
					? deals.findByCompanyIdAndStageOrderByUpdatedAtDesc(user.getCompanyId(), s)
					: deals.findByCompanyIdAndOwnerIdAndStageOrderByUpdatedAtDesc(user.getCompanyId(), user.getId(), s);
		} else {
			list = all
					? deals.findByCompanyIdOrderByStageAscUpdatedAtDesc(user.getCompanyId())
					: deals.findByCompanyIdAndOwnerIdOrderByStageAscUpdatedAtDesc(user.getCompanyId(), user.getId());
		}
		return list.stream().map(this::toDto).toList();
	}

	@Transactional(readOnly = true)
	public Map<String, Object> pipeline(UserPrincipal user) {
		List<Map<String, Object>> all = list(user, null);
		List<Map<String, Object>> columns = new ArrayList<>();
		double totalOpen = 0;
		for (DealStage stage : DEAL_STAGES) {
			List<Map<String, Object>> stageDeals = all.stream().filter(d -> stage.name().equals(d.get("stage"))).toList();
			double total = stageDeals.stream().mapToDouble(d -> ((Number) d.get("value")).doubleValue()).sum();
			columns.add(JsonMaps.of("stage", stage.name(), "deals", stageDeals, "totalValue", total));
			if (stage != DealStage.WON && stage != DealStage.LOST) {
				totalOpen += total;
			}
		}
		return JsonMaps.of("columns", columns, "totalOpenValue", totalOpen);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(UserPrincipal user, String id) {
		Deal deal = findScoped(user, id);
		Map<String, Object> dto = toDto(deal);
		dto.put("activities", activities.findByDealIdAndCompanyIdOrderByDateDesc(id, user.getCompanyId()).stream()
				.map(a -> {
					Map<String, Object> m = activityDto(a);
					m.put("user", userBrief(a.getUserId()));
					return m;
				}).toList());
		dto.put("attachments", attachments.findByDealIdOrderByCreatedAtDesc(id).stream()
				.map(this::attachmentDto).toList());
		return dto;
	}

	@Transactional
	public Map<String, Object> create(UserPrincipal user, Map<String, Object> body) {
		ensureRefs(user, opt(body, "contactId"), opt(body, "accountId"));
		DealStage stage = body.get("stage") != null ? DealStage.valueOf(String.valueOf(body.get("stage"))) : DealStage.LEAD;
		Deal d = new Deal();
		d.setTitle(req(body, "title"));
		d.setValue(asDecimal(body.get("value"), BigDecimal.ZERO));
		d.setStage(stage);
		d.setCloseDate(parseDate(opt(body, "closeDate")));
		d.setContactId(emptyToNull(opt(body, "contactId")));
		d.setAccountId(emptyToNull(opt(body, "accountId")));
		String source = LeadSource.normalize(body.get("source"));
		if (source == null && d.getContactId() != null) {
			source = contacts.findById(d.getContactId()).map(c -> c.getSource()).orElse(null);
		}
		d.setSource(source);
		String ownerId = emptyToNull(opt(body, "ownerId"));
		d.setOwnerId(ownerId != null ? ensureOwner(user, ownerId) : user.getId());
		d.setProbability(body.get("probability") != null
				? probability(body.get("probability"))
				: STAGE_PROBABILITY.get(stage));
		d.setCompanyId(user.getCompanyId());
		deals.save(d);
		Map<String, Object> dto = toDto(d);
		audit.created(user, "DEAL", d.getId(), d.getTitle());
		dto.put("automationTasks", automations.onStageEntered(user, d, stage));
		return dto;
	}

	@Transactional
	public Map<String, Object> update(UserPrincipal user, String id, Map<String, Object> body) {
		Deal d = findScoped(user, id);
		ensureRefs(user, opt(body, "contactId"), opt(body, "accountId"));
		Map<String, Object> before = toDto(d);
		if (body.containsKey("title")) d.setTitle(String.valueOf(body.get("title")));
		if (body.containsKey("value")) d.setValue(asDecimal(body.get("value"), d.getValue()));
		DealStage previousStage = d.getStage();
		if (body.containsKey("stage")) {
			DealStage stage = DealStage.valueOf(String.valueOf(body.get("stage")));
			d.setStage(stage);
			d.setProbability(body.get("probability") != null
					? probability(body.get("probability"))
					: STAGE_PROBABILITY.get(stage));
		} else if (body.containsKey("probability")) {
			d.setProbability(probability(body.get("probability")));
		}
		if (body.containsKey("closeDate")) d.setCloseDate(parseDate(opt(body, "closeDate")));
		if (body.containsKey("contactId")) d.setContactId(emptyToNull(opt(body, "contactId")));
		if (body.containsKey("accountId")) d.setAccountId(emptyToNull(opt(body, "accountId")));
		if (body.containsKey("source")) d.setSource(LeadSource.normalize(body.get("source")));
		if (body.containsKey("ownerId")) {
			String ownerId = emptyToNull(opt(body, "ownerId"));
			d.setOwnerId(ownerId == null ? null : ensureOwner(user, ownerId));
		}
		deals.save(d);
		Map<String, Object> dto = toDto(d);
		audit.updated(user, "DEAL", d.getId(), d.getTitle(), before, dto, AUDIT_FIELDS);
		dto.put("automationTasks", d.getStage() != previousStage
				? automations.onStageEntered(user, d, d.getStage())
				: List.of());
		return dto;
	}

	private String ensureOwner(UserPrincipal user, String ownerId) {
		users.findByIdAndCompanyId(ownerId, user.getCompanyId())
				.orElseThrow(() -> new AppException("Owner not found", HttpStatus.BAD_REQUEST));
		return ownerId;
	}

	private static int probability(Object v) {
		double p;
		try {
			p = v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).trim());
		} catch (NumberFormatException e) {
			throw new AppException("Probability must be a number", HttpStatus.BAD_REQUEST);
		}
		if (Double.isNaN(p) || p < 0 || p > 100) {
			throw new AppException("Probability must be between 0 and 100", HttpStatus.BAD_REQUEST);
		}
		return (int) Math.round(p);
	}

	@Transactional
	public Map<String, Object> updateStage(UserPrincipal user, String id, Map<String, Object> body) {
		return update(user, id, Map.of("stage", req(body, "stage")));
	}

	@Transactional
	public void delete(UserPrincipal user, String id) {
		Deal deal = findScoped(user, id);
		List<String> keys = attachments.findByDealIdOrderByCreatedAtDesc(deal.getId()).stream()
				.map(Attachment::getStorageKey).filter(Objects::nonNull).toList();
		deals.delete(deal);
		audit.deleted(user, "DEAL", deal.getId(), deal.getTitle());
		deleteFilesAfterCommit(keys);
	}

	@Transactional
	public Map<String, Object> addActivity(UserPrincipal user, String dealId, Map<String, Object> body) {
		Deal deal = findScoped(user, dealId);
		Activity a = new Activity();
		a.setType(ActivityType.valueOf(req(body, "type")));
		a.setSubject(emptyToNull(opt(body, "subject")));
		a.setNote(emptyToNull(opt(body, "note")));
		a.setDueAt(parseDate(opt(body, "dueAt")));
		a.setDate(parseDate(opt(body, "date")) != null ? parseDate(opt(body, "date")) : Instant.now());
		a.setCompanyId(user.getCompanyId());
		a.setUserId(user.getId());
		a.setDealId(dealId);
		ensureRefs(user, opt(body, "contactId"), null);
		a.setContactId(body.get("contactId") != null ? String.valueOf(body.get("contactId")) : deal.getContactId());
		activities.save(a);
		Map<String, Object> dto = activityDto(a);
		dto.put("user", userBrief(a.getUserId()));
		return dto;
	}

	@Transactional
	public Map<String, Object> addAttachment(UserPrincipal user, String dealId, Map<String, Object> body) {
		Deal deal = findScoped(user, dealId);
		String url = req(body, "fileUrl");
		String lower = url.toLowerCase();
		if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
			throw new AppException("Links must start with http:// or https://", HttpStatus.BAD_REQUEST);
		}
		Attachment att = new Attachment();
		att.setFileName(cleanFileName(req(body, "fileName")));
		att.setFileUrl(url);
		att.setMimeType(emptyToNull(opt(body, "mimeType")));
		att.setCompanyId(user.getCompanyId());
		att.setDealId(dealId);
		att.setUploadedBy(user.getId());
		attachments.save(att);
		auditAttachment(user, AuditService.CREATE, att, deal);
		return attachmentDto(att);
	}

	@Transactional
	public Map<String, Object> uploadAttachment(UserPrincipal user, String dealId, MultipartFile file) {
		Deal deal = findScoped(user, dealId);
		if (file == null || file.isEmpty()) {
			throw new AppException("Choose a file to upload", HttpStatus.BAD_REQUEST);
		}
		String key;
		try (InputStream in = file.getInputStream()) {
			key = storage.save(user.getCompanyId(), in);
		} catch (IOException e) {
			throw new AppException("Couldn't read the uploaded file", HttpStatus.BAD_REQUEST);
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) storage.delete(key);
			}
		});
		Attachment att = new Attachment();
		att.setFileName(cleanFileName(file.getOriginalFilename()));
		att.setStorageKey(key);
		String type = file.getContentType();
		att.setMimeType(type == null || type.isBlank() || type.length() > 120 ? null : type.toLowerCase());
		att.setSizeBytes((int) Math.min(Integer.MAX_VALUE, file.getSize()));
		att.setCompanyId(user.getCompanyId());
		att.setDealId(dealId);
		att.setUploadedBy(user.getId());
		attachments.save(att);
		auditAttachment(user, AuditService.CREATE, att, deal);
		return attachmentDto(att);
	}

	public record FileDownload(Path path, String fileName, String contentType, boolean inline) {}

	@Transactional(readOnly = true)
	public FileDownload download(UserPrincipal user, String attachmentId) {
		Attachment att = scopedAttachment(user, attachmentId);
		if (att.getStorageKey() == null) {
			throw new AppException("This attachment is a link, not an uploaded file", HttpStatus.BAD_REQUEST);
		}
		String type = att.getMimeType();
		boolean inline = type != null && INLINE_TYPES.contains(type);
		return new FileDownload(storage.path(att.getStorageKey()), att.getFileName(),
				inline ? type : "application/octet-stream", inline);
	}

	@Transactional
	public void deleteAttachment(UserPrincipal user, String attachmentId) {
		Attachment att = scopedAttachment(user, attachmentId);
		if (!user.getId().equals(att.getUploadedBy()) && !SecurityUtils.canViewAllDeals(user)) {
			throw new AppException("Only the person who added this file or a manager can remove it",
					HttpStatus.FORBIDDEN);
		}
		attachments.delete(att);
		auditAttachment(user, AuditService.DELETE, att,
				att.getDealId() == null ? null : deals.findById(att.getDealId()).orElse(null));
		if (att.getStorageKey() != null) deleteFilesAfterCommit(List.of(att.getStorageKey()));
	}

	private void auditAttachment(UserPrincipal user, String action, Attachment att, Deal deal) {
		audit.record(user, action, "ATTACHMENT", att.getId(), att.getFileName(),
				deal == null ? null : JsonMaps.of("deal", deal.getTitle(), "dealId", deal.getId()));
	}

	private Attachment scopedAttachment(UserPrincipal user, String attachmentId) {
		Attachment att = attachments.findByIdAndCompanyId(attachmentId, user.getCompanyId())
				.orElseThrow(() -> new AppException("Attachment not found", HttpStatus.NOT_FOUND));
		if (att.getDealId() != null) {
			try {
				findScoped(user, att.getDealId());
			} catch (AppException e) {
				throw new AppException("Attachment not found", HttpStatus.NOT_FOUND);
			}
		}
		return att;
	}

	private void deleteFilesAfterCommit(List<String> keys) {
		if (keys.isEmpty()) return;
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				keys.forEach(storage::delete);
			}
		});
	}

	private static String cleanFileName(String name) {
		String n = name == null ? "" : name;
		n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
		n = n.replaceAll("[\\p{Cntrl}\"]", "").trim();
		if (n.isEmpty()) n = "file";
		return n.length() > 200 ? n.substring(0, 200) : n;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> forecast(UserPrincipal user) {
		boolean all = SecurityUtils.canViewAllDeals(user);
		List<Deal> list = all
				? deals.findByCompanyIdAndStageNotOrderByUpdatedAtDesc(user.getCompanyId(), DealStage.LOST)
				: deals.findByCompanyIdAndOwnerIdAndStageNotOrderByUpdatedAtDesc(user.getCompanyId(), user.getId(), DealStage.LOST);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Deal d : list) {
			double value = d.getValue().doubleValue();
			double weighted = value * d.getProbability() / 100.0;
			Map<String, Object> row = JsonMaps.of(
					"id", d.getId(),
					"title", d.getTitle(),
					"value", value,
					"stage", d.getStage().name(),
					"probability", d.getProbability(),
					"closeDate", JsonMaps.iso(d.getCloseDate()),
					"weightedValue", weighted,
					"owner", userBrief(d.getOwnerId()));
			rows.add(row);
		}
		List<Map<String, Object>> byStage = new ArrayList<>();
		for (DealStage stage : DEAL_STAGES) {
			if (stage == DealStage.LOST) continue;
			List<Map<String, Object>> stageDeals = rows.stream().filter(r -> stage.name().equals(r.get("stage"))).toList();
			byStage.add(JsonMaps.of(
					"stage", stage.name(),
					"count", stageDeals.size(),
					"pipelineValue", stageDeals.stream().mapToDouble(r -> ((Number) r.get("value")).doubleValue()).sum(),
					"weightedValue", stageDeals.stream().mapToDouble(r -> ((Number) r.get("weightedValue")).doubleValue()).sum()));
		}
		Map<String, Map<String, Object>> byOwnerMap = new LinkedHashMap<>();
		for (Map<String, Object> d : rows) {
			@SuppressWarnings("unchecked")
			Map<String, Object> owner = (Map<String, Object>) d.get("owner");
			String key = owner != null ? String.valueOf(owner.get("id")) : "unassigned";
			Map<String, Object> current = byOwnerMap.computeIfAbsent(key, k -> JsonMaps.of(
					"ownerId", key,
					"ownerName", owner != null ? owner.get("name") : "Unassigned",
					"pipelineValue", 0.0,
					"weightedValue", 0.0,
					"count", 0));
			current.put("pipelineValue", ((Number) current.get("pipelineValue")).doubleValue() + ((Number) d.get("value")).doubleValue());
			current.put("weightedValue", ((Number) current.get("weightedValue")).doubleValue() + ((Number) d.get("weightedValue")).doubleValue());
			current.put("count", ((Number) current.get("count")).intValue() + 1);
		}
		return JsonMaps.of(
				"summary", JsonMaps.of(
						"dealCount", rows.size(),
						"pipelineValue", rows.stream().mapToDouble(r -> ((Number) r.get("value")).doubleValue()).sum(),
						"weightedForecast", rows.stream().mapToDouble(r -> ((Number) r.get("weightedValue")).doubleValue()).sum()),
				"byStage", byStage,
				"byOwner", new ArrayList<>(byOwnerMap.values()),
				"deals", rows);
	}

	private Deal findScoped(UserPrincipal user, String id) {
		Deal deal = deals.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Deal not found", HttpStatus.NOT_FOUND));
		if (!SecurityUtils.canViewAllDeals(user) && (deal.getOwnerId() == null || !deal.getOwnerId().equals(user.getId()))) {
			throw new AppException("Deal not found", HttpStatus.NOT_FOUND);
		}
		return deal;
	}

	private void ensureRefs(UserPrincipal user, String contactId, String accountId) {
		if (contactId != null && !contactId.isBlank()) {
			contacts.findByIdAndCompanyId(contactId, user.getCompanyId())
					.orElseThrow(() -> new AppException("Contact not found", HttpStatus.NOT_FOUND));
		}
		if (accountId != null && !accountId.isBlank()) {
			accounts.findByIdAndCompanyId(accountId, user.getCompanyId())
					.orElseThrow(() -> new AppException("Company not found", HttpStatus.NOT_FOUND));
		}
	}

	private Map<String, Object> toDto(Deal d) {
		return JsonMaps.of(
				"id", d.getId(),
				"title", d.getTitle(),
				"value", JsonMaps.num(d.getValue()),
				"stage", d.getStage().name(),
				"closeDate", JsonMaps.iso(d.getCloseDate()),
				"companyId", d.getCompanyId(),
				"contactId", d.getContactId(),
				"accountId", d.getAccountId(),
				"ownerId", d.getOwnerId(),
				"probability", d.getProbability(),
				"source", d.getSource(),
				"createdAt", JsonMaps.iso(d.getCreatedAt()),
				"updatedAt", JsonMaps.iso(d.getUpdatedAt()),
				"contact", d.getContactId() == null ? null : contacts.findById(d.getContactId())
						.map(c -> JsonMaps.of("id", c.getId(), "name", c.getName(), "email", c.getEmail())).orElse(null),
				"account", d.getAccountId() == null ? null : accounts.findById(d.getAccountId())
						.map(a -> JsonMaps.of("id", a.getId(), "name", a.getName())).orElse(null),
				"owner", userBrief(d.getOwnerId()));
	}

	private Map<String, Object> activityDto(Activity a) {
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
				"updatedAt", JsonMaps.iso(a.getUpdatedAt()));
	}

	private Map<String, Object> attachmentDto(Attachment a) {
		return JsonMaps.of(
				"id", a.getId(),
				"fileName", a.getFileName(),
				"fileUrl", a.getFileUrl(),
				"uploaded", a.getStorageKey() != null,
				"mimeType", a.getMimeType(),
				"sizeBytes", a.getSizeBytes(),
				"companyId", a.getCompanyId(),
				"dealId", a.getDealId(),
				"uploadedBy", a.getUploadedBy(),
				"uploader", userBrief(a.getUploadedBy()),
				"createdAt", JsonMaps.iso(a.getCreatedAt()));
	}

	private Map<String, Object> userBrief(String id) {
		if (id == null) return null;
		return users.findById(id).map(u -> JsonMaps.of("id", u.getId(), "name", u.getName(), "email", u.getEmail())).orElse(null);
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

	private static Instant parseDate(String value) {
		if (value == null || value.isBlank() || "null".equals(value)) return null;
		return Instant.parse(value.contains("T") ? value : value + "T00:00:00Z");
	}

	private static BigDecimal asDecimal(Object v, BigDecimal def) {
		if (v == null) return def;
		return new BigDecimal(String.valueOf(v));
	}
}
