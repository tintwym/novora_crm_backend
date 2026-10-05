package dev.tintwym.novora.modules.quotes;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.EmailService;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.domain.entity.Deal;
import dev.tintwym.novora.domain.entity.Quote;
import dev.tintwym.novora.domain.entity.QuoteLineItem;
import dev.tintwym.novora.domain.enums.QuoteStatus;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.modules.products.ProductsService;
import dev.tintwym.novora.repositories.CrmAccountRepository;
import dev.tintwym.novora.repositories.ContactRepository;
import dev.tintwym.novora.repositories.DealRepository;
import dev.tintwym.novora.repositories.QuoteLineItemRepository;
import dev.tintwym.novora.repositories.QuoteRepository;
import dev.tintwym.novora.repositories.TenantCompanyRepository;
import dev.tintwym.novora.repositories.UserEntityRepository;
import dev.tintwym.novora.security.SecurityUtils;
import dev.tintwym.novora.security.UserPrincipal;

@Service
public class QuotesService {

	private final QuoteRepository quotes;
	private final QuoteLineItemRepository lineItems;
	private final DealRepository deals;
	private final ContactRepository contacts;
	private final CrmAccountRepository accounts;
	private final UserEntityRepository users;
	private final TenantCompanyRepository companies;
	private final ProductsService products;
	private final AuditService audit;
	private final EmailService email;

	private static final String[] AUDIT_FIELDS = { "title", "status", "currency", "taxRate", "total", "notes", "terms",
			"validUntil", "contact", "account" };

