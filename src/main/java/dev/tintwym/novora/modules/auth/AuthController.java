package dev.tintwym.novora.modules.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.tintwym.novora.common.ApiResponse;
import dev.tintwym.novora.common.EmailService;
import dev.tintwym.novora.security.Permission;
import dev.tintwym.novora.security.RequiresPermission;
import dev.tintwym.novora.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService authService;

	private final PasswordResetService passwordResets;
	private final EmailService email;

	public AuthController(AuthService authService, PasswordResetService passwordResets, EmailService email) {
		this.passwordResets = passwordResets;
		this.email = email;
		this.authService = authService;
	}

	@PostMapping("/register")
	public ResponseEntity<ApiResponse<Map<String, Object>>> register(@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(authService.register(body)));
	}

	@PostMapping("/forgot-password")
	public ApiResponse<Map<String, Object>> forgotPassword(@RequestBody Map<String, Object> body) {
		passwordResets.requestReset(body.get("email") == null ? null : String.valueOf(body.get("email")));
		return ApiResponse.success(Map.of("message",
				"If that email has an account, we've sent a link to reset the password."));
	}

	@GetMapping("/reset-password")
	public ApiResponse<Map<String, Object>> checkResetLink(@RequestParam(required = false) String token) {
		return ApiResponse.success(passwordResets.check(token));
	}

	@PostMapping("/reset-password")
	public ApiResponse<Map<String, Object>> resetPassword(@RequestBody Map<String, Object> body) {
		Object token = body.get("token");
		Object password = body.get("password");
		return ApiResponse.success(passwordResets.reset(token == null ? null : String.valueOf(token),
				password == null ? null : String.valueOf(password)));
	}

	@GetMapping("/email-status")
	@RequiresPermission(Permission.MANAGE_USERS)
	public ApiResponse<Map<String, Object>> emailStatus() {
		Map<String, Object> status = new java.util.LinkedHashMap<>();
		status.put("enabled", email.isEnabled());
		status.put("from", email.fromAddress());
		return ApiResponse.success(status);
	}

	@PostMapping("/email-test")
	@RequiresPermission(Permission.MANAGE_USERS)
	public ApiResponse<Map<String, Object>> emailTest() {
		var user = SecurityUtils.currentUser();
		String to = authService.me(user.getId()).get("email").toString();
		email.sendNow(new EmailService.Email(to, "Novora CRM test email",
				"Email is working. Password resets, invites and quotes will be sent from this address.",
				email.layout("Email is working", java.util.List.of(
						"Password resets, invites and quotes will be sent from this address."), null, null)));
		return ApiResponse.success(Map.of("sentTo", to));
	}

	@PostMapping("/login")
	public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
		return ApiResponse.success(authService.login(body));
	}

	@PostMapping("/refresh")
	public ApiResponse<Map<String, Object>> refresh(@RequestBody Map<String, Object> body) {
		String token = body.get("refreshToken") == null ? null : String.valueOf(body.get("refreshToken"));
		return ApiResponse.success(authService.refresh(token));
	}

	@PostMapping("/logout")
	public ApiResponse<Map<String, Object>> logout(@RequestBody(required = false) Map<String, Object> body) {
		String token = body == null || body.get("refreshToken") == null ? null : String.valueOf(body.get("refreshToken"));
		authService.logout(token);
		return ApiResponse.success(Map.of());
	}

	@GetMapping("/me")
	public ApiResponse<Map<String, Object>> me() {
		return ApiResponse.success(authService.me(SecurityUtils.currentUser().getId()));
	}

	@PatchMapping("/me")
	public ApiResponse<Map<String, Object>> updateMe(@RequestBody Map<String, Object> body) {
		return ApiResponse.success(authService.updateProfile(SecurityUtils.currentUser().getId(), body));
	}

	@PostMapping("/change-password")
	public ApiResponse<Map<String, Object>> changePassword(@RequestBody Map<String, Object> body) {
		authService.changePassword(SecurityUtils.currentUser(), body);
		return ApiResponse.success(Map.of());
	}

	@GetMapping("/company")
	public ApiResponse<Map<String, Object>> company() {
		return ApiResponse.success(authService.company(SecurityUtils.currentUser().getCompanyId()));
	}

	@PatchMapping("/company")
	@RequiresPermission(Permission.MANAGE_USERS)
	public ApiResponse<Map<String, Object>> updateCompany(@RequestBody Map<String, Object> body) {
		return ApiResponse.success(authService.updateCompany(SecurityUtils.currentUser(), body));
	}
}
