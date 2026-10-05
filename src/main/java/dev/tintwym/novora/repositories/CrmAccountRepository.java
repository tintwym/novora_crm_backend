package dev.tintwym.novora.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.tintwym.novora.domain.entity.CrmAccount;

public interface CrmAccountRepository extends JpaRepository<CrmAccount, String> {
	Optional<CrmAccount> findByIdAndCompanyId(String id, String companyId);
	List<CrmAccount> findByCompanyIdOrderByNameAsc(String companyId);

	@Query("""
			select a from CrmAccount a where a.companyId = :companyId and (
			  lower(a.name) like lower(concat('%', :search, '%'))
			  or lower(coalesce(a.industry, '')) like lower(concat('%', :search, '%'))
			  or lower(coalesce(a.email, '')) like lower(concat('%', :search, '%'))
			) order by a.name asc
			""")
	List<CrmAccount> search(@Param("companyId") String companyId, @Param("search") String search);

	Optional<CrmAccount> findFirstByCompanyIdAndNameIgnoreCase(String companyId, String name);
	long countByCompanyIdAndOwnerId(String companyId, String ownerId);
}