	public QuotesService(QuoteRepository quotes, QuoteLineItemRepository lineItems, DealRepository deals,
			ContactRepository contacts, CrmAccountRepository accounts, UserEntityRepository users,
			TenantCompanyRepository companies, ProductsService products, AuditService audit, EmailService email) {
		this.email = email;
		this.products = products;
		this.audit = audit;
		this.quotes = quotes;
		this.lineItems = lineItems;
		this.deals = deals;
		this.contacts = contacts;
		this.accounts = accounts;
		this.users = users;
		this.companies = companies;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UserPrincipal user, String dealId) {
		List<Quote> list;
		if (SecurityUtils.canViewAllDeals(user)) {
			list = dealId != null && !dealId.isBlank()
					? quotes.findByCompanyIdAndDealIdOrderByCreatedAtDesc(user.getCompanyId(), dealId)
					: quotes.findByCompanyIdOrderByCreatedAtDesc(user.getCompanyId());
		} else {
			list = quotes.findScoped(user.getCompanyId(), user.getId(),
					dealId == null || dealId.isBlank() ? null : dealId);
		}
		return list.stream().map(this::serialize).toList();
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(UserPrincipal user, String id) {
		return serialize(findScoped(user, id));
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> create(UserPrincipal user, Map<String, Object> body) {
		Deal deal = ensureDeal(user, req(body, "dealId"));
		BigDecimal taxRate = asDecimal(body.get("taxRate"), BigDecimal.ZERO);
		List<Map<String, Object>> itemsInput = (List<Map<String, Object>>) body.get("lineItems");
		if (itemsInput == null || itemsInput.isEmpty()) {
			throw new AppException("lineItems is required", HttpStatus.BAD_REQUEST);
		}
		Totals totals = calcTotals(user, itemsInput, taxRate);
		Quote q = new Quote();
		q.setQuoteNumber(nextQuoteNumber(user.getCompanyId()));
		q.setTitle(req(body, "title"));
		q.setCurrency(body.get("currency") != null ? String.valueOf(body.get("currency")) : "MMK");
		q.setTaxRate(taxRate);
		q.setSubtotal(totals.subtotal);
		q.setTaxAmount(totals.taxAmount);
		q.setTotal(totals.total);
		q.setNotes(emptyToNull(opt(body, "notes")));
		q.setTerms(emptyToNull(opt(body, "terms")));
		q.setValidUntil(parseDate(opt(body, "validUntil")));
		q.setCompanyId(user.getCompanyId());
		q.setDealId(deal.getId());
		q.setContactId(body.get("contactId") != null ? scopedContact(user, opt(body, "contactId")) : deal.getContactId());
		q.setAccountId(body.get("accountId") != null ? scopedAccount(user, opt(body, "accountId")) : deal.getAccountId());
		q.setCreatedById(user.getId());
		quotes.save(q);
		saveLineItems(q.getId(), totals.items);
		audit.created(user, "QUOTE", q.getId(), label(q));
		return serialize(q);
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> update(UserPrincipal user, String id, Map<String, Object> body) {
		Quote existing = findScoped(user, id);
		if (existing.getStatus() != QuoteStatus.DRAFT) {
			throw new AppException("Only draft quotes can be edited", HttpStatus.BAD_REQUEST);
		}
		Map<String, Object> before = serialize(existing);
		BigDecimal taxRate = body.get("taxRate") != null ? asDecimal(body.get("taxRate"), existing.getTaxRate())
				: existing.getTaxRate();
		if (body.get("lineItems") instanceof List<?> raw) {
			List<Map<String, Object>> itemsInput = (List<Map<String, Object>>) raw;
			Totals totals = calcTotals(user, itemsInput, taxRate);
			lineItems.deleteByQuoteId(id);
			saveLineItems(id, totals.items);
			existing.setSubtotal(totals.subtotal);
			existing.setTaxAmount(totals.taxAmount);
			existing.setTotal(totals.total);
			existing.setTaxRate(taxRate);
		} else if (body.containsKey("taxRate")) {
			BigDecimal subtotal = existing.getSubtotal();
			BigDecimal taxAmount = subtotal.multiply(taxRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
			existing.setTaxRate(taxRate);
			existing.setTaxAmount(taxAmount);
			existing.setTotal(subtotal.add(taxAmount).setScale(2, RoundingMode.HALF_UP));
		}
		if (body.containsKey("title")) existing.setTitle(req(body, "title"));
		if (body.containsKey("currency")) existing.setCurrency(String.valueOf(body.get("currency")));
		if (body.containsKey("notes")) existing.setNotes(emptyToNull(opt(body, "notes")));
		if (body.containsKey("terms")) existing.setTerms(emptyToNull(opt(body, "terms")));
		if (body.containsKey("validUntil")) existing.setValidUntil(parseDate(opt(body, "validUntil")));
		if (body.containsKey("contactId")) existing.setContactId(scopedContact(user, opt(body, "contactId")));
		if (body.containsKey("accountId")) existing.setAccountId(scopedAccount(user, opt(body, "accountId")));
		quotes.save(existing);
		Map<String, Object> after = serialize(existing);
		Map<String, Object> changes = AuditService.diff(before, after, AUDIT_FIELDS);
		if (body.get("lineItems") instanceof List<?> && !sameLines(before.get("lineItems"), after.get("lineItems"))) {
			changes.put("lineItems", JsonMaps.of("from", lineCount(before), "to", lineCount(after)));
		}
		if (!changes.isEmpty()) {
			audit.record(user, AuditService.UPDATE, "QUOTE", id, label(existing), JsonMaps.of("changes", changes));
		}
		return after;
	}

	@Transactional
	public Map<String, Object> updateStatus(UserPrincipal user, String id, Map<String, Object> body) {
		Quote existing = findScoped(user, id);
		QuoteStatus status = QuoteStatus.valueOf(req(body, "status"));
		Map<QuoteStatus, List<QuoteStatus>> transitions = Map.of(
				QuoteStatus.DRAFT, List.of(QuoteStatus.SENT),
				QuoteStatus.SENT, List.of(QuoteStatus.ACCEPTED, QuoteStatus.REJECTED, QuoteStatus.DRAFT),
				QuoteStatus.ACCEPTED, List.of(),
				QuoteStatus.REJECTED, List.of(QuoteStatus.DRAFT));
		if (!transitions.get(existing.getStatus()).contains(status)) {
			throw new AppException("Cannot change status from " + existing.getStatus() + " to " + status,
					HttpStatus.BAD_REQUEST);
		}
		QuoteStatus previous = existing.getStatus();
		existing.setStatus(status);
		if (status == QuoteStatus.SENT) existing.setSentAt(Instant.now());
		if (status == QuoteStatus.ACCEPTED || status == QuoteStatus.REJECTED) existing.setRespondedAt(Instant.now());
		if (status == QuoteStatus.DRAFT) {
			existing.setSentAt(null);
			existing.setRespondedAt(null);
		}
		quotes.save(existing);
		audit.record(user, AuditService.UPDATE, "QUOTE", id, label(existing), JsonMaps.of("changes",
				JsonMaps.of("status", JsonMaps.of("from", previous.name(), "to", status.name()))));
		return serialize(existing);
	}

	/** Emails the quote PDF. A draft is marked as sent once the email goes out. */
	@Transactional
	public Map<String, Object> emailQuote(UserPrincipal user, String id, Map<String, Object> body) {
		Quote quote = findScoped(user, id);
		String to = emptyToNull(opt(body, "to"));
		if (to == null && quote.getContactId() != null) {
			to = contacts.findById(quote.getContactId()).map(c -> c.getEmail()).orElse(null);
		}
		if (to == null) throw new AppException("Enter the email address to send the quote to", HttpStatus.BAD_REQUEST);
		to = to.trim();
		if (!EmailService.isValidAddress(to)) throw new AppException("Enter a valid email address", HttpStatus.BAD_REQUEST);
		if (quote.getStatus() == QuoteStatus.ACCEPTED || quote.getStatus() == QuoteStatus.REJECTED) {
			throw new AppException("This quote was already " + quote.getStatus().name().toLowerCase()
					+ ". Move it back to draft to send it again.", HttpStatus.BAD_REQUEST);
		}
		String companyName = companies.findById(quote.getCompanyId()).map(c -> c.getName()).orElse("Novora");
		String subject = emptyToNull(opt(body, "subject"));
		if (subject == null) subject = "Quote " + quote.getQuoteNumber() + " from " + companyName;
		if (subject.length() > 200) throw new AppException("Subject must be 200 characters or fewer", HttpStatus.BAD_REQUEST);
		String message = emptyToNull(opt(body, "message"));
		if (message != null && message.length() > 5000) {
			throw new AppException("Message must be 5000 characters or fewer", HttpStatus.BAD_REQUEST);
		}
		String total = quote.getCurrency() + " " + String.format("%,.2f", quote.getTotal());
		String validUntil = quote.getValidUntil() == null ? null
				: DateTimeFormatter.ofPattern("d MMM yyyy").format(quote.getValidUntil().atZone(ZoneOffset.UTC));
		List<String> paragraphs = new ArrayList<>();
		paragraphs.add(message != null ? message : "Please find our quote attached.");
		paragraphs.add(quote.getTitle() + "\nTotal: " + total + (validUntil == null ? "" : "\nValid until: " + validUntil));
		paragraphs.add("Reply to this email if you have any questions.\n\n" + user.getName() + "\n" + companyName);
		PdfResult pdf = exportPdf(user, id);
		email.sendNow(new EmailService.Email(to, subject, String.join("\n\n", paragraphs),
				email.layout("Quote " + quote.getQuoteNumber(), paragraphs, null, null),
				List.of(new EmailService.Attachment(pdf.fileName(), pdf.buffer(), "application/pdf"))));
		QuoteStatus previous = quote.getStatus();
		if (previous == QuoteStatus.DRAFT) {
			quote.setStatus(QuoteStatus.SENT);
			quote.setSentAt(Instant.now());
			quotes.save(quote);
		}
		Map<String, Object> details = JsonMaps.of("to", to);
		if (previous == QuoteStatus.DRAFT) {
			details.put("changes", JsonMaps.of("status", JsonMaps.of("from", "DRAFT", "to", "SENT")));
		}
		audit.record(user, "EMAIL", "QUOTE", id, label(quote), details);
		Map<String, Object> dto = serialize(quote);
		dto.put("emailedTo", to);
		return dto;
	}

	@Transactional
	public void delete(UserPrincipal user, String id) {
		Quote existing = findScoped(user, id);
		if (existing.getStatus() != QuoteStatus.DRAFT) {
			throw new AppException("Only draft quotes can be deleted", HttpStatus.BAD_REQUEST);
		}
		quotes.delete(existing);
		audit.deleted(user, "QUOTE", id, label(existing));
	}

	private static String label(Quote q) {
		return q.getQuoteNumber() + " – " + q.getTitle();
	}

	private static String lineCount(Map<String, Object> dto) {
		int n = dto.get("lineItems") instanceof List<?> l ? l.size() : 0;
		return n + (n == 1 ? " item" : " items");
	}

	private static boolean sameLines(Object a, Object b) {
		if (!(a instanceof List<?> x) || !(b instanceof List<?> y) || x.size() != y.size()) return false;
		for (int i = 0; i < x.size(); i++) {
			Map<?, ?> m = (Map<?, ?>) x.get(i), n = (Map<?, ?>) y.get(i);
			for (String k : List.of("productId", "description", "quantity", "unitPrice")) {
				if (!java.util.Objects.equals(m.get(k), n.get(k))) return false;
			}
		}
		return true;
	}

	@Transactional(readOnly = true)
	public PdfResult exportPdf(UserPrincipal user, String id) {
		Quote quote = findScoped(user, id);
		List<QuoteLineItem> items = lineItems.findByQuoteIdOrderBySortOrderAsc(quote.getId());
		String companyName = companies.findById(quote.getCompanyId()).map(c -> c.getName()).orElse("Novora");
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			Document doc = new Document();
			PdfWriter.getInstance(doc, baos);
			doc.open();
			Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, new Color(20, 66, 225));
			Font hFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
			Font nFont = FontFactory.getFont(FontFactory.HELVETICA, 10);
			doc.add(new Paragraph("Novora CRM", titleFont));
			doc.add(new Paragraph("Quotation", hFont));
			doc.add(new Paragraph("From: " + companyName, nFont));
			doc.add(new Paragraph("Quote #: " + quote.getQuoteNumber(), nFont));
			doc.add(new Paragraph("Status: " + quote.getStatus(), nFont));
			doc.add(new Paragraph(quote.getTitle(), hFont));
			for (QuoteLineItem item : items) {
				doc.add(new Paragraph(item.getDescription() + " x " + item.getQuantity() + " = "
						+ quote.getCurrency() + " " + item.getAmount(), nFont));
			}
			doc.add(new Paragraph("Total: " + quote.getCurrency() + " " + quote.getTotal(), hFont));
			doc.close();
			return new PdfResult(baos.toByteArray(), quote.getQuoteNumber() + ".pdf");
		} catch (Exception e) {
			throw new AppException("Failed to generate PDF", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	public record PdfResult(byte[] buffer, String fileName) {}

	private Quote findScoped(UserPrincipal user, String id) {
		Quote quote = quotes.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Quote not found", HttpStatus.NOT_FOUND));
		if (!SecurityUtils.canViewAllDeals(user)) {
			boolean ok = user.getId().equals(quote.getCreatedById())
					|| deals.findById(quote.getDealId()).map(d -> user.getId().equals(d.getOwnerId())).orElse(false);
			if (!ok) throw new AppException("Quote not found", HttpStatus.NOT_FOUND);
		}
		return quote;
	}

	private String scopedContact(UserPrincipal user, String contactId) {
		String id = emptyToNull(contactId);
		if (id == null) return null;
		return contacts.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Contact not found", HttpStatus.BAD_REQUEST)).getId();
	}

	private String scopedAccount(UserPrincipal user, String accountId) {
		String id = emptyToNull(accountId);
		if (id == null) return null;
		return accounts.findByIdAndCompanyId(id, user.getCompanyId())
				.orElseThrow(() -> new AppException("Company not found", HttpStatus.BAD_REQUEST)).getId();
	}

	private Deal ensureDeal(UserPrincipal user, String dealId) {
		Deal deal = deals.findByIdAndCompanyId(dealId, user.getCompanyId())
				.orElseThrow(() -> new AppException("Deal not found", HttpStatus.NOT_FOUND));
		if (!SecurityUtils.canViewAllDeals(user)
				&& (deal.getOwnerId() == null || !deal.getOwnerId().equals(user.getId()))) {
			throw new AppException("Deal not found", HttpStatus.NOT_FOUND);
		}
		return deal;
	}

	private String nextQuoteNumber(String companyId) {
		String prefix = "Q-" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
		return prefix + "-" + String.format("%03d", quotes.maxQuoteSequence(companyId, prefix) + 1);
	}

	private void saveLineItems(String quoteId, List<LineItemData> items) {
		int i = 0;
		for (LineItemData item : items) {
			QuoteLineItem li = new QuoteLineItem();
			li.setQuoteId(quoteId);
			li.setProductId(item.productId);
			li.setDescription(item.description);
			li.setQuantity(item.quantity);
			li.setUnitPrice(item.unitPrice);
			li.setAmount(item.amount);
			li.setSortOrder(i++);
			lineItems.save(li);
		}
	}

	private Totals calcTotals(UserPrincipal user, List<Map<String, Object>> lineItemsInput, BigDecimal taxRate) {
		List<LineItemData> items = new ArrayList<>();
		BigDecimal subtotal = BigDecimal.ZERO;
		int row = 0;
		for (Map<String, Object> item : lineItemsInput) {
			row++;
			String productId = emptyToNull(opt(item, "productId"));
			ProductsService.ProductRef product = productId == null ? null
					: products.findRef(user.getCompanyId(), productId)
							.orElseThrow(() -> new AppException("Product not found", HttpStatus.BAD_REQUEST));
			String description = emptyToNull(opt(item, "description"));
			if (description == null && product != null) description = product.name();
			if (description == null) {
				throw new AppException("Line " + row + " needs a description", HttpStatus.BAD_REQUEST);
			}
			BigDecimal qty = asDecimal(item.get("quantity"), BigDecimal.ONE);
			BigDecimal unit = asDecimal(item.get("unitPrice"), product != null ? product.unitPrice() : BigDecimal.ZERO);
			if (qty.signum() <= 0) throw new AppException("Line " + row + " quantity must be more than 0", HttpStatus.BAD_REQUEST);
			if (unit.signum() < 0) throw new AppException("Line " + row + " price can't be negative", HttpStatus.BAD_REQUEST);
			BigDecimal amount = qty.multiply(unit).setScale(2, RoundingMode.HALF_UP);
			items.add(new LineItemData(productId, description, qty, unit, amount));
			subtotal = subtotal.add(amount);
		}
		subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
		BigDecimal taxAmount = subtotal.multiply(taxRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
		BigDecimal total = subtotal.add(taxAmount).setScale(2, RoundingMode.HALF_UP);
		return new Totals(items, subtotal, taxAmount, total);
	}

	private Map<String, Object> serialize(Quote q) {
		List<Map<String, Object>> items = lineItems.findByQuoteIdOrderBySortOrderAsc(q.getId()).stream()
				.map(li -> JsonMaps.of(
						"id", li.getId(),
						"quoteId", li.getQuoteId(),
						"productId", li.getProductId(),
						"description", li.getDescription(),
						"quantity", JsonMaps.num(li.getQuantity()),
						"unitPrice", JsonMaps.num(li.getUnitPrice()),
						"amount", JsonMaps.num(li.getAmount()),
						"sortOrder", li.getSortOrder()))
				.toList();
		return JsonMaps.of(
				"id", q.getId(),
				"quoteNumber", q.getQuoteNumber(),
				"title", q.getTitle(),
				"status", q.getStatus().name(),
				"currency", q.getCurrency(),
				"taxRate", JsonMaps.num(q.getTaxRate()),
				"subtotal", JsonMaps.num(q.getSubtotal()),
				"taxAmount", JsonMaps.num(q.getTaxAmount()),
				"total", JsonMaps.num(q.getTotal()),
				"notes", q.getNotes(),
				"terms", q.getTerms(),
				"validUntil", JsonMaps.iso(q.getValidUntil()),
				"sentAt", JsonMaps.iso(q.getSentAt()),
				"respondedAt", JsonMaps.iso(q.getRespondedAt()),
				"companyId", q.getCompanyId(),
				"dealId", q.getDealId(),
				"contactId", q.getContactId(),
				"accountId", q.getAccountId(),
				"createdById", q.getCreatedById(),
				"createdAt", JsonMaps.iso(q.getCreatedAt()),
				"updatedAt", JsonMaps.iso(q.getUpdatedAt()),
				"lineItems", items,
				"deal", deals.findById(q.getDealId())
						.map(d -> JsonMaps.of("id", d.getId(), "title", d.getTitle(), "stage", d.getStage().name()))
						.orElse(null),
				"contact", q.getContactId() == null ? null : contacts.findById(q.getContactId())
						.map(c -> JsonMaps.of("id", c.getId(), "name", c.getName(), "email", c.getEmail(), "phone", c.getPhone()))
						.orElse(null),
				"account", q.getAccountId() == null ? null : accounts.findById(q.getAccountId())
						.map(a -> JsonMaps.of("id", a.getId(), "name", a.getName(), "email", a.getEmail(), "phone", a.getPhone()))
						.orElse(null),
				"createdBy", users.findById(q.getCreatedById())
						.map(u -> JsonMaps.of("id", u.getId(), "name", u.getName(), "email", u.getEmail()))
						.orElse(null));
	}

	private record LineItemData(String productId, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {}
	private record Totals(List<LineItemData> items, BigDecimal subtotal, BigDecimal taxAmount, BigDecimal total) {}

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
		return Instant.parse(value.contains("T") ? value : value + "T00:00:00Z");
	}

	private static BigDecimal asDecimal(Object v, BigDecimal def) {
		if (v == null || String.valueOf(v).isBlank()) return def;
		try {
			return new BigDecimal(String.valueOf(v).trim());
		} catch (NumberFormatException e) {
			throw new AppException("Numbers on the quote must be valid numbers", HttpStatus.BAD_REQUEST);
		}
	}
}
