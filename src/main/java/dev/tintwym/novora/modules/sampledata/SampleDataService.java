package dev.tintwym.novora.modules.sampledata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.Cuid;
import dev.tintwym.novora.common.FileStorage;
import dev.tintwym.novora.modules.tickets.TicketsService;
import dev.tintwym.novora.domain.enums.TicketPriority;
import dev.tintwym.novora.repositories.QuoteRepository;
import dev.tintwym.novora.repositories.TicketRepository;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.security.UserPrincipal;

/**
 * Loads a realistic set of demo records into a workspace and removes exactly those records again.
 * Every inserted row is registered in {@code sample_records}, so real data is never touched.
 */
@Service
public class SampleDataService {

	private static final String[] ENTITY_TYPES = { "USER", "ACCOUNT", "CONTACT", "DEAL", "ACTIVITY", "NOTE", "TAG",
			"QUOTE", "PRODUCT", "TICKET", "KB_ARTICLE", "AUTOMATION" };

	private static final Map<String, Integer> STAGE_PROBABILITY = Map.of(
			"LEAD", 10, "QUALIFIED", 25, "PROPOSAL", 50, "NEGOTIATION", 75, "WON", 100, "LOST", 0);

	private record AccountSeed(String name, String industry, String size, String domain) {}

	private record ContactSeed(String name, String title, int account) {}

	private record DealRow(String id, String title, String stage, BigDecimal value, Instant createdAt,
			Instant closeDate, String contactId, String accountId, String ownerId) {}

	/** Weighted so the reports show a realistic spread rather than an even split. */
	private static final List<String> SOURCES = List.of("WEBSITE", "WEBSITE", "WEBSITE", "REFERRAL", "REFERRAL",
			"CAMPAIGN", "CAMPAIGN", "EVENT", "COLD_OUTREACH", "SOCIAL", "PARTNER");

	private static final List<AccountSeed> ACCOUNTS = List.of(
			new AccountSeed("Acme Logistics", "Logistics", "201-500", "acme-logistics.example"),
			new AccountSeed("Brightwave Media", "Media", "11-50", "brightwave.example"),
			new AccountSeed("Cedar Health Clinics", "Healthcare", "51-200", "cedarhealth.example"),
			new AccountSeed("Delta Robotics", "Manufacturing", "201-500", "deltarobotics.example"),
			new AccountSeed("Evergreen Foods", "Retail", "51-200", "evergreenfoods.example"),
			new AccountSeed("Fintrust Capital", "Finance", "11-50", "fintrust.example"),
			new AccountSeed("Golden Lotus Hotels", "Hospitality", "201-500", "goldenlotus.example"),
			new AccountSeed("Horizon Education", "Education", "51-200", "horizon-edu.example"));

	private static final List<ContactSeed> CONTACTS = List.of(
			new ContactSeed("Olivia Martin", "Operations Director", 0),
			new ContactSeed("Ethan Brooks", "IT Manager", 0),
			new ContactSeed("Sofia Ramirez", "Head of Marketing", 1),
			new ContactSeed("Liam Turner", "Creative Lead", 1),
			new ContactSeed("Aung Kyaw", "Clinic Administrator", 2),
			new ContactSeed("Hannah Lee", "Chief Medical Officer", 2),
			new ContactSeed("Noah Fischer", "Plant Manager", 3),
			new ContactSeed("Mia Wong", "Procurement Lead", 3),
			new ContactSeed("Lucas Silva", "CTO", 3),
			new ContactSeed("Emma Johnson", "Store Operations Manager", 4),
			new ContactSeed("Thiri Win", "Finance Manager", 4),
			new ContactSeed("James Carter", "Managing Partner", 5),
			new ContactSeed("Ava Patel", "Compliance Officer", 5),
			new ContactSeed("Kenji Sato", "General Manager", 6),
			new ContactSeed("Chloe Dubois", "Guest Experience Lead", 6),
			new ContactSeed("Benjamin Clark", "Dean of Admissions", 7),
			new ContactSeed("Zaw Min Htet", "IT Coordinator", 7),
			new ContactSeed("Grace Kim", "Freelance Consultant", -1),
			new ContactSeed("Oscar Nguyen", "Founder", -1),
			new ContactSeed("Isabella Rossi", "Event Planner", -1));

