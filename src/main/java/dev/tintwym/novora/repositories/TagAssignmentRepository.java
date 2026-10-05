package dev.tintwym.novora.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.tintwym.novora.domain.entity.TagAssignment;
import dev.tintwym.novora.domain.enums.TagEntityType;

public interface TagAssignmentRepository extends JpaRepository<TagAssignment, String> {
	List<TagAssignment> findByEntityTypeAndEntityIdIn(TagEntityType entityType, Iterable<String> entityIds);

	/**
	 * Bulk delete so it executes immediately; a derived delete is deferred until flush,
	 * where Hibernate runs inserts first and re-adding a kept tag violates the unique key.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from TagAssignment a where a.entityType = :entityType and a.entityId = :entityId")
	void deleteByEntityTypeAndEntityId(@Param("entityType") TagEntityType entityType,
			@Param("entityId") String entityId);
}
