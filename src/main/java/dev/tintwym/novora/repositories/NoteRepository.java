package dev.tintwym.novora.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.tintwym.novora.domain.entity.Note;
import dev.tintwym.novora.domain.enums.NoteEntityType;

public interface NoteRepository extends JpaRepository<Note, String> {
	List<Note> findByEntityTypeAndEntityIdAndCompanyIdOrderByCreatedAtDesc(
			NoteEntityType entityType, String entityId, String companyId);
	void deleteByEntityTypeAndEntityIdAndCompanyId(NoteEntityType entityType, String entityId, String companyId);
}
