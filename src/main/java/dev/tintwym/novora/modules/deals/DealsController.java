package dev.tintwym.novora.modules.deals;

import java.util.List;
import java.util.Map;

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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import dev.tintwym.novora.common.ApiResponse;
import dev.tintwym.novora.security.Permission;
import dev.tintwym.novora.security.RequiresPermission;
import dev.tintwym.novora.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/deals")
public class DealsController {

	private final DealsService service;

	public DealsController(DealsService service) {
		this.service = service;
	}

	@GetMapping("/pipeline")
	@RequiresPermission(Permission.VIEW_DEALS)
	public ApiResponse<Map<String, Object>> pipeline() {
		return ApiResponse.success(service.pipeline(SecurityUtils.currentUser()));
	}

	@GetMapping("/forecast")
	@RequiresPermission(Permission.FORECAST_DEALS)
	public ApiResponse<Map<String, Object>> forecast() {
		return ApiResponse.success(service.forecast(SecurityUtils.currentUser()));
	}

	@GetMapping
	@RequiresPermission(Permission.VIEW_DEALS)
	public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String stage) {
		return ApiResponse.success(service.list(SecurityUtils.currentUser(), stage));
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permission.VIEW_DEALS)
	public ApiResponse<Map<String, Object>> get(@PathVariable String id) {
		return ApiResponse.success(service.get(SecurityUtils.currentUser(), id));
	}

	@PostMapping
	@RequiresPermission(Permission.MANAGE_DEALS)
	public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.create(SecurityUtils.currentUser(), body)));
	}

	@PatchMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_DEALS)
	public ApiResponse<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
		return ApiResponse.success(service.update(SecurityUtils.currentUser(), id, body));
	}

	@PatchMapping("/{id}/stage")
	@RequiresPermission(Permission.MANAGE_DEALS)
	public ApiResponse<Map<String, Object>> updateStage(@PathVariable String id, @RequestBody Map<String, Object> body) {
		return ApiResponse.success(service.updateStage(SecurityUtils.currentUser(), id, body));
	}

	@DeleteMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_DEALS)
	public ApiResponse<Map<String, Object>> remove(@PathVariable String id) {
		service.delete(SecurityUtils.currentUser(), id);
		return ApiResponse.success(Map.of());
	}

	@PostMapping("/{id}/activities")
	@RequiresPermission(Permission.MANAGE_ACTIVITIES)
	public ResponseEntity<ApiResponse<Map<String, Object>>> addActivity(@PathVariable String id,
			@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.addActivity(SecurityUtils.currentUser(), id, body)));
	}

	@PostMapping(path = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@RequiresPermission(Permission.MANAGE_DEALS)
	public ResponseEntity<ApiResponse<Map<String, Object>>> uploadAttachment(@PathVariable String id,
			@RequestPart(name = "file", required = false) MultipartFile file) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.uploadAttachment(SecurityUtils.currentUser(), id, file)));
	}

	@PostMapping(path = "/{id}/attachments", consumes = MediaType.APPLICATION_JSON_VALUE)
	@RequiresPermission(Permission.MANAGE_DEALS)
	public ResponseEntity<ApiResponse<Map<String, Object>>> addAttachment(@PathVariable String id,
			@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.addAttachment(SecurityUtils.currentUser(), id, body)));
	}
}
