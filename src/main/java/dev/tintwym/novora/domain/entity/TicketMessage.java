package dev.tintwym.novora.domain.entity;

import java.time.Instant;

import dev.tintwym.novora.common.Cuid;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "ticket_messages")
public class TicketMessage {

	@Id
	private String id;

	@Column(name = "ticket_id", nullable = false)
	private String ticketId;

	@Column(nullable = false)
	private String body;

	@Column(name = "is_internal", nullable = false)
	private boolean isInternal = false;

	@Column(name = "author_id")
	private String authorId;

	@Column(name = "author_name")
	private String authorName;

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

	public String getTicketId() {
		return ticketId;
	}

	public void setTicketId(String ticketId) {
		this.ticketId = ticketId;
	}

	public String getBody() {
		return body;
	}

	public void setBody(String body) {
		this.body = body;
	}

	public boolean isInternal() {
		return isInternal;
	}

	public void setInternal(boolean internal) {
		isInternal = internal;
	}

	public String getAuthorId() {
		return authorId;
	}

	public void setAuthorId(String authorId) {
		this.authorId = authorId;
	}

	public String getAuthorName() {
		return authorName;
	}

	public void setAuthorName(String authorName) {
		this.authorName = authorName;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
