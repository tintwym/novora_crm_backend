package dev.tintwym.novora.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.tintwym.novora.domain.entity.Tag;

public interface TagRepository extends JpaRepository<Tag, String> {
	List<Tag> findByCompanyIdOrderByNameAsc(String companyId);
	Optional<Tag> findByCompanyIdAndName(String companyId, String name);
	List<Tag> findByIdInAndCompanyId(Iterable<String> ids, String companyId);
}
