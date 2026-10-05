package dev.tintwym.novora.modules.users;

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
import org.springframework.web.bind.annotation.RestController;

import dev.tintwym.novora.common.ApiResponse;
import dev.tintwym.novora.security.Permission;
import dev.tintwym.novora.security.RequiresPermission;
import dev.tintwym.novora.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/users")
public class UsersController {

	private final UsersService usersService;

	public UsersController(UsersService usersService) {
		this.usersService = usersService;
	}

	@GetMapping
	@RequiresPermission(Permission.VIEW_USERS)
	public ApiResponse<List<Map<String, Object>>> list() {
		return ApiResponse.success(usersService.list(SecurityUtils.currentUser()));
	}

	@PostMapping
	@RequiresPermission(Permission.MANAGE_USERS)
	public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(usersService.create(SecurityUtils.currentUser(), body)));
	}

	@PatchMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_USERS)
	public ApiResponse<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
		return ApiResponse.success(usersService.update(SecurityUtils.currentUser(), id, body));
	}

	@PostMapping("/{id}/invite")
	@RequiresPermission(Permission.MANAGE_USERS)
	public ApiResponse<Map<String, Object>> resendInvite(@PathVariable String id) {
		return ApiResponse.success(usersService.resendInvite(SecurityUtils.currentUser(), id));
	}
}
