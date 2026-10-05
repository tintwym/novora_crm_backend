package dev.tintwym.novora.repositories;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.tintwym.novora.domain.entity.Activity;
import dev.tintwym.novora.domain.enums.ActivityType;
import jakarta.persistence.criteria.Predicate;

public interface ActivityRepository extends JpaRepository<Activity, String>, JpaSpecificationExecutor<Activity> {
	Optional<Activity> findByIdAndCompanyId(String id, String companyId);
	List<Activity> findByContactIdAndCompanyIdOrderByDateDesc(String contactId, String companyId);
	List<Activity> findByDealIdAndCompanyIdOrderByDateDesc(String dealId, String companyId);
	List<Activity> findByCompanyIdOrderByDateDesc(String companyId, Pageable pageable);

	@Query("""
			select a from Activity a where a.companyId = :companyId and (
			  (a.dueAt is not null and a.dueAt >= :from and a.dueAt <= :to)
			  or (a.dueAt is null and a.date >= :from and a.date <= :to)
			) order by a.dueAt asc nulls last, a.date asc
			""")
	List<Activity> calendar(@Param("companyId") String companyId, @Param("from") Instant from, @Param("to") Instant to);

	List<Activity> findByCompanyIdAndCompletedAtIsNullAndDueAtIsNotNullAndDueAtLessThanEqualOrderByDueAtAsc(
			String companyId, Instant dueAt, Pageable pageable);

	/**
	 * Optional filters are applied only when non-null so PostgreSQL never sees
	 * untyped {@code ? IS NULL} binds (which break for enums/booleans).
	 */
	default List<Activity> filter(String companyId, ActivityType type, String contactId, String dealId,
			Boolean completed, Pageable pageable) {
		Specification<Activity> spec = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.equal(root.get("companyId"), companyId));
			if (type != null) {
				predicates.add(cb.equal(root.get("type"), type));
			}
			if (contactId != null) {
				predicates.add(cb.equal(root.get("contactId"), contactId));
			}
			if (dealId != null) {
				predicates.add(cb.equal(root.get("dealId"), dealId));
			}
			if (completed != null) {
				if (completed) {
					predicates.add(cb.isNotNull(root.get("completedAt")));
				} else {
					predicates.add(cb.isNull(root.get("completedAt")));
				}
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};

		Sort sort = Sort.by(
				Sort.Order.asc("dueAt").nullsLast(),
				Sort.Order.desc("date"));
		if (pageable != null && pageable.isPaged()) {
			return findAll(spec, org.springframework.data.domain.PageRequest.of(
					pageable.getPageNumber(), pageable.getPageSize(), sort)).getContent();
		}
		return findAll(spec, sort);
	}
}
