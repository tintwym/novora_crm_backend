package dev.tintwym.novora.modules.integrations.hrms;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.tintwym.novora.common.ApiResponse;
import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.security.Permission;
import dev.tintwym.novora.security.RequiresPermission;
import dev.tintwym.novora.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping(HrmsIntegrationController.BASE_PATH)
public class HrmsIntegrationController {

	public static final String BASE_PATH = "/api/v1/integrations/hrms";
	public static final String EVENTS_PATH = BASE_PATH + "/events";

	private final HrmsIntegrationService service;
	private final HrmsSettings settings;

	public HrmsIntegrationController(HrmsIntegrationService service, HrmsSettings settings) {
		this.service = service;
		this.settings = settings;
	}

	@GetMapping
	@RequiresPermission(Permission.MANAGE_USERS)
	public ApiResponse<Map<String, Object>> status() {
		return ApiResponse.success(service.status(SecurityUtils.currentUser()));
	}

	@PostMapping("/connect")
	@RequiresPermission(Permission.MANAGE_USERS)
	public ApiResponse<Map<String, Object>> connect(@RequestBody Map<String, Object> body) {
		Object code = body.get("code");
		return ApiResponse.success(service.connect(SecurityUtils.currentUser(), code == null ? null : String.valueOf(code)));
	}

	@DeleteMapping
	@RequiresPermission(Permission.MANAGE_USERS)
	public ApiResponse<Map<String, Object>> disconnect() {
		return ApiResponse.success(service.disconnect(SecurityUtils.currentUser()));
	}

	@PatchMapping("/settings")
	@RequiresPermission(Permission.MANAGE_USERS)
	public ApiResponse<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> body) {
		return ApiResponse.success(service.updateSettings(SecurityUtils.currentUser(), body));
	}

	@PostMapping("/sync")
	@RequiresPermission(Permission.MANAGE_USERS)
	public ApiResponse<Map<String, Object>> syncNow() {
		return ApiResponse.success(service.syncNow(SecurityUtils.currentUser()));
	}

	/** Called by Novora HRMS, not by a signed-in user: authenticated by the request signature alone. */
	@PostMapping("/events")
	public ApiResponse<Map<String, Object>> receiveEvent(@RequestBody(required = false) String body,
			HttpServletRequest request) {
		if (!settings.isReady()) {
			throw new AppException("The HRMS connection isn't set up on this server", HttpStatus.SERVICE_UNAVAILABLE);
		}
		String payload = body == null ? "" : body;
		String query = request.getQueryString();
		String pathAndQuery = request.getRequestURI() + (query == null || query.isEmpty() ? "" : "?" + query);
		boolean valid = RequestSigner.verify(settings.secret(), request.getHeader(RequestSigner.TIMESTAMP_HEADER),
				request.getHeader(RequestSigner.SIGNATURE_HEADER), request.getMethod(), pathAndQuery, payload,
				Instant.now());
		if (!valid) {
			throw new AppException("Invalid or expired request signature", HttpStatus.UNAUTHORIZED);
		}
		return ApiResponse.success(service.handleEvent(payload));
	}
}
