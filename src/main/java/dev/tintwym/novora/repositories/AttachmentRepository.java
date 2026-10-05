package dev.tintwym.novora.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.tintwym.novora.domain.entity.Attachment;

public interface AttachmentRepository extends JpaRepository<Attachment, String> {
	List<Attachment> findByDealIdOrderByCreatedAtDesc(String dealId);
	Optional<Attachment> findByIdAndCompanyId(String id, String companyId);
}
