package dev.tintwym.novora.modules.audit;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.tintwym.novora.common.ApiResponse;
import dev.tintwym.novora.security.Permission;
import dev.tintwym.novora.security.RequiresPermission;
import dev.tintwym.novora.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/audit-log")
public class AuditController {

	private final AuditService service;

	public AuditController(AuditService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresPermission(Permission.VIEW_AUDIT_LOG)
	public ApiResponse<Map<String, Object>> list(
			@RequestParam(required = false) String entityType,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) String userId,
			@RequestParam(required = false) String entityId,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) Integer page) {
		return ApiResponse.success(service.list(SecurityUtils.currentUser(), entityType, action, userId, entityId, q, page));
	}
}
