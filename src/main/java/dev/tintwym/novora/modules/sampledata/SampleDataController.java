package dev.tintwym.novora.modules.sampledata;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.tintwym.novora.common.ApiResponse;
import dev.tintwym.novora.security.Permission;
import dev.tintwym.novora.security.RequiresPermission;
import dev.tintwym.novora.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/sample-data")
public class SampleDataController {

	private final SampleDataService service;

	public SampleDataController(SampleDataService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresPermission(Permission.VIEW_USERS)
	public ApiResponse<Map<String, Object>> status() {
		return ApiResponse.success(service.status(SecurityUtils.currentUser()));
	}

	@PostMapping
	@RequiresPermission(Permission.MANAGE_USERS)
	public ApiResponse<Map<String, Object>> load() {
		return ApiResponse.success(service.load(SecurityUtils.currentUser()), "Sample data loaded");
	}

	@DeleteMapping
	@RequiresPermission(Permission.MANAGE_USERS)
	public ApiResponse<Map<String, Object>> remove() {
		return ApiResponse.success(service.remove(SecurityUtils.currentUser()), "Sample data removed");
	}
}
