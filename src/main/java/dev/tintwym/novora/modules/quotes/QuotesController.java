package dev.tintwym.novora.modules.quotes;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.tintwym.novora.common.ApiResponse;
import dev.tintwym.novora.security.Permission;
import dev.tintwym.novora.security.RequiresPermission;
import dev.tintwym.novora.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/quotes")
public class QuotesController {

	private final QuotesService service;

	public QuotesController(QuotesService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresPermission(Permission.VIEW_QUOTES)
	public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String dealId) {
		return ApiResponse.success(service.list(SecurityUtils.currentUser(), dealId));
	}

	@GetMapping("/{id}/pdf")
	@RequiresPermission(Permission.VIEW_QUOTES)
	public ResponseEntity<byte[]> pdf(@PathVariable String id) {
		QuotesService.PdfResult pdf = service.exportPdf(SecurityUtils.currentUser(), id);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.fileName() + "\"")
				.contentType(MediaType.APPLICATION_PDF)
				.body(pdf.buffer());
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permission.VIEW_QUOTES)
	public ApiResponse<Map<String, Object>> get(@PathVariable String id) {
		return ApiResponse.success(service.get(SecurityUtils.currentUser(), id));
	}

	@PostMapping
	@RequiresPermission(Permission.MANAGE_QUOTES)
	public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.create(SecurityUtils.currentUser(), body)));
	}

	@PatchMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_QUOTES)
	public ApiResponse<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
		return ApiResponse.success(service.update(SecurityUtils.currentUser(), id, body));
	}

	@PatchMapping("/{id}/status")
	@RequiresPermission(Permission.MANAGE_QUOTES)
	public ApiResponse<Map<String, Object>> updateStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
		return ApiResponse.success(service.updateStatus(SecurityUtils.currentUser(), id, body));
	}

	@PostMapping("/{id}/email")
	@RequiresPermission(Permission.MANAGE_QUOTES)
	public ApiResponse<Map<String, Object>> email(@PathVariable String id, @RequestBody Map<String, Object> body) {
		return ApiResponse.success(service.emailQuote(SecurityUtils.currentUser(), id, body));
	}

	@DeleteMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_QUOTES)
	public ApiResponse<Map<String, Object>> remove(@PathVariable String id) {
		service.delete(SecurityUtils.currentUser(), id);
		return ApiResponse.success(Map.of());
	}
}
