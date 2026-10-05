package dev.tintwym.novora.modules.products;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.common.Cuid;
import dev.tintwym.novora.common.JsonMaps;
import dev.tintwym.novora.modules.audit.AuditService;
import dev.tintwym.novora.security.UserPrincipal;

@Service
public class ProductsService {

	private static final String SELECT = """
			select p.id, p.name, p.sku, p.description, p.unit_price, p.is_active, p.created_at, p.updated_at,
			  (select count(distinct li.quote_id) from quote_line_items li where li.product_id = p.id) as used_count
			from products p
			""";

	private final NamedParameterJdbcTemplate jdbc;

	private final AuditService audit;

	private static final String[] AUDIT_FIELDS = { "name", "sku", "description", "unitPrice", "isActive" };

	public ProductsService(NamedParameterJdbcTemplate jdbc, AuditService audit) {
		this.audit = audit;
		this.jdbc = jdbc;
	}

	public record ProductRef(String id, String name, String description, BigDecimal unitPrice, boolean active) {}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UserPrincipal user, boolean includeArchived, String q) {
		MapSqlParameterSource params = new MapSqlParameterSource("companyId", user.getCompanyId());
		StringBuilder sql = new StringBuilder(SELECT).append(" where p.company_id = :companyId");
		if (!includeArchived) sql.append(" and p.is_active");
		if (q != null && !q.isBlank()) {
			sql.append(" and (p.name ilike :q or p.sku ilike :q or p.description ilike :q)");
			params.addValue("q", "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%");
		}
		sql.append(" order by p.is_active desc, lower(p.name)");
		return jdbc.query(sql.toString(), params, this::row);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(UserPrincipal user, String id) {
		return findRow(user.getCompanyId(), id);
	}

	@Transactional
	public Map<String, Object> create(UserPrincipal user, Map<String, Object> body) {
		String id = Cuid.generate();
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("id", id)
				.addValue("companyId", user.getCompanyId())
				.addValue("name", requireName(body.get("name")))
				.addValue("sku", clean(body.get("sku")))
				.addValue("description", clean(body.get("description")))
				.addValue("unitPrice", price(body.get("unitPrice"), BigDecimal.ZERO))
				.addValue("active", body.get("isActive") == null || Boolean.parseBoolean(String.valueOf(body.get("isActive"))));
		try {
			jdbc.update("""
					insert into products (id, company_id, name, sku, description, unit_price, is_active, created_at, updated_at)
					values (:id, :companyId, :name, :sku, :description, :unitPrice, :active, now(), now())
					""", params);
		} catch (DuplicateKeyException e) {
			throw duplicateSku();
		}
		Map<String, Object> created = findRow(user.getCompanyId(), id);
		audit.created(user, "PRODUCT", id, String.valueOf(created.get("name")));
		return created;
	}

	@Transactional
	public Map<String, Object> update(UserPrincipal user, String id, Map<String, Object> body) {
		Map<String, Object> before = findRow(user.getCompanyId(), id);
		MapSqlParameterSource params = new MapSqlParameterSource("id", id).addValue("companyId", user.getCompanyId());
		StringBuilder sets = new StringBuilder("updated_at = now()");
		if (body.containsKey("name")) {
			sets.append(", name = :name");
			params.addValue("name", requireName(body.get("name")));
		}
		if (body.containsKey("sku")) {
			sets.append(", sku = :sku");
			params.addValue("sku", clean(body.get("sku")));
		}
		if (body.containsKey("description")) {
			sets.append(", description = :description");
			params.addValue("description", clean(body.get("description")));
		}
		if (body.containsKey("unitPrice")) {
			sets.append(", unit_price = :unitPrice");
			params.addValue("unitPrice", price(body.get("unitPrice"), null));
		}
		if (body.containsKey("isActive")) {
			sets.append(", is_active = :active");
			params.addValue("active", Boolean.parseBoolean(String.valueOf(body.get("isActive"))));
		}
		try {
			jdbc.update("update products set " + sets + " where id = :id and company_id = :companyId", params);
		} catch (DuplicateKeyException e) {
			throw duplicateSku();
		}
		Map<String, Object> after = findRow(user.getCompanyId(), id);
		audit.updated(user, "PRODUCT", id, String.valueOf(after.get("name")), before, after, AUDIT_FIELDS);
		return after;
	}

	/** Products already used on quotes are archived rather than deleted so those quotes keep their link. */
	@Transactional
	public Map<String, Object> delete(UserPrincipal user, String id) {
		Map<String, Object> existing = findRow(user.getCompanyId(), id);
		MapSqlParameterSource params = new MapSqlParameterSource("id", id).addValue("companyId", user.getCompanyId());
		if (((Number) existing.get("usedCount")).longValue() > 0) {
			jdbc.update("update products set is_active = false, updated_at = now() where id = :id and company_id = :companyId",
					params);
			audit.record(user, "ARCHIVE", "PRODUCT", id, String.valueOf(existing.get("name")), null);
			return Map.of("archived", true);
		}
		jdbc.update("delete from products where id = :id and company_id = :companyId", params);
		audit.deleted(user, "PRODUCT", id, String.valueOf(existing.get("name")));
		return Map.of("archived", false);
	}

	public Optional<ProductRef> findRef(String companyId, String id) {
		return jdbc.query("""
				select id, name, description, unit_price, is_active from products
				where id = :id and company_id = :companyId
				""", new MapSqlParameterSource("id", id).addValue("companyId", companyId),
				(rs, i) -> new ProductRef(rs.getString("id"), rs.getString("name"), rs.getString("description"),
						rs.getBigDecimal("unit_price"), rs.getBoolean("is_active")))
				.stream().findFirst();
	}

	private Map<String, Object> findRow(String companyId, String id) {
		return jdbc.query(SELECT + " where p.id = :id and p.company_id = :companyId",
				new MapSqlParameterSource("id", id).addValue("companyId", companyId), this::row)
				.stream().findFirst()
				.orElseThrow(() -> new AppException("Product not found", HttpStatus.NOT_FOUND));
	}

	private Map<String, Object> row(ResultSet rs, int i) throws SQLException {
		return JsonMaps.of(
				"id", rs.getString("id"),
				"name", rs.getString("name"),
				"sku", rs.getString("sku"),
				"description", rs.getString("description"),
				"unitPrice", JsonMaps.num(rs.getBigDecimal("unit_price")),
				"isActive", rs.getBoolean("is_active"),
				"usedCount", rs.getLong("used_count"),
				"createdAt", iso(rs.getTimestamp("created_at")),
				"updatedAt", iso(rs.getTimestamp("updated_at")));
	}

	private static String iso(Timestamp ts) {
		return ts == null ? null : JsonMaps.iso(ts.toInstant());
	}

	private static String requireName(Object v) {
		String name = clean(v);
		if (name == null) throw new AppException("Product name is required", HttpStatus.BAD_REQUEST);
		if (name.length() > 200) throw new AppException("Product name is too long", HttpStatus.BAD_REQUEST);
		return name;
	}

	private static String clean(Object v) {
		if (v == null) return null;
		String s = String.valueOf(v).trim();
		return s.isEmpty() ? null : s;
	}

	private static BigDecimal price(Object v, BigDecimal def) {
		String s = clean(v);
		if (s == null) {
			if (def == null) throw new AppException("Unit price is required", HttpStatus.BAD_REQUEST);
			return def;
		}
		BigDecimal p;
		try {
			p = new BigDecimal(s);
		} catch (NumberFormatException e) {
			throw new AppException("Unit price must be a number", HttpStatus.BAD_REQUEST);
		}
		if (p.signum() < 0) throw new AppException("Unit price can't be negative", HttpStatus.BAD_REQUEST);
		return p.setScale(2, RoundingMode.HALF_UP);
	}

	private static AppException duplicateSku() {
		return new AppException("Another product already uses that SKU", HttpStatus.CONFLICT);
	}
}
