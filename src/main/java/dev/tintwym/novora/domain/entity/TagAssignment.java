package dev.tintwym.novora.domain.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import dev.tintwym.novora.common.Cuid;
import dev.tintwym.novora.domain.enums.TagEntityType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "tag_assignments")
public class TagAssignment {

	@Id
	private String id;

	@Column(name = "tag_id", nullable = false)
	private String tagId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "entity_type", nullable = false, columnDefinition = "\"TagEntityType\"")
	private TagEntityType entityType;

	@Column(name = "entity_id", nullable = false)
	private String entityId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@PrePersist
	void onCreate() {
		if (id == null) {
			id = Cuid.generate();
		}
		createdAt = Instant.now();
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getTagId() {
		return tagId;
	}

	public void setTagId(String tagId) {
		this.tagId = tagId;
	}

	public TagEntityType getEntityType() {
		return entityType;
	}

	public void setEntityType(TagEntityType entityType) {
		this.entityType = entityType;
	}

	public String getEntityId() {
		return entityId;
	}

	public void setEntityId(String entityId) {
		this.entityId = entityId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