	private static final String[] DEAL_PRODUCTS = { "CRM rollout", "Annual support plan", "Analytics add-on",
			"Onboarding package", "Enterprise licence", "Integration project", "Renewal", "Expansion seats" };

	private final JdbcTemplate jdbc;
	private final PasswordEncoder passwordEncoder;
	private final TicketRepository tickets;
	private final QuoteRepository quotes;

	private final FileStorage storage;
	private final AuditService audit;

	public SampleDataService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, TicketRepository tickets,
			QuoteRepository quotes, FileStorage storage, AuditService audit) {
		this.audit = audit;
		this.storage = storage;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.tickets = tickets;
		this.quotes = quotes;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> status(UserPrincipal user) {
		Map<String, Object> counts = new LinkedHashMap<>();
		for (String type : ENTITY_TYPES) {
			counts.put(type, 0);
		}
		jdbc.query("select entity_type, count(*) from sample_records where company_id = ? group by entity_type",
				rs -> {
					counts.put(rs.getString(1), rs.getInt(2));
				}, user.getCompanyId());
		boolean loaded = counts.values().stream().anyMatch(v -> ((Integer) v) > 0);
		return new LinkedHashMap<>(Map.of("loaded", loaded, "counts", counts));
	}

	@Transactional
	public Map<String, Object> load(UserPrincipal user) {
		String companyId = user.getCompanyId();
		Integer existing = jdbc.queryForObject("select count(*) from sample_records where company_id = ?",
				Integer.class, companyId);
		if (existing != null && existing > 0) {
			throw new AppException("Sample data is already loaded", HttpStatus.CONFLICT);
		}

		Random rnd = new Random(companyId.hashCode());
		Instant now = Instant.now();
		String emailSuffix = companyId.substring(Math.max(0, companyId.length() - 6));

		List<String> owners = new ArrayList<>();
		owners.add(user.getId());
		owners.add(insertUser(companyId, "Maya Chen", "maya.chen", "SALES_REP", emailSuffix, now));
		owners.add(insertUser(companyId, "Daniel Okafor", "daniel.okafor", "SALES_REP", emailSuffix, now));
		owners.add(insertUser(companyId, "Priya Nair", "priya.nair", "SALES_MANAGER", emailSuffix, now));
		String supportId = insertUser(companyId, "Sam Rivera", "sam.rivera", "SUPPORT_AGENT", emailSuffix, now);

		List<String> accountIds = new ArrayList<>();
		for (AccountSeed a : ACCOUNTS) {
			String id = Cuid.generate();
			Instant created = daysAgo(now, 200 + rnd.nextInt(80));
			jdbc.update("""
					insert into accounts (id, name, industry, size, website, phone, email, company_id, owner_id,
					  created_at, updated_at)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", id, a.name(), a.industry(), a.size(), "https://" + a.domain(),
					"+1 555 01" + (10 + accountIds.size()), "hello@" + a.domain(), companyId,
					pick(rnd, owners), ts(created), ts(created));
			record(companyId, "ACCOUNT", id);
			accountIds.add(id);
		}

		List<String> contactIds = new ArrayList<>();
		List<String> contactSources = new ArrayList<>();
		List<Integer> contactAccount = new ArrayList<>();
		for (ContactSeed c : CONTACTS) {
			String id = Cuid.generate();
			String accountId = c.account() >= 0 ? accountIds.get(c.account()) : null;
			String domain = c.account() >= 0 ? ACCOUNTS.get(c.account()).domain() : "mail.example";
			String email = c.name().toLowerCase(Locale.ROOT).replace(' ', '.') + "@" + domain;
			Instant created = daysAgo(now, 3 + rnd.nextInt(260));
			String source = pick(rnd, SOURCES);
			jdbc.update("""
					insert into contacts (id, name, email, phone, title, source, company_id, account_id, owner_id,
					  custom_fields, created_at, updated_at)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?)
					""", id, c.name(), email, "+1 555 02" + (10 + contactIds.size()), c.title(), source, companyId,
					accountId, pick(rnd, owners), ts(created), ts(created));
			contactSources.add(source);
			record(companyId, "CONTACT", id);
			contactIds.add(id);
			contactAccount.add(c.account());
		}

		Map<String, String> tagIds = new LinkedHashMap<>();
		tagIds.put("VIP", ensureTag(companyId, "VIP", "#f59e0b"));
		tagIds.put("Partner", ensureTag(companyId, "Partner", "#6366f1"));
		tagIds.put("Newsletter", ensureTag(companyId, "Newsletter", "#10b981"));
		List<String> tagList = new ArrayList<>(tagIds.values());
		for (int i = 0; i < contactIds.size(); i++) {
			if (i % 3 == 0) {
				tagAssign(tagList.get(0), "CONTACT", contactIds.get(i));
			}
			if (i % 4 == 1) {
				tagAssign(tagList.get(1), "CONTACT", contactIds.get(i));
			}
			if (i % 2 == 0) {
				tagAssign(tagList.get(2), "CONTACT", contactIds.get(i));
			}
		}

		List<DealRow> deals = new ArrayList<>();
		for (int i = 0; i < 34; i++) {
			int contactIdx = rnd.nextInt(contactIds.size());
			int accountIdx = contactAccount.get(contactIdx);
			String accountName = accountIdx >= 0 ? ACCOUNTS.get(accountIdx).name() : CONTACTS.get(contactIdx).name();
			String title = accountName + " – " + DEAL_PRODUCTS[rnd.nextInt(DEAL_PRODUCTS.length)];
			int ageDays = i % 5 < 2 ? 1 + rnd.nextInt(28) : 30 + rnd.nextInt(240);
			Instant created = daysAgo(now, ageDays).minus(Duration.ofMinutes(rnd.nextInt(600)));
			String stage = stageForAge(rnd, ageDays);
			BigDecimal value = BigDecimal.valueOf(1500 + rnd.nextInt(58) * 1000L);
			Instant closeDate;
			if ("WON".equals(stage) || "LOST".equals(stage)) {
				Instant candidate = created.plus(Duration.ofDays(7 + rnd.nextInt(50)));
				closeDate = candidate.isAfter(now) ? now.minus(Duration.ofHours(6 + rnd.nextInt(40))) : candidate;
			} else {
				closeDate = now.plus(Duration.ofDays(5 + rnd.nextInt(60)));
			}
			Instant updated = closeDate.isAfter(now) ? created.plus(Duration.ofDays(1)) : closeDate;
			if (updated.isAfter(now)) {
				updated = now;
			}
			String id = Cuid.generate();
			String ownerId = owners.get(i % owners.size());
			String accountId = accountIdx >= 0 ? accountIds.get(accountIdx) : null;
			jdbc.update("""
					insert into deals (id, title, value, stage, close_date, company_id, contact_id, account_id,
					  owner_id, probability, source, created_at, updated_at)
					values (?, ?, ?, ?::"DealStage", ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", id, title, value, stage, ts(closeDate), companyId, contactIds.get(contactIdx), accountId,
					ownerId, STAGE_PROBABILITY.get(stage),
					rnd.nextInt(4) == 0 ? pick(rnd, SOURCES) : contactSources.get(contactIdx), ts(created), ts(updated));
			record(companyId, "DEAL", id);
			deals.add(new DealRow(id, title, stage, value, created, closeDate, contactIds.get(contactIdx), accountId,
					ownerId));
			if (i % 5 == 0) {
				tagAssign(tagList.get(0), "DEAL", id);
			}
		}

		String[][] pastActivities = {
				{ "CALL", "Discovery call", "Walked through current process and pain points." },
				{ "MEETING", "Product demo", "Demoed pipeline and reporting. Strong interest in analytics." },
				{ "EMAIL", "Sent pricing overview", "Shared pricing tiers and onboarding timeline." },
				{ "NOTE", "Budget confirmed", "Decision maker confirmed budget for this quarter." },
				{ "CALL", "Follow-up call", "Answered security and data-residency questions." },
				{ "MEETING", "Stakeholder workshop", "Mapped requirements with operations and IT." } };
		for (DealRow d : deals) {
			Instant end = d.closeDate().isAfter(now) ? now : d.closeDate();
			long spanMinutes = Math.max(60, Duration.between(d.createdAt(), end).toMinutes());
			int count = 1 + rnd.nextInt(3);
			for (int k = 0; k < count; k++) {
				String[] a = pastActivities[rnd.nextInt(pastActivities.length)];
				Instant date = d.createdAt().plus(Duration.ofMinutes((long) (rnd.nextDouble() * spanMinutes)));
				insertActivity(companyId, d.ownerId(), d.contactId(), d.id(), a[0], a[1], a[2], date, null, date);
			}
		}

		String[][] upcoming = {
				{ "TASK", "Prepare proposal", "Draft scope and pricing for review." },
				{ "CALL", "Check in on decision", "Confirm timeline with the buyer." },
				{ "MEETING", "Contract review", "Go through redlines with legal." },
				{ "TASK", "Send case study", "Share the logistics case study." },
				{ "EMAIL", "Follow up on quote", "Ask if the quote needs changes." } };
		List<DealRow> open = deals.stream()
				.filter(d -> !"WON".equals(d.stage()) && !"LOST".equals(d.stage()))
				.toList();
		int[] dueOffsetsHours = { -50, -20, -3, 4, 20, 30, 46, 72, 120, 200, 260, 330 };
		for (int k = 0; k < dueOffsetsHours.length && !open.isEmpty(); k++) {
			DealRow d = open.get(k % open.size());
			String[] a = upcoming[k % upcoming.length];
			Instant due = now.plus(Duration.ofHours(dueOffsetsHours[k]));
			Instant created = now.minus(Duration.ofDays(1 + rnd.nextInt(5)));
			insertActivity(companyId, d.ownerId(), d.contactId(), d.id(), a[0], a[1], a[2], created, due, null);
		}

		String[] noteBodies = { "Prefers WhatsApp for quick questions.", "Renewal decision is made every March.",
				"Introduced us to their regional office.", "Asked for a Burmese-language onboarding session.",
				"Interested in the customer portal for their own clients.", "Met at the Yangon tech expo." };
		for (int i = 0; i < noteBodies.length; i++) {
			String id = Cuid.generate();
			Instant created = daysAgo(now, 2 + rnd.nextInt(90));
			jdbc.update("""
					insert into notes (id, body, entity_type, entity_id, company_id, author_id, created_at, updated_at)
					values (?, ?, 'CONTACT'::"NoteEntityType", ?, ?, ?, ?, ?)
					""", id, noteBodies[i], contactIds.get(i * 3 % contactIds.size()), companyId, pick(rnd, owners),
					ts(created), ts(created));
			record(companyId, "NOTE", id);
		}

		insertQuotes(companyId, user.getId(), deals, now);
		insertTickets(companyId, user.getId(), supportId, contactIds, now, rnd);
		insertKbArticles(companyId, user.getId(), now);
		insertAutomations(companyId, user.getId(), now);
		audit.record(user, "LOAD", "SAMPLE_DATA", null, "Sample data", null);

		return status(user);
	}

	private void insertAutomations(String companyId, String creatorId, Instant now) {
		String[][] rules = {
				{ "Send proposal follow-up", "PROPOSAL", "CALL", "Follow up on the proposal for {deal}",
						"Check {contact} has everything they need to decide.", "3" },
				{ "Kick off won deals", "WON", "TASK", "Schedule onboarding for {company}",
						"Introduce the onboarding team and agree a start date.", "1" } };
		for (String[] r : rules) {
			String id = Cuid.generate();
			jdbc.update("""
					insert into automation_rules (id, company_id, name, trigger_stage, task_type, task_subject,
					  task_note, due_in_days, assign_to, is_active, created_by, created_at, updated_at)
					values (?, ?, ?, cast(? as "DealStage"), cast(? as "ActivityType"), ?, ?, ?, 'DEAL_OWNER', true, ?, ?, ?)
					""", id, companyId, r[0], r[1], r[2], r[3], r[4], Integer.parseInt(r[5]), creatorId, ts(now),
					ts(now));
			record(companyId, "AUTOMATION", id);
		}
	}

	@Transactional
	public Map<String, Object> remove(UserPrincipal user) {
		String c = user.getCompanyId();
		String sampleOf = "(select entity_id from sample_records where company_id = ? and entity_type = ?)";
		String sampleParents = "(select entity_id from sample_records where company_id = ? and entity_type in ('CONTACT','ACCOUNT','DEAL'))";

		jdbc.update("delete from tag_assignments where entity_id in " + sampleParents + " or tag_id in " + sampleOf,
				c, c, "TAG");
		jdbc.update("delete from notes where company_id = ? and (id in " + sampleOf + " or entity_id in "
				+ sampleParents + ")", c, c, "NOTE", c);
		jdbc.update("delete from automation_rules where company_id = ? and id in " + sampleOf, c, c, "AUTOMATION");
		jdbc.update("delete from tickets where company_id = ? and id in " + sampleOf, c, c, "TICKET");
		jdbc.update("delete from quotes where company_id = ? and id in " + sampleOf, c, c, "QUOTE");
		jdbc.update("delete from products where company_id = ? and id in " + sampleOf, c, c, "PRODUCT");
		jdbc.update("delete from activities where company_id = ? and id in " + sampleOf, c, c, "ACTIVITY");
		List<String> fileKeys = jdbc.queryForList("select storage_key from attachments where storage_key is not null"
				+ " and deal_id in " + sampleOf, String.class, c, "DEAL");
		jdbc.update("delete from deals where company_id = ? and id in " + sampleOf, c, c, "DEAL");
		if (!fileKeys.isEmpty()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					fileKeys.forEach(storage::delete);
				}
			});
		}
		jdbc.update("delete from contacts where company_id = ? and id in " + sampleOf, c, c, "CONTACT");
		jdbc.update("delete from accounts where company_id = ? and id in " + sampleOf, c, c, "ACCOUNT");
		jdbc.update("delete from kb_articles where company_id = ? and id in " + sampleOf, c, c, "KB_ARTICLE");
		jdbc.update("delete from tags where company_id = ? and id in " + sampleOf, c, c, "TAG");
		jdbc.update("delete from users where company_id = ? and id <> ? and id in " + sampleOf, c, user.getId(), c,
				"USER");
		Integer removed = jdbc.queryForObject("select count(*) from sample_records where company_id = ?",
				Integer.class, c);
		jdbc.update("delete from sample_records where company_id = ?", c);
		if (removed != null && removed > 0) {
			audit.record(user, "REMOVE", "SAMPLE_DATA", null, "Sample data", Map.of("records", removed));
		}
		return status(user);
	}

	private static final String[][] PRODUCTS = {
			{ "Enterprise licence (annual, per seat)", "NOV-ENT-SEAT", "180", "Full CRM access for one user for 12 months." },
			{ "Onboarding & data migration", "NOV-ONBOARD", "2400", "Guided setup and import of existing customer data." },
			{ "Premium support plan", "NOV-SUP-PREM", "1200", "Priority support with a 4-hour response target, billed yearly." },
			{ "Starter licence (annual, per seat)", "NOV-STD-SEAT", "90", "Core contacts, deals and tasks for one user." },
			{ "Team training workshop", "NOV-TRAIN", "800", "Half-day remote workshop for up to 15 people." },
			{ "Custom integration", "NOV-INTEG", "3500", "One custom connector to an in-house system." } };

	private List<String> insertProducts(String companyId, Instant now) {
		List<String> ids = new ArrayList<>();
		for (String[] p : PRODUCTS) {
			Integer taken = jdbc.queryForObject(
					"select count(*) from products where company_id = ? and lower(sku) = lower(?)", Integer.class,
					companyId, p[1]);
			String id = Cuid.generate();
			jdbc.update("""
					insert into products (id, company_id, name, sku, description, unit_price, is_active, created_at, updated_at)
					values (?, ?, ?, ?, ?, ?, true, ?, ?)
					""", id, companyId, p[0], taken != null && taken > 0 ? null : p[1], p[3], new BigDecimal(p[2]),
					ts(now), ts(now));
			record(companyId, "PRODUCT", id);
			ids.add(id);
		}
		return ids;
	}

	private void insertQuotes(String companyId, String creatorId, List<DealRow> deals, Instant now) {
		List<String> productIds = insertProducts(companyId, now);
		String[][] items = {
				{ PRODUCTS[0][0], "25", PRODUCTS[0][2] },
				{ PRODUCTS[1][0], "1", PRODUCTS[1][2] },
				{ PRODUCTS[2][0], "1", PRODUCTS[2][2] } };
		String[] wanted = { "PROPOSAL", "NEGOTIATION", "WON" };
		String[] statuses = { "SENT", "DRAFT", "ACCEPTED" };
		for (int i = 0; i < wanted.length; i++) {
			String stage = wanted[i];
			DealRow deal = deals.stream().filter(d -> stage.equals(d.stage())).findFirst().orElse(null);
			if (deal == null) {
				continue;
			}
			Instant created = deal.createdAt().plus(Duration.ofDays(2));
			if (created.isAfter(now)) {
				created = now.minus(Duration.ofHours(2));
			}
			String prefix = "Q-" + LocalDate.ofInstant(created, ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
			String number = prefix + "-" + String.format("%03d", quotes.maxQuoteSequence(companyId, prefix) + 1);
			BigDecimal subtotal = BigDecimal.ZERO;
			List<Object[]> lines = new ArrayList<>();
			for (int k = 0; k <= i % items.length + 1 && k < items.length; k++) {
				BigDecimal qty = new BigDecimal(items[k][1]);
				BigDecimal price = new BigDecimal(items[k][2]);
				BigDecimal amount = qty.multiply(price);
				subtotal = subtotal.add(amount);
				lines.add(new Object[] { items[k][0], qty, price, amount, k, productIds.get(k) });
			}
			BigDecimal taxRate = new BigDecimal("5.00");
			BigDecimal tax = subtotal.multiply(taxRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
			String id = Cuid.generate();
			String status = statuses[i];
			Instant sentAt = "DRAFT".equals(status) ? null : created.plus(Duration.ofHours(3));
			Instant respondedAt = "ACCEPTED".equals(status) ? created.plus(Duration.ofDays(3)) : null;
			if (respondedAt != null && respondedAt.isAfter(now)) {
				respondedAt = now;
			}
			jdbc.update("""
					insert into quotes (id, quote_number, title, status, currency, tax_rate, subtotal, tax_amount, total,
					  notes, terms, valid_until, sent_at, responded_at, company_id, deal_id, contact_id, account_id,
					  created_by_id, created_at, updated_at)
					values (?, ?, ?, ?::"QuoteStatus", 'USD', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", id, number, deal.title(), status, taxRate, subtotal, tax, subtotal.add(tax),
					"Thank you for considering Novora.", "Payment due within 30 days of invoice.",
					ts(created.plus(Duration.ofDays(30))), ts(sentAt), ts(respondedAt), companyId, deal.id(),
					deal.contactId(), deal.accountId(), creatorId, ts(created), ts(created));
			for (Object[] line : lines) {
				jdbc.update("""
						insert into quote_line_items (id, quote_id, description, quantity, unit_price, amount, sort_order, product_id)
						values (?, ?, ?, ?, ?, ?, ?, ?)
						""", Cuid.generate(), id, line[0], line[1], line[2], line[3], line[4], line[5]);
			}
			record(companyId, "QUOTE", id);
		}
	}

	private void insertTickets(String companyId, String adminId, String supportId, List<String> contactIds,
			Instant now, Random rnd) {
		Object[][] seeds = {
				{ "Cannot log in after password reset", "I reset my password but the login page still says invalid credentials.", "OPEN", TicketPriority.HIGH, 30 },
				{ "Invoice shows the wrong company name", "Our March invoice is addressed to our old trading name.", "IN_PROGRESS", TicketPriority.MEDIUM, 20 },
				{ "Export to Excel is missing columns", "The contacts export doesn't include phone numbers.", "OPEN", TicketPriority.LOW, 5 },
				{ "Portal is down for our customers", "Our customers get an error when opening the support portal.", "OPEN", TicketPriority.URGENT, 6 },
				{ "How do I add a new team member?", "We hired a new sales rep and want to give them access.", "RESOLVED", TicketPriority.LOW, 96 },
				{ "Deal stages need renaming", "Can we rename 'Negotiation' to 'Contracting' for our team?", "CLOSED", TicketPriority.MEDIUM, 240 },
				{ "Reminder emails not arriving", "Task reminders stopped arriving yesterday.", "IN_PROGRESS", TicketPriority.HIGH, 3 },
				{ "Request: dark mode on mobile", "Would love the dark theme on the mobile layout too.", "RESOLVED", TicketPriority.LOW, 400 } };
		int seq = tickets.maxTicketSequence(companyId);
		for (int i = 0; i < seeds.length; i++) {
			Object[] s = seeds[i];
			String status = (String) s[2];
			TicketPriority priority = (TicketPriority) s[3];
			Instant created = now.minus(Duration.ofHours((Integer) s[4]));
			Instant slaDue = created.plusSeconds(TicketsService.SLA_HOURS.get(priority) * 3600L);
			boolean answered = !"OPEN".equals(status) || i == 2;
			Instant firstResponse = answered ? created.plus(Duration.ofMinutes(25 + rnd.nextInt(90))) : null;
			Instant resolved = "RESOLVED".equals(status) || "CLOSED".equals(status)
					? created.plus(Duration.ofHours(6 + rnd.nextInt(30))) : null;
			Instant closed = "CLOSED".equals(status) ? resolved.plus(Duration.ofHours(24)) : null;
			String contactId = contactIds.get((i * 2 + 1) % contactIds.size());
			Map<String, Object> contact = jdbc.queryForMap("select name, email from contacts where id = ?", contactId);
			String id = Cuid.generate();
			seq++;
			Instant updated = closed != null ? closed : resolved != null ? resolved : firstResponse != null ? firstResponse : created;
			jdbc.update("""
					insert into tickets (id, ticket_number, title, description, status, priority, company_id, contact_id,
					  assignee_id, created_by_id, requester_email, requester_name, sla_due_at, first_responded_at,
					  resolved_at, closed_at, created_at, updated_at)
					values (?, ?, ?, ?, ?::"TicketStatus", ?::"TicketPriority", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", id, "T-" + String.format("%05d", seq), s[0], s[1], status, priority.name(), companyId,
					contactId, i % 3 == 0 ? adminId : supportId, adminId, contact.get("email"), contact.get("name"),
					ts(slaDue), ts(firstResponse), ts(resolved), ts(closed), ts(created), ts(updated));
			record(companyId, "TICKET", id);

			insertMessage(id, (String) s[1], false, null, (String) contact.get("name"), created);
			if (firstResponse != null) {
				insertMessage(id, "Thanks for reaching out — we're looking into this now.", false, supportId,
						"Sam Rivera", firstResponse);
			}
			if ("IN_PROGRESS".equals(status)) {
				insertMessage(id, "Reproduced on staging, escalating to engineering.", true, supportId, "Sam Rivera",
						firstResponse.plus(Duration.ofMinutes(40)));
			}
			if (resolved != null) {
				insertMessage(id, "This is fixed on our side. Let us know if you still see it.", false, supportId,
						"Sam Rivera", resolved);
			}
		}
	}

	private void insertKbArticles(String companyId, String authorId, Instant now) {
		String[][] articles = {
				{ "Getting started with Novora", "getting-started-with-novora",
						"Create your first company and contact, then add a deal to the pipeline. Drag the deal between stages as it progresses." },
				{ "Resetting your password", "resetting-your-password",
						"Open Settings, go to Password, enter your current password and choose a new one with at least 8 characters." },
				{ "Using the customer portal", "using-the-customer-portal",
						"Share your portal link with customers. They can submit tickets, track progress with their ticket number, and reply to agents." } };
		for (String[] a : articles) {
			String id = Cuid.generate();
			Instant created = daysAgo(now, 30);
			int inserted = jdbc.update("""
					insert into kb_articles (id, title, slug, body, published, company_id, created_by_id, created_at, updated_at)
					values (?, ?, ?, ?, true, ?, ?, ?, ?)
					on conflict (company_id, slug) do nothing
					""", id, a[0], a[1], a[2], companyId, authorId, ts(created), ts(created));
			if (inserted > 0) {
				record(companyId, "KB_ARTICLE", id);
			}
		}
	}

	private String insertUser(String companyId, String name, String handle, String role, String suffix, Instant now) {
		String id = Cuid.generate();
		String email = handle + "." + suffix + "@sample.novora.local";
		Instant created = daysAgo(now, 280);
		jdbc.update("""
				insert into users (id, name, email, password_hash, role, company_id, is_active, created_at, updated_at)
				values (?, ?, ?, ?, ?::"Role", ?, true, ?, ?)
				""", id, name, email, passwordEncoder.encode(UUID.randomUUID().toString()), role, companyId,
				ts(created), ts(created));
		record(companyId, "USER", id);
		return id;
	}

	private void insertActivity(String companyId, String userId, String contactId, String dealId, String type,
			String subject, String note, Instant date, Instant dueAt, Instant completedAt) {
		String id = Cuid.generate();
		jdbc.update("""
				insert into activities (id, type, subject, note, due_at, completed_at, date, company_id, user_id,
				  contact_id, deal_id, created_at, updated_at)
				values (?, ?::"ActivityType", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", id, type, subject, note, ts(dueAt), ts(completedAt), ts(date), companyId, userId, contactId, dealId,
				ts(date), ts(date));
		record(companyId, "ACTIVITY", id);
	}

	private void insertMessage(String ticketId, String body, boolean internal, String authorId, String authorName,
			Instant at) {
		jdbc.update("""
				insert into ticket_messages (id, ticket_id, body, is_internal, author_id, author_name, created_at)
				values (?, ?, ?, ?, ?, ?, ?)
				""", Cuid.generate(), ticketId, body, internal, authorId, authorName, ts(at));
	}

	private String ensureTag(String companyId, String name, String color) {
		String id = Cuid.generate();
		int inserted = jdbc.update("""
				insert into tags (id, name, color, company_id) values (?, ?, ?, ?)
				on conflict (company_id, name) do nothing
				""", id, name, color, companyId);
		if (inserted > 0) {
			record(companyId, "TAG", id);
			return id;
		}
		return jdbc.queryForObject("select id from tags where company_id = ? and name = ?", String.class, companyId,
				name);
	}

	private void tagAssign(String tagId, String entityType, String entityId) {
		jdbc.update("""
				insert into tag_assignments (id, tag_id, entity_type, entity_id)
				values (?, ?, ?::"TagEntityType", ?) on conflict do nothing
				""", Cuid.generate(), tagId, entityType, entityId);
	}

	private void record(String companyId, String type, String entityId) {
		jdbc.update("insert into sample_records (id, company_id, entity_type, entity_id) values (?, ?, ?, ?)",
				Cuid.generate(), companyId, type, entityId);
	}

	private static String stageForAge(Random rnd, int ageDays) {
		double r = rnd.nextDouble();
		if (ageDays > 60) {
			return r < 0.6 ? "WON" : r < 0.85 ? "LOST" : "NEGOTIATION";
		}
		if (ageDays > 28) {
			return r < 0.3 ? "WON" : r < 0.4 ? "LOST" : r < 0.65 ? "NEGOTIATION" : "PROPOSAL";
		}
		return r < 0.35 ? "LEAD" : r < 0.7 ? "QUALIFIED" : r < 0.88 ? "PROPOSAL" : "NEGOTIATION";
	}

	private static String pick(Random rnd, List<String> list) {
		return list.get(rnd.nextInt(list.size()));
	}

	private static Instant daysAgo(Instant now, int days) {
		return now.minus(Duration.ofDays(days));
	}

	private static Timestamp ts(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}
}
