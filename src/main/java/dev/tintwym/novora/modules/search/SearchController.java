package dev.tintwym.novora.modules.search;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.tintwym.novora.common.ApiResponse;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.repositories.ContactRepository;
import dev.tintwym.novora.repositories.CrmAccountRepository;
import dev.tintwym.novora.repositories.DealRepository;
import dev.tintwym.novora.repositories.TicketRepository;
import dev.tintwym.novora.security.Permission;
import dev.tintwym.novora.security.RolePermissions;
import dev.tintwym.novora.security.SecurityUtils;
import dev.tintwym.novora.security.UserPrincipal;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

	private static final int LIMIT = 5;

	private final ContactRepository contacts;
	private final CrmAccountRepository accounts;
	private final DealRepository deals;
	private final TicketRepository tickets;

	public SearchController(ContactRepository contacts, CrmAccountRepository accounts, DealRepository deals,
			TicketRepository tickets) {
		this.contacts = contacts;
		this.accounts = accounts;
		this.deals = deals;
		this.tickets = tickets;
	}

	@GetMapping
	@Transactional(readOnly = true)
	public ApiResponse<Map<String, Object>> search(@RequestParam(name = "q", required = false) String q) {
		UserPrincipal user = SecurityUtils.currentUser();
		String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
		if (needle.length() < 2) {
			return ApiResponse.success(JsonMaps.of(
					"contacts", List.of(), "companies", List.of(), "deals", List.of(), "tickets", List.of()));
		}
		Predicate<String> hit = s -> s != null && s.toLowerCase(Locale.ROOT).contains(needle);
		String companyId = user.getCompanyId();

		List<Map<String, Object>> contactHits = !can(user, Permission.VIEW_CONTACTS) ? List.of()
				: contacts.findByCompanyIdOrderByUpdatedAtDesc(companyId).stream()
						.filter(c -> hit.test(c.getName()) || hit.test(c.getEmail()) || hit.test(c.getPhone()))
						.limit(LIMIT)
						.map(c -> JsonMaps.of("id", c.getId(), "name", c.getName(), "email", c.getEmail(),
								"title", c.getTitle()))
						.toList();

		List<Map<String, Object>> companyHits = !can(user, Permission.VIEW_COMPANIES) ? List.of()
				: accounts.search(companyId, needle).stream()
						.limit(LIMIT)
						.map(a -> JsonMaps.of("id", a.getId(), "name", a.getName(), "industry", a.getIndustry()))
						.toList();

		List<Map<String, Object>> dealHits = List.of();
		if (can(user, Permission.VIEW_DEALS)) {
			var scoped = SecurityUtils.canViewAllDeals(user)
					? deals.findByCompanyIdOrderByStageAscUpdatedAtDesc(companyId)
					: deals.findByCompanyIdAndOwnerIdOrderByStageAscUpdatedAtDesc(companyId, user.getId());
			dealHits = scoped.stream()
					.filter(d -> hit.test(d.getTitle()))
					.limit(LIMIT)
					.map(d -> JsonMaps.of("id", d.getId(), "title", d.getTitle(), "stage", d.getStage().name(),
							"value", JsonMaps.num(d.getValue())))
					.toList();
		}

		List<Map<String, Object>> ticketHits = !can(user, Permission.VIEW_TICKETS) ? List.of()
				: tickets.findByCompanyIdOrderByPriorityDescUpdatedAtDesc(companyId).stream()
						.filter(t -> hit.test(t.getTitle()) || hit.test(t.getTicketNumber())
								|| hit.test(t.getRequesterEmail()))
						.limit(LIMIT)
						.map(t -> JsonMaps.of("id", t.getId(), "title", t.getTitle(),
								"ticketNumber", t.getTicketNumber(), "status", t.getStatus().name()))
						.toList();

		return ApiResponse.success(JsonMaps.of(
				"contacts", contactHits,
				"companies", companyHits,
				"deals", dealHits,
				"tickets", ticketHits));
	}

	private static boolean can(UserPrincipal user, Permission permission) {
		return RolePermissions.has(user.getRole(), permission);
	}
}
