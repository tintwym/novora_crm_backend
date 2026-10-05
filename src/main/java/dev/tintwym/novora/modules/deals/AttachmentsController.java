package dev.tintwym.novora.modules.deals;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.tintwym.novora.common.ApiResponse;
import dev.tintwym.novora.security.Permission;
import dev.tintwym.novora.security.RequiresPermission;
import dev.tintwym.novora.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentsController {

	private final DealsService service;

	public AttachmentsController(DealsService service) {
		this.service = service;
	}

	@GetMapping("/{id}/download")
	@RequiresPermission(Permission.VIEW_DEALS)
	public ResponseEntity<Resource> download(@PathVariable String id) {
		DealsService.FileDownload file = service.download(SecurityUtils.currentUser(), id);
		ContentDisposition disposition = (file.inline() ? ContentDisposition.inline() : ContentDisposition.attachment())
				.filename(file.fileName(), StandardCharsets.UTF_8)
				.build();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
				.header("X-Content-Type-Options", "nosniff")
				.header("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox")
				.contentType(MediaType.parseMediaType(file.contentType()))
				.body(new FileSystemResource(file.path()));
	}

	@DeleteMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_DEALS)
	public ApiResponse<Map<String, Object>> remove(@PathVariable String id) {
		service.deleteAttachment(SecurityUtils.currentUser(), id);
		return ApiResponse.success(Map.of());
	}
}
