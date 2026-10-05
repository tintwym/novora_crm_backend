package dev.tintwym.novora.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.tintwym.novora.domain.entity.Contact;

public interface ContactRepository extends JpaRepository<Contact, String> {
	Optional<Contact> findByIdAndCompanyId(String id, String companyId);
	List<Contact> findByCompanyIdOrderByUpdatedAtDesc(String companyId);
	List<Contact> findByCompanyIdAndAccountIdOrderByUpdatedAtDesc(String companyId, String accountId);
	Optional<Contact> findFirstByCompanyIdAndEmailIgnoreCase(String companyId, String email);
	long countByAccountId(String accountId);
}
