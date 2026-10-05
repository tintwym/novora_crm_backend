package dev.tintwym.novora.modules.reports;

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
@RequestMapping("/api/v1/reports")
public class ReportsController {

	private final ReportsService service;

	public ReportsController(ReportsService service) {
		this.service = service;
	}

	@GetMapping("/sales")
	@RequiresPermission(Permission.VIEW_REPORTS)
	public ApiResponse<Map<String, Object>> sales(
			@RequestParam(name = "from", required = false) String from,
			@RequestParam(name = "to", required = false) String to) {
		return ApiResponse.success(service.salesReport(SecurityUtils.currentUser(), from, to));
	}
}
