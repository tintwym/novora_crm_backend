package dev.tintwym.novora.modules.tickets;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/tickets")
public class TicketsController {

	private final TicketsService service;

	public TicketsController(TicketsService service) {
		this.service = service;
	}

	@GetMapping("/sla-alerts")
	@RequiresPermission(Permission.VIEW_TICKETS)
	public ApiResponse<List<Map<String, Object>>> slaAlerts() {
		return ApiResponse.success(service.slaAlerts(SecurityUtils.currentUser()));
	}

	@GetMapping("/kb")
	@RequiresPermission(Permission.VIEW_TICKETS)
	public ApiResponse<List<Map<String, Object>>> listKb() {
		return ApiResponse.success(service.listKb(SecurityUtils.currentUser()));
	}

	@PostMapping("/kb")
	@RequiresPermission(Permission.MANAGE_TICKETS)
	public ResponseEntity<ApiResponse<Map<String, Object>>> createKb(@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.createKb(SecurityUtils.currentUser(), body)));
	}

	@GetMapping("/portal-info")
	@RequiresPermission(Permission.VIEW_TICKETS)
	public ApiResponse<Map<String, Object>> portalInfo() {
		return ApiResponse.success(service.portalInfo(SecurityUtils.currentUser()));
	}

	@GetMapping
	@RequiresPermission(Permission.VIEW_TICKETS)
	public ApiResponse<List<Map<String, Object>>> list(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String priority,
			@RequestParam(required = false, defaultValue = "false") boolean breachedOnly) {
		return ApiResponse.success(service.list(SecurityUtils.currentUser(), status, priority, breachedOnly));
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permission.VIEW_TICKETS)
	public ApiResponse<Map<String, Object>> get(@PathVariable String id) {
		return ApiResponse.success(service.get(SecurityUtils.currentUser(), id));
	}

	@PostMapping
	@RequiresPermission(Permission.MANAGE_TICKETS)
	public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.create(SecurityUtils.currentUser(), body)));
	}

	@PatchMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_TICKETS)
	public ApiResponse<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
		return ApiResponse.success(service.update(SecurityUtils.currentUser(), id, body));
	}

	@PostMapping("/{id}/messages")
	@RequiresPermission(Permission.MANAGE_TICKETS)
	public ResponseEntity<ApiResponse<Map<String, Object>>> addMessage(@PathVariable String id,
			@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.addMessage(SecurityUtils.currentUser(), id, body)));
	}
}
