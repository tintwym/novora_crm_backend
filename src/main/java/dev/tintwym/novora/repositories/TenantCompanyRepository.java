package dev.tintwym.novora.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.tintwym.novora.domain.entity.TenantCompany;

public interface TenantCompanyRepository extends JpaRepository<TenantCompany, String> {
	Optional<TenantCompany> findByPortalSlug(String portalSlug);
}
