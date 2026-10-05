package dev.tintwym.novora.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.tintwym.novora.domain.entity.Quote;

public interface QuoteRepository extends JpaRepository<Quote, String> {
	Optional<Quote> findByIdAndCompanyId(String id, String companyId);
	List<Quote> findByCompanyIdOrderByCreatedAtDesc(String companyId);
	List<Quote> findByCompanyIdAndDealIdOrderByCreatedAtDesc(String companyId, String dealId);
	long countByCompanyIdAndQuoteNumberStartingWith(String companyId, String prefix);

	@Query(value = """
			select coalesce(max(cast(substring(quote_number from char_length(:prefix) + 2) as integer)), 0)
			from quotes where company_id = :companyId
			and quote_number like :prefix || '-%'
			and substring(quote_number from char_length(:prefix) + 2) ~ '^[0-9]+$'
			""", nativeQuery = true)
	int maxQuoteSequence(@Param("companyId") String companyId, @Param("prefix") String prefix);

	@Query("""
			select q from Quote q where q.companyId = :companyId
			and (:dealId is null or q.dealId = :dealId)
			and (q.createdById = :userId or q.dealId in (
			  select d.id from Deal d where d.ownerId = :userId
			))
			order by q.createdAt desc
			""")
	List<Quote> findScoped(@Param("companyId") String companyId, @Param("userId") String userId,
			@Param("dealId") String dealId);
}
