package dev.tintwym.novora.modules.activities;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/activities")
public class ActivitiesController {

	private final ActivitiesService service;

	public ActivitiesController(ActivitiesService service) {
		this.service = service;
	}

	@GetMapping("/recent")
	@RequiresPermission(Permission.VIEW_ACTIVITIES)
	public ApiResponse<List<Map<String, Object>>> recent(@RequestParam(required = false) Integer limit) {
		return ApiResponse.success(service.recent(SecurityUtils.currentUser(), limit));
	}

	@GetMapping("/calendar")
	@RequiresPermission(Permission.VIEW_ACTIVITIES)
	public ApiResponse<List<Map<String, Object>>> calendar(@RequestParam String from, @RequestParam String to) {
		return ApiResponse.success(service.calendar(SecurityUtils.currentUser(), from, to));
	}

	@GetMapping("/reminders")
	@RequiresPermission(Permission.VIEW_ACTIVITIES)
	public ApiResponse<List<Map<String, Object>>> reminders() {
		return ApiResponse.success(service.reminders(SecurityUtils.currentUser()));
	}

	@GetMapping
	@RequiresPermission(Permission.VIEW_ACTIVITIES)
	public ApiResponse<List<Map<String, Object>>> list(
			@RequestParam(required = false) String type,
			@RequestParam(required = false) String contactId,
			@RequestParam(required = false) String dealId,
			@RequestParam(required = false) Boolean completed,
			@RequestParam(required = false) Integer limit) {
		return ApiResponse.success(service.list(SecurityUtils.currentUser(), type, contactId, dealId, completed, limit));
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permission.VIEW_ACTIVITIES)
	public ApiResponse<Map<String, Object>> get(@PathVariable String id) {
		return ApiResponse.success(service.get(SecurityUtils.currentUser(), id));
	}

	@PostMapping
	@RequiresPermission(Permission.MANAGE_ACTIVITIES)
	public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.create(SecurityUtils.currentUser(), body)));
	}

	@PatchMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_ACTIVITIES)
	public ApiResponse<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
		return ApiResponse.success(service.update(SecurityUtils.currentUser(), id, body));
	}

	@PostMapping("/{id}/complete")
	@RequiresPermission(Permission.MANAGE_ACTIVITIES)
	public ApiResponse<Map<String, Object>> complete(@PathVariable String id) {
		return ApiResponse.success(service.complete(SecurityUtils.currentUser(), id));
	}

	@DeleteMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_ACTIVITIES)
	public ApiResponse<Map<String, Object>> remove(@PathVariable String id) {
		service.delete(SecurityUtils.currentUser(), id);
		return ApiResponse.success(Map.of());
	}
}
