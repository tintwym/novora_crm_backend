package dev.tintwym.novora.modules.contacts;

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
@RequestMapping("/api/v1/contacts")
public class ContactsController {

	private final ContactsService service;
	private final ContactToolsService tools;

	public ContactsController(ContactsService service, ContactToolsService tools) {
		this.service = service;
		this.tools = tools;
	}

	@GetMapping("/duplicates")
	@RequiresPermission(Permission.VIEW_CONTACTS)
	public ApiResponse<List<Map<String, Object>>> duplicates() {
		return ApiResponse.success(tools.duplicateGroups(SecurityUtils.currentUser()));
	}

	@GetMapping("/duplicates/check")
	@RequiresPermission(Permission.VIEW_CONTACTS)
	public ApiResponse<List<Map<String, Object>>> checkDuplicates(@RequestParam(required = false) String email,
			@RequestParam(required = false) String phone, @RequestParam(required = false) String name,
			@RequestParam(required = false) String excludeId) {
		return ApiResponse.success(tools.check(SecurityUtils.currentUser(), email, phone, name, excludeId));
	}

	@PostMapping("/bulk")
	@RequiresPermission(Permission.MANAGE_CONTACTS)
	public ApiResponse<Map<String, Object>> bulk(@RequestBody Map<String, Object> body) {
		return ApiResponse.success(tools.bulk(SecurityUtils.currentUser(), body));
	}

	@PostMapping("/{id}/merge")
	@RequiresPermission(Permission.MANAGE_CONTACTS)
	public ApiResponse<Map<String, Object>> merge(@PathVariable String id, @RequestBody Map<String, Object> body) {
		return ApiResponse.success(tools.merge(SecurityUtils.currentUser(), id, body.get("mergeIds")));
	}

	@GetMapping("/tags")
	@RequiresPermission(Permission.VIEW_CONTACTS)
	public ApiResponse<List<Map<String, Object>>> listTags() {
		return ApiResponse.success(service.listTags(SecurityUtils.currentUser()));
	}

	@PostMapping("/tags")
	@RequiresPermission(Permission.MANAGE_CONTACTS)
	public ResponseEntity<ApiResponse<Map<String, Object>>> createTag(@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.createTag(SecurityUtils.currentUser(), body)));
	}

	@PostMapping("/import")
	@RequiresPermission(Permission.IMPORT_CONTACTS)
	public ApiResponse<Map<String, Object>> importCsv(@RequestBody Map<String, Object> body) {
		return ApiResponse.success(service.importContacts(SecurityUtils.currentUser(), body));
	}

	@GetMapping
	@RequiresPermission(Permission.VIEW_CONTACTS)
	public ApiResponse<List<Map<String, Object>>> list(
			@RequestParam(required = false) String search,
			@RequestParam(required = false) String accountId,
			@RequestParam(required = false) String tag) {
		return ApiResponse.success(service.list(SecurityUtils.currentUser(), search, accountId, tag));
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permission.VIEW_CONTACTS)
	public ApiResponse<Map<String, Object>> get(@PathVariable String id) {
		return ApiResponse.success(service.get(SecurityUtils.currentUser(), id));
	}

	@GetMapping("/{id}/timeline")
	@RequiresPermission(Permission.VIEW_CONTACTS)
	public ApiResponse<List<Map<String, Object>>> timeline(@PathVariable String id) {
		return ApiResponse.success(service.timeline(SecurityUtils.currentUser(), id));
	}

	@PostMapping
	@RequiresPermission(Permission.MANAGE_CONTACTS)
	public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.create(SecurityUtils.currentUser(), body)));
	}

	@PatchMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_CONTACTS)
	public ApiResponse<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
		return ApiResponse.success(service.update(SecurityUtils.currentUser(), id, body));
	}

	@DeleteMapping("/{id}")
	@RequiresPermission(Permission.MANAGE_CONTACTS)
	public ApiResponse<Map<String, Object>> remove(@PathVariable String id) {
		service.delete(SecurityUtils.currentUser(), id);
		return ApiResponse.success(Map.of());
	}

	@PostMapping("/{id}/notes")
	@RequiresPermission(Permission.MANAGE_CONTACTS)
	public ResponseEntity<ApiResponse<Map<String, Object>>> addNote(@PathVariable String id,
			@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(service.addNote(SecurityUtils.currentUser(), id, body)));
	}
}
