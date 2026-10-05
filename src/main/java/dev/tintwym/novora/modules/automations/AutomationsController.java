package dev.tintwym.novora.modules.automations;

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
import org.springframework.web.bind.annotation.RestController;

import dev.tintwym.novora.common.ApiResponse;
import dev.tintwym.novora.security.Permission;
import dev.tintwym.novora.security.RequiresPermission;
import dev.tintwym.novora.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/automations")
public class AutomationsController {

	private final AutomationService service;

	public AutomationsController(AutomationService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresPermission(Permission.MANAGE_AUTOMATIONS)
	public ApiResponse<List<Map<String, Object>>> list() {
		return ApiResponse.success(service.list(SecurityUtils.currentUser()));
	}

	@PostMapping
	@RequiresPermission(Permission.MANAGE_AUTOMATIONS)
	public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.create(SecurityUtils.currentUser(), body)));
	}

	@PatchMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_AUTOMATIONS)
	public ApiResponse<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
		return ApiResponse.success(service.update(SecurityUtils.currentUser(), id, body));
	}

	@DeleteMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_AUTOMATIONS)
	public ApiResponse<Map<String, Object>> remove(@PathVariable String id) {
		service.delete(SecurityUtils.currentUser(), id);
		return ApiResponse.success(Map.of());
	}
}
