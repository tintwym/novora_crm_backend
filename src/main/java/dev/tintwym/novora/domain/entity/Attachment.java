package dev.tintwym.novora.domain.entity;

import java.time.Instant;

import dev.tintwym.novora.common.Cuid;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "attachments")
public class Attachment {

	@Id
	private String id;

	@Column(name = "file_name", nullable = false)
	private String fileName;

	@Column(name = "file_url")
	private String fileUrl;

	@Column(name = "storage_key")
	private String storageKey;

	@Column(name = "mime_type")
	private String mimeType;

	@Column(name = "size_bytes")
	private Integer sizeBytes;

	@Column(name = "company_id", nullable = false)
	private String companyId;

	@Column(name = "deal_id")
	private String dealId;

	@Column(name = "uploaded_by")
	private String uploadedBy;

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

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public String getFileUrl() {
		return fileUrl;
	}

	public void setFileUrl(String fileUrl) {
		this.fileUrl = fileUrl;
	}

	public String getMimeType() {
		return mimeType;
	}

	public void setMimeType(String mimeType) {
		this.mimeType = mimeType;
	}

	public Integer getSizeBytes() {
		return sizeBytes;
	}

	public void setSizeBytes(Integer sizeBytes) {
		this.sizeBytes = sizeBytes;
	}

	public String getCompanyId() {
		return companyId;
	}

	public void setCompanyId(String companyId) {
		this.companyId = companyId;
	}

	public String getDealId() {
		return dealId;
	}

	public void setDealId(String dealId) {
		this.dealId = dealId;
	}

	public String getUploadedBy() {
		return uploadedBy;
	}

	public void setUploadedBy(String uploadedBy) {
		this.uploadedBy = uploadedBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getStorageKey() {
		return storageKey;
	}

	public void setStorageKey(String storageKey) {
		this.storageKey = storageKey;
	}
}
