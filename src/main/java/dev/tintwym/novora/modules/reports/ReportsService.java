package dev.tintwym.novora.modules.reports;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.security.SecurityUtils;
import dev.tintwym.novora.security.UserPrincipal;

@Service
public class ReportsService {

	/** When a closed deal was closed: its close date, or when it was last moved. */
	private static final String CLOSED_AT = "coalesce(d.close_date, d.updated_at)";

	private final NamedParameterJdbcTemplate jdbc;

	public ReportsService(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> salesReport(UserPrincipal user, String fromParam, String toParam) {
		LocalDate to = parse(toParam, LocalDate.now(ZoneOffset.UTC));
		LocalDate from = parse(fromParam, earliestDeal(user.getCompanyId(), to.withDayOfMonth(1).minusMonths(11)));
		if (from.isAfter(to)) {
			LocalDate tmp = from;
			from = to;
			to = tmp;
		}
		long spanDays = ChronoUnit.DAYS.between(from, to) + 1;
		String unit = spanDays <= 31 ? "day" : spanDays <= 120 ? "week" : "month";

		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("c", user.getCompanyId())
				.addValue("from", Timestamp.valueOf(from.atStartOfDay()))
				.addValue("to", Timestamp.valueOf(to.plusDays(1).atStartOfDay().minusNanos(1_000_000)))
				.addValue("unit", unit)
				.addValue("step", "1 " + unit);
		boolean all = SecurityUtils.canViewAllDeals(user);
		String dealScope = all ? "" : " and d.owner_id = :o";
		String userScope = all ? "" : " and u.id = :o";
		String activityScope = all ? "" : " and a.user_id = :o";
		if (!all) {
			p.addValue("o", user.getId());
		}
		String closedInRange = CLOSED_AT + " between :from and :to";

		Map<String, Object> summary = jdbc.queryForMap("""
				select
				  count(*) filter (where d.created_at between :from and :to) as new_deals,
				  count(*) filter (where d.stage = 'WON' and %1$s) as won,
				  count(*) filter (where d.stage = 'LOST' and %1$s) as lost,
				  coalesce(sum(d.value) filter (where d.stage = 'WON' and %1$s), 0) as revenue,
				  coalesce(avg(d.value) filter (where d.stage = 'WON' and %1$s), 0) as avg_deal,
				  coalesce(avg(extract(epoch from (%2$s - d.created_at)) / 86400)
				    filter (where d.stage = 'WON' and %1$s), 0) as cycle_days,
				  count(*) filter (where d.stage not in ('WON', 'LOST')) as open_count,
				  coalesce(sum(d.value) filter (where d.stage not in ('WON', 'LOST')), 0) as open_value,
				  coalesce(sum(d.value * d.probability / 100.0) filter (where d.stage not in ('WON', 'LOST')), 0)
				    as weighted_value
				from deals d
				where d.company_id = :c%3$s
				""".formatted(closedInRange, CLOSED_AT, dealScope), p);
		long won = ((Number) summary.get("won")).longValue();
		long lost = ((Number) summary.get("lost")).longValue();

		List<Map<String, Object>> trend = jdbc.queryForList("""
				with buckets as (
				  select generate_series(
				    date_trunc(cast(:unit as text), cast(:from as timestamp)),
				    date_trunc(cast(:unit as text), cast(:to as timestamp)),
				    cast(:step as interval)) as start
				)
				select b.start,
				  count(d.id) filter (where d.stage = 'WON') as won,
				  count(d.id) filter (where d.stage = 'LOST') as lost,
				  coalesce(sum(d.value) filter (where d.stage = 'WON'), 0) as revenue,
				  (select count(*) from deals n
				    where n.company_id = :c%3$s
				      and n.created_at between :from and :to
				      and date_trunc(cast(:unit as text), n.created_at) = b.start) as created
				from buckets b
				left join deals d on d.company_id = :c%1$s
				  and d.stage in ('WON', 'LOST')
				  and %2$s
				  and date_trunc(cast(:unit as text), %4$s) = b.start
				group by b.start
				order by b.start
				""".formatted(dealScope, closedInRange, dealScope.replace("d.", "n."), CLOSED_AT), p);

		List<Map<String, Object>> byOwner = jdbc.queryForList("""
				select u.id, u.name,
				  count(d.id) filter (where d.created_at between :from and :to) as created,
				  count(d.id) filter (where d.stage = 'WON' and %1$s) as won,
				  count(d.id) filter (where d.stage = 'LOST' and %1$s) as lost,
				  coalesce(sum(d.value) filter (where d.stage = 'WON' and %1$s), 0) as revenue,
				  coalesce(sum(d.value) filter (where d.stage not in ('WON', 'LOST')), 0) as open_value,
				  (select count(*) from activities a
				    where a.company_id = :c and a.user_id = u.id and a.date between :from and :to) as activities
				from users u
				left join deals d on d.owner_id = u.id and d.company_id = :c
				where u.company_id = :c%2$s
				group by u.id, u.name
				having count(d.id) > 0
				order by revenue desc, won desc, u.name
				""".formatted(closedInRange, userScope), p);

		List<Map<String, Object>> topAccounts = jdbc.queryForList("""
				select acc.id, acc.name, count(d.id) as won, sum(d.value) as revenue
				from deals d join accounts acc on acc.id = d.account_id
				where d.company_id = :c%1$s and d.stage = 'WON' and %2$s
				group by acc.id, acc.name
				order by revenue desc
				limit 5
				""".formatted(dealScope, closedInRange), p);

		List<Map<String, Object>> stages = jdbc.queryForList("""
				select cast(d.stage as text) as stage, count(*) as count, coalesce(sum(d.value), 0) as value
				from deals d
				where d.company_id = :c%s and d.created_at between :from and :to
				group by d.stage
				""".formatted(dealScope), p);

		List<Map<String, Object>> bySource = jdbc.queryForList("""
				select coalesce(d.source, 'UNKNOWN') as source,
				       count(*) as created,
				       count(*) filter (where d.stage = 'WON') as won,
				       count(*) filter (where d.stage = 'LOST') as lost,
				       coalesce(sum(d.value) filter (where d.stage = 'WON'), 0) as revenue,
				       coalesce(sum(d.value) filter (where d.stage not in ('WON', 'LOST')), 0) as open_value
				from deals d
				where d.company_id = :c%s and d.created_at between :from and :to
				group by 1
				order by revenue desc, created desc
				""".formatted(dealScope), p);

		List<Map<String, Object>> activityMix = jdbc.queryForList("""
				select cast(a.type as text) as type, count(*) as count
				from activities a
				where a.company_id = :c%s and a.date between :from and :to
				group by a.type
				order by count desc
				""".formatted(activityScope), p);

		Map<String, Object> summaryOut = new LinkedHashMap<>();
		summaryOut.put("newDeals", num(summary.get("new_deals")));
		summaryOut.put("won", won);
		summaryOut.put("lost", lost);
		summaryOut.put("winRate", won + lost == 0 ? null : Math.round(won * 1000.0 / (won + lost)) / 10.0);
		summaryOut.put("revenue", num(summary.get("revenue")));
		summaryOut.put("avgDealSize", num(summary.get("avg_deal")));
		summaryOut.put("avgSalesCycleDays", Math.round(num(summary.get("cycle_days")) * 10) / 10.0);
		summaryOut.put("openDeals", num(summary.get("open_count")));
		summaryOut.put("openPipeline", num(summary.get("open_value")));
		summaryOut.put("weightedPipeline", num(summary.get("weighted_value")));

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("range", Map.of("from", from.toString(), "to", to.toString(), "interval", unit));
		out.put("scope", all ? "company" : "own");
		out.put("summary", summaryOut);
		out.put("trend", trend.stream().map(r -> Map.of(
				"start", ((Timestamp) r.get("start")).toLocalDateTime().toLocalDate().toString(),
				"won", num(r.get("won")),
				"lost", num(r.get("lost")),
				"revenue", num(r.get("revenue")),
				"created", num(r.get("created")))).toList());
		out.put("byOwner", byOwner.stream().map(r -> {
			double ownerWon = num(r.get("won"));
			double ownerLost = num(r.get("lost"));
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", r.get("id"));
			m.put("name", r.get("name"));
			m.put("created", num(r.get("created")));
			m.put("won", ownerWon);
			m.put("lost", ownerLost);
			m.put("winRate", ownerWon + ownerLost == 0 ? null
					: Math.round(ownerWon * 1000.0 / (ownerWon + ownerLost)) / 10.0);
			m.put("revenue", num(r.get("revenue")));
			m.put("openPipeline", num(r.get("open_value")));
			m.put("activities", num(r.get("activities")));
			return m;
		}).toList());
		out.put("topAccounts", topAccounts.stream().map(r -> Map.of(
				"id", r.get("id"), "name", r.get("name"),
				"won", num(r.get("won")), "revenue", num(r.get("revenue")))).toList());
		out.put("stages", stages.stream().map(r -> Map.of(
				"stage", r.get("stage"), "count", num(r.get("count")), "value", num(r.get("value")))).toList());
		out.put("bySource", bySource.stream().map(r -> {
			double srcWon = num(r.get("won"));
			double srcLost = num(r.get("lost"));
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("source", r.get("source"));
			m.put("created", num(r.get("created")));
			m.put("won", srcWon);
			m.put("lost", srcLost);
			m.put("winRate", srcWon + srcLost == 0 ? null : Math.round(srcWon * 1000.0 / (srcWon + srcLost)) / 10.0);
			m.put("revenue", num(r.get("revenue")));
			m.put("openPipeline", num(r.get("open_value")));
			return m;
		}).toList());
		out.put("activityMix", activityMix.stream().map(r -> Map.of(
				"type", r.get("type"), "count", num(r.get("count")))).toList());
		return out;
	}

	private LocalDate earliestDeal(String companyId, LocalDate fallback) {
		Timestamp first = jdbc.queryForObject("select min(created_at) from deals where company_id = :c",
				new MapSqlParameterSource("c", companyId), Timestamp.class);
		if (first == null) {
			return fallback;
		}
		LocalDate day = first.toLocalDateTime().toLocalDate().withDayOfMonth(1);
		return day.isBefore(fallback) ? day : fallback;
	}

	private static LocalDate parse(String value, LocalDate fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return LocalDate.parse(value.trim());
		} catch (DateTimeParseException ex) {
			throw new AppException("Dates must look like 2026-01-31", HttpStatus.BAD_REQUEST);
		}
	}

	private static double num(Object value) {
		return value == null ? 0 : ((Number) value).doubleValue();
	}
}
