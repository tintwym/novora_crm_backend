package dev.tintwym.novora.modules.companies;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.domain.entity.CrmAccount;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.repositories.ContactRepository;
import dev.tintwym.novora.repositories.CrmAccountRepository;
import dev.tintwym.novora.repositories.DealRepository;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.UserPrincipal;

@Service
public class CompaniesService {

	private final CrmAccountRepository accounts;
	private final UserEntityRepository users;
	private final ContactRepository contacts;
	private final DealRepository deals;
	private final AuditService audit;

	private static final String[] AUDIT_FIELDS = { "name", "industry", "size", "website", "phone", "email", "owner" };

	public CompaniesService(CrmAccountRepository accounts, UserEntityRepository users, ContactRepository contacts,
			DealRepository deals, AuditService audit) {
		this.audit = audit;
		this.accounts = accounts;
		this.users = users;
		this.contacts = contacts;
		this.deals = deals;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UserPrincipal user, String search) {
		List<CrmAccount> list = (search == null || search.isBlank())
				? accounts.findByCompanyIdOrderByNameAsc(user.getCompanyId())
				: accounts.search(user.getCompanyId(), search.trim());
		List<Map<String, Object>> out = new ArrayList<>();
		for (CrmAccount a : list) {
			out.add(toListDto(a));
		}
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(UserPrincipal user, String id) {
		CrmAccount a = accounts.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Company not found", HttpStatus.NOT_FOUND));
		Map<String, Object> dto = base(a);
		dto.put("owner", owner(a.getOwnerId()));
		dto.put("contacts", contacts.findByCompanyIdAndAccountIdOrderByUpdatedAtDesc(user.getCompanyId(), id).stream()
				.map(c -> JsonMaps.of("id", c.getId(), "name", c.getName(), "email", c.getEmail(), "phone", c.getPhone(),
						"title", c.getTitle()))
				.toList());
		dto.put("deals", deals.findByAccountIdOrderByUpdatedAtDesc(id).stream().limit(20)
				.map(d -> JsonMaps.of("id", d.getId(), "title", d.getTitle(), "value", JsonMaps.num(d.getValue()),
						"stage", d.getStage().name(), "closeDate", JsonMaps.iso(d.getCloseDate())))
				.toList());
		return dto;
	}

	@Transactional
	public Map<String, Object> create(UserPrincipal user, Map<String, Object> body) {
		CrmAccount a = new CrmAccount();
		a.setName(req(body, "name"));
		a.setIndustry(emptyToNull(opt(body, "industry")));
		a.setSize(emptyToNull(opt(body, "size")));
		a.setWebsite(emptyToNull(opt(body, "website")));
		a.setPhone(emptyToNull(opt(body, "phone")));
		a.setEmail(emptyToNull(opt(body, "email")));
		String ownerId = emptyToNull(opt(body, "ownerId"));
		a.setOwnerId(ownerId != null ? ensureOwner(user, ownerId) : user.getId());
		a.setCompanyId(user.getCompanyId());
		accounts.save(a);
		audit.created(user, "COMPANY", a.getId(), a.getName());
		return toListDto(a);
	}

	@Transactional
	public Map<String, Object> update(UserPrincipal user, String id, Map<String, Object> body) {
		CrmAccount a = accounts.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Company not found", HttpStatus.NOT_FOUND));
		Map<String, Object> before = toListDto(a);
		if (body.containsKey("name")) a.setName(req(body, "name"));
		if (body.containsKey("industry")) a.setIndustry(emptyToNull(opt(body, "industry")));
		if (body.containsKey("size")) a.setSize(emptyToNull(opt(body, "size")));
		if (body.containsKey("website")) a.setWebsite(emptyToNull(opt(body, "website")));
		if (body.containsKey("phone")) a.setPhone(emptyToNull(opt(body, "phone")));
		if (body.containsKey("email")) a.setEmail(emptyToNull(opt(body, "email")));
		if (body.containsKey("ownerId")) {
			String ownerId = emptyToNull(opt(body, "ownerId"));
			a.setOwnerId(ownerId == null ? null : ensureOwner(user, ownerId));
		}
		accounts.save(a);
		Map<String, Object> after = toListDto(a);
		audit.updated(user, "COMPANY", a.getId(), a.getName(), before, after, AUDIT_FIELDS);
		return after;
	}

	@Transactional
	public void delete(UserPrincipal user, String id) {
		CrmAccount a = accounts.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Company not found", HttpStatus.NOT_FOUND));
		accounts.delete(a);
		audit.deleted(user, "COMPANY", a.getId(), a.getName());
	}

	private String ensureOwner(UserPrincipal user, String ownerId) {
		users.findByIdAndCompanyId(ownerId, user.getCompanyId())
				.orElseThrow(() -> new AppException("Owner not found", HttpStatus.BAD_REQUEST));
		return ownerId;
	}

	private Map<String, Object> toListDto(CrmAccount a) {
		Map<String, Object> dto = base(a);
		dto.put("owner", owner(a.getOwnerId()));
		dto.put("_count", JsonMaps.of("contacts", contacts.countByAccountId(a.getId()), "deals", deals.countByAccountId(a.getId())));
		return dto;
	}

	private Map<String, Object> base(CrmAccount a) {
		return JsonMaps.of(
				"id", a.getId(),
				"name", a.getName(),
				"industry", a.getIndustry(),
				"size", a.getSize(),
				"website", a.getWebsite(),
				"phone", a.getPhone(),
				"email", a.getEmail(),
				"companyId", a.getCompanyId(),
				"ownerId", a.getOwnerId(),
				"createdAt", JsonMaps.iso(a.getCreatedAt()),
				"updatedAt", JsonMaps.iso(a.getUpdatedAt()));
	}

	private Map<String, Object> owner(String ownerId) {
		if (ownerId == null) return null;
		return users.findById(ownerId)
				.map(u -> JsonMaps.of("id", u.getId(), "name", u.getName(), "email", u.getEmail()))
				.orElse(null);
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
