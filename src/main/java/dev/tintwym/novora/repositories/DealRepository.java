package dev.tintwym.novora.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.tintwym.novora.domain.entity.Deal;
import dev.tintwym.novora.domain.enums.DealStage;

public interface DealRepository extends JpaRepository<Deal, String> {
	Optional<Deal> findByIdAndCompanyId(String id, String companyId);
	List<Deal> findByCompanyIdOrderByStageAscUpdatedAtDesc(String companyId);
	List<Deal> findByCompanyIdAndOwnerIdOrderByStageAscUpdatedAtDesc(String companyId, String ownerId);
	List<Deal> findByCompanyIdAndStageOrderByUpdatedAtDesc(String companyId, DealStage stage);
	List<Deal> findByCompanyIdAndOwnerIdAndStageOrderByUpdatedAtDesc(String companyId, String ownerId, DealStage stage);
	List<Deal> findByCompanyIdAndStageNotOrderByUpdatedAtDesc(String companyId, DealStage stage);
	List<Deal> findByCompanyIdAndOwnerIdAndStageNotOrderByUpdatedAtDesc(String companyId, String ownerId, DealStage stage);
	List<Deal> findByContactIdAndCompanyIdOrderByUpdatedAtDesc(String contactId, String companyId);
	List<Deal> findByAccountIdOrderByUpdatedAtDesc(String accountId);
	long countByAccountId(String accountId);
}
