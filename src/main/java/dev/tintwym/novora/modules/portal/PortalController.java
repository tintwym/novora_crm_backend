package dev.tintwym.novora.modules.portal;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.tintwym.novora.common.ApiResponse;
import dev.tintwym.novora.common.AppException;
import dev.tintwym.novora.modules.tickets.TicketsService;

@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {

	private final TicketsService ticketsService;

	public PortalController(TicketsService ticketsService) {
		this.ticketsService = ticketsService;
	}

	@GetMapping("/{portalSlug}/kb")
	public ApiResponse<List<Map<String, Object>>> kb(@PathVariable String portalSlug) {
		return ApiResponse.success(ticketsService.listPublicKb(portalSlug));
	}

	@PostMapping("/{portalSlug}/tickets")
	public ResponseEntity<ApiResponse<Map<String, Object>>> createTicket(@PathVariable String portalSlug,
			@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(ticketsService.createPortalTicket(portalSlug, body)));
	}

	@PostMapping("/{portalSlug}/track")
	public ApiResponse<Map<String, Object>> track(@PathVariable String portalSlug,
			@RequestBody Map<String, Object> body) {
		String email = required(body, "email");
		String ticketNumber = required(body, "ticketNumber");
		return ApiResponse.success(ticketsService.trackPortalTicket(portalSlug, email, ticketNumber));
	}

	@PostMapping("/{portalSlug}/reply")
	public ResponseEntity<ApiResponse<Map<String, Object>>> reply(@PathVariable String portalSlug,
			@RequestBody Map<String, Object> body) {
		String email = required(body, "email");
		String ticketNumber = required(body, "ticketNumber");
		String messageBody = required(body, "body");
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
				ticketsService.addPortalReply(portalSlug, email, ticketNumber, messageBody)));
	}

	private static String required(Map<String, Object> body, String key) {
		Object v = body.get(key);
		if (v == null || String.valueOf(v).isBlank()) {
			throw new AppException(key + " is required", HttpStatus.BAD_REQUEST);
		}
		return String.valueOf(v).trim();
	}
}
