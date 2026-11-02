package dev.tintwym.novora.modules.integrations.hrms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end checks of the HRMS connection against a fake Novora HRMS server: linking, signed events, role
 * mapping, offboarding with reassignment, the safety rules, the reconcile, and workspace isolation.
 */
@SpringBootTest(properties = {
		"novora.hrms.enabled=true",
		"novora.hrms.integration-secret=" + HrmsIntegrationTest.SECRET,
		"novora.hrms.sync-on-startup=false",
		"novora.hrms.nightly-sync-cron=-" })
@AutoConfigureMockMvc
class HrmsIntegrationTest {

	static final String SECRET = "crm-test-secret-that-is-at-least-32-chars";
	private static final String GOOD_CODE = "ABCD-EFGH";
	private static final String EVENTS = HrmsIntegrationController.EVENTS_PATH;

	private static HttpServer fakeHrms;
	private static volatile String hrmsOrgId;
	private static volatile String rosterJson = "[]";
	private static volatile String lastCompanyHeader;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private record Workspace(String token, String companyId, String adminId, String adminEmail) {}

	@DynamicPropertySource
	static void hrmsUrl(DynamicPropertyRegistry registry) throws IOException {
		if (fakeHrms == null) {
			fakeHrms = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			fakeHrms.createContext(HrmsClient.BASE_PATH + "/", HrmsIntegrationTest::handleHrms);
			fakeHrms.start();
		}
		registry.add("novora.hrms.base-url", () -> "http://127.0.0.1:" + fakeHrms.getAddress().getPort());
	}

	@AfterAll
	static void stopFakeHrms() {
		if (fakeHrms != null) fakeHrms.stop(0);
		fakeHrms = null;
	}

	@BeforeEach
	void requireDatabase() {
		boolean up;
		try {
			jdbc.queryForObject("select 1", Integer.class);
			up = true;
		} catch (Exception e) {
			up = false;
		}
		Assumptions.assumeTrue(up, "Skipping — PostgreSQL not available");
		hrmsOrgId = UUID.randomUUID().toString();
		rosterJson = "[]";
		lastCompanyHeader = null;
	}

	// ------------------------------------------------------------------------------------------------- tests

	@Test
	void connectingNeedsAValidCodeAndAnAdmin() throws Exception {
		Workspace ws = register();

		mockMvc.perform(post("/api/v1/integrations/hrms/connect").header("Authorization", bearer(ws))
						.contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"ZZZZ-ZZZZ\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("This code is invalid or has expired. Ask your HR admin for a new one."));

		connect(ws)
				.andExpect(jsonPath("$.data.connected").value(true))
				.andExpect(jsonPath("$.data.hrmsOrganizationName").value("Acme HR"))
				.andExpect(jsonPath("$.data.salesDepartmentCodes[0]").value("SLS"));

		mockMvc.perform(post("/api/v1/integrations/hrms/connect").header("Authorization", bearer(ws))
						.contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + GOOD_CODE + "\"}"))
				.andExpect(status().isConflict());

		String repToken = loginAs(ws, "Rep", "SALES_REP");
		mockMvc.perform(get("/api/v1/integrations/hrms").header("Authorization", "Bearer " + repToken))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/v1/integrations/hrms/sync").header("Authorization", "Bearer " + repToken))
				.andExpect(status().isForbidden());
	}

	@Test
	void eventsWithoutAValidSignatureAreRejected() throws Exception {
		Workspace ws = register();
		connect(ws);
		String employeeId = UUID.randomUUID().toString();
		String body = event(employee(employeeId, "Sam", "Stone", unique("sam"), "active", "SLS", "junior", null),
				Instant.now());

		mockMvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());

		long ts = Instant.now().getEpochSecond();
		String signedForOtherBody = RequestSigner.sign(SECRET, ts, "POST", EVENTS, body.replace("junior", "manager"));
		mockMvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).content(body)
						.header(RequestSigner.TIMESTAMP_HEADER, Long.toString(ts))
						.header(RequestSigner.SIGNATURE_HEADER, signedForOtherBody))
				.andExpect(status().isUnauthorized());

		long old = ts - 3600;
		mockMvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).content(body)
						.header(RequestSigner.TIMESTAMP_HEADER, Long.toString(old))
						.header(RequestSigner.SIGNATURE_HEADER, RequestSigner.sign(SECRET, old, "POST", EVENTS, body)))
				.andExpect(status().isUnauthorized());

		assertNull(userByHrmsId(ws, employeeId));
	}

	@Test
	void hiresCreateInvitedUsersAndRepeatsOrOldEventsChangeNothing() throws Exception {
		Workspace ws = register();
		connect(ws);
		String id = UUID.randomUUID().toString();
		String email = unique("maya");
		Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		String hire = event(employee(id, "Maya", "Chen", email, "active", "SLS", "manager", null), t0);
		postEvent(hire).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("created"));
		Map<String, Object> user = userByHrmsId(ws, id);
		assertEquals("SALES_MANAGER", user.get("role"));
		assertEquals(email, user.get("email"));
		assertEquals("Maya Chen", user.get("name"));
		assertEquals(1, count("select count(*) from password_reset_tokens where user_id = ? and purpose = 'INVITE'",
				user.get("id")));

		postEvent(hire).andExpect(jsonPath("$.data.status").value("duplicate"));
		assertEquals(1, count("select count(*) from users where email = ?", email));

		postEvent(event(employee(id, "Maya", "Chen", email, "active", "SLS", "junior", null), t0.plusSeconds(60)))
				.andExpect(jsonPath("$.data.status").value("updated"));
		assertEquals("SALES_REP", userByHrmsId(ws, id).get("role"));

		postEvent(event(employee(id, "Maya", "Chen", email, "active", "SLS", "director", null), t0.plusSeconds(30)))
				.andExpect(jsonPath("$.data.status").value("stale"));
		assertEquals("SALES_REP", userByHrmsId(ws, id).get("role"));

		String engineer = UUID.randomUUID().toString();
		postEvent(event(employee(engineer, "Eli", "Park", unique("eli"), "active", "ENG", "senior", null), t0))
				.andExpect(jsonPath("$.data.status").value("skipped"));
		assertNull(userByHrmsId(ws, engineer));
	}

	@Test
	void leaversLoseAccessAndTheirOpenWorkMovesToTheirManager() throws Exception {
		Workspace ws = register();
		connect(ws);
		Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		String managerId = UUID.randomUUID().toString();
		String repId = UUID.randomUUID().toString();
		String repEmail = unique("rory");
		postEvent(event(employee(managerId, "Mia", "Boss", unique("mia"), "active", "SLS", "manager", null), t0));
		postEvent(event(employee(repId, "Rory", "Rep", repEmail, "active", "SLS", "junior", managerId), t0));
		String manager = (String) userByHrmsId(ws, managerId).get("id");
		String rep = (String) userByHrmsId(ws, repId).get("id");

		mockMvc.perform(patch("/api/v1/users/" + rep).header("Authorization", bearer(ws))
						.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}"))
				.andExpect(status().isOk());
		login(repEmail, "password123");

		String openDeal = insertDeal(ws, rep, "PROPOSAL");
		String wonDeal = insertDeal(ws, rep, "WON");
		String openTicket = insertTicket(ws, rep, "OPEN");
		String closedTicket = insertTicket(ws, rep, "CLOSED");
		String contact = insertContact(ws, rep);

		postEvent(event(employee(repId, "Rory", "Rep", null, "terminated", "SLS", "junior", managerId), t0.plusSeconds(10)))
				.andExpect(jsonPath("$.data.status").value("disabled"));

		Map<String, Object> after = userByHrmsId(ws, repId);
		assertEquals(false, after.get("is_active"));
		assertEquals(true, after.get("hrms_deactivated"));
		assertEquals(repEmail, after.get("email"));
		assertEquals(0, count("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", rep));
		assertEquals(manager, value("select owner_id from deals where id = ?", openDeal));
		assertEquals(rep, value("select owner_id from deals where id = ?", wonDeal));
		assertEquals(manager, value("select assignee_id from tickets where id = ?", openTicket));
		assertEquals(rep, value("select assignee_id from tickets where id = ?", closedTicket));
		assertEquals(manager, value("select owner_id from contacts where id = ?", contact));
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of("email", repEmail, "password", "password123"))))
				.andExpect(status().is4xxClientError());

		postEvent(event(employee(repId, "Rory", "Rep", repEmail, "active", "SLS", "junior", managerId), t0.plusSeconds(20)))
				.andExpect(jsonPath("$.data.status").value("reactivated"));
		assertEquals(true, userByHrmsId(ws, repId).get("is_active"));
	}

	@Test
	void leaverWithoutALinkedManagerHandsWorkToAnAdmin() throws Exception {
		Workspace ws = register();
		connect(ws);
		String repId = UUID.randomUUID().toString();
		postEvent(event(employee(repId, "Lone", "Rep", unique("lone"), "active", "CS", "junior", null), Instant.now()));
		String rep = (String) userByHrmsId(ws, repId).get("id");
		String ticket = insertTicket(ws, rep, "IN_PROGRESS");

		postEvent(event(employee(repId, "Lone", "Rep", null, "terminated", "CS", "junior", UUID.randomUUID().toString()),
				Instant.now().plusSeconds(5))).andExpect(jsonPath("$.data.status").value("disabled"));
		assertEquals(ws.adminId(), value("select assignee_id from tickets where id = ?", ticket));
	}

	@Test
	void syncRespectsManualDeactivationAndNeverRemovesTheLastAdmin() throws Exception {
		Workspace ws = register();
		connect(ws);
		Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		String repId = UUID.randomUUID().toString();
		String repEmail = unique("kai");
		postEvent(event(employee(repId, "Kai", "Lim", repEmail, "active", "SLS", "junior", null), t0));
		String rep = (String) userByHrmsId(ws, repId).get("id");
		mockMvc.perform(patch("/api/v1/users/" + rep).header("Authorization", bearer(ws))
						.contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
				.andExpect(status().isOk());
		postEvent(event(employee(repId, "Kai", "Lim", repEmail, "active", "SLS", "senior", null), t0.plusSeconds(10)));
		assertEquals(false, userByHrmsId(ws, repId).get("is_active"));

		String adminEmployee = UUID.randomUUID().toString();
		postEvent(event(employee(adminEmployee, "Ada", "Admin", ws.adminEmail(), "active", "ENG", "director", null), t0))
				.andExpect(jsonPath("$.data.status").value("linked"));
		assertEquals("ADMIN", userByHrmsId(ws, adminEmployee).get("role"));

		postEvent(event(employee(adminEmployee, "Ada", "Admin", null, "terminated", "ENG", "director", null),
				t0.plusSeconds(10)));
		Map<String, Object> admin = userByHrmsId(ws, adminEmployee);
		assertEquals(true, admin.get("is_active"));
		assertEquals("ADMIN", admin.get("role"));
	}

	@Test
	void movingOutOfSalesOnlyRemovesAccountsTheSyncCreated() throws Exception {
		Workspace ws = register();
		connect(ws);
		Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		String synced = UUID.randomUUID().toString();
		String syncedEmail = unique("sid");
		postEvent(event(employee(synced, "Sid", "Synced", syncedEmail, "active", "SLS", "junior", null), t0));

		String manualEmail = unique("manu");
		createUser(ws, "Manu", manualEmail, "SALES_REP");
		String manual = UUID.randomUUID().toString();
		postEvent(event(employee(manual, "Manu", "Al", manualEmail, "active", "ENG", "senior", null), t0))
				.andExpect(jsonPath("$.data.status").value("linked"));

		postEvent(event(employee(synced, "Sid", "Synced", syncedEmail, "active", "ENG", "junior", null), t0.plusSeconds(10)))
				.andExpect(jsonPath("$.data.status").value("disabled"));
		assertEquals(false, userByHrmsId(ws, synced).get("is_active"));
		Map<String, Object> manualUser = userByHrmsId(ws, manual);
		assertEquals(true, manualUser.get("is_active"));
		assertEquals("SALES_REP", manualUser.get("role"));

		postEvent(event(employee(manual, "Manu", "Al", null, "terminated", "ENG", "senior", null), t0.plusSeconds(10)))
				.andExpect(jsonPath("$.data.status").value("disabled"));
	}

	@Test
	void oneWorkspaceCannotReachAnother() throws Exception {
		Workspace a = register();
		Workspace b = register();
		connect(a);
		String bUserEmail = unique("bee");
		createUser(b, "Bee", bUserEmail, "SALES_REP");

		String otherOrg = UUID.randomUUID().toString();
		postEvent(event(otherOrg, employee(UUID.randomUUID().toString(), "Zed", "Zee", unique("zed"), "active", "SLS",
				"junior", null), Instant.now())).andExpect(jsonPath("$.data.status").value("ignored"));

		String stealer = UUID.randomUUID().toString();
		postEvent(event(employee(stealer, "Bee", "Taken", bUserEmail, "active", "SLS", "manager", null), Instant.now()))
				.andExpect(jsonPath("$.data.status").value("skipped"));
		Map<String, Object> bUser = jdbc.queryForMap("select * from users where email = ?", bUserEmail);
		assertEquals(b.companyId(), bUser.get("company_id"));
		assertEquals("SALES_REP", bUser.get("role"));
		assertNull(bUser.get("hrms_employee_id"));

		mockMvc.perform(get("/api/v1/integrations/hrms").header("Authorization", bearer(b)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.connected").value(false));
	}

	@Test
	void syncNowAppliesTheRosterAndRefusesAnEmptyOne() throws Exception {
		Workspace ws = register();
		connect(ws);
		String rep = UUID.randomUUID().toString();
		String agent = UUID.randomUUID().toString();
		rosterJson = jsonMapper.writeValueAsString(List.of(
				employee(rep, "Ria", "Sales", unique("ria"), "active", "SLS", "junior", null),
				employee(agent, "Ash", "Help", unique("ash"), "on_leave", "CS", "mid", null),
				employee(UUID.randomUUID().toString(), "Dev", "Eng", unique("dev"), "active", "ENG", "senior", null),
				employee(UUID.randomUUID().toString(), "Old", "Timer", null, "terminated", "SLS", "junior", null)));

		mockMvc.perform(post("/api/v1/integrations/hrms/sync").header("Authorization", bearer(ws)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.summary.created").value(2))
				.andExpect(jsonPath("$.data.summary.skipped").value(2))
				.andExpect(jsonPath("$.data.lastSyncStatus").value("OK"))
				.andExpect(jsonPath("$.data.linkedUsers").value(2));
		assertEquals(ws.companyId(), lastCompanyHeader);
		assertEquals("SALES_REP", userByHrmsId(ws, rep).get("role"));
		assertEquals("SUPPORT_AGENT", userByHrmsId(ws, agent).get("role"));

		rosterJson = "[]";
		mockMvc.perform(post("/api/v1/integrations/hrms/sync").header("Authorization", bearer(ws)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.summary.status").value("FAILED"))
				.andExpect(jsonPath("$.data.lastSyncStatus").value("FAILED"));
		assertEquals(true, userByHrmsId(ws, rep).get("is_active"));
	}

	@Test
	void departmentCodesAreValidatedAndDisconnectStopsEvents() throws Exception {
		Workspace ws = register();
		connect(ws);
		String settings = "/api/v1/integrations/hrms/settings";

		mockMvc.perform(patch(settings).header("Authorization", bearer(ws)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"salesDepartmentCodes\":[\"SLS\",\"SUPPORT\"]}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(patch(settings).header("Authorization", bearer(ws)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"salesDepartmentCodes\":[\"bad code\"]}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(patch(settings).header("Authorization", bearer(ws)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"salesDepartmentCodes\":[\"sls\",\"revenue\"],\"supportDepartmentCodes\":[\"help\"]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.salesDepartmentCodes[1]").value("REVENUE"))
				.andExpect(jsonPath("$.data.supportDepartmentCodes[0]").value("HELP"));

		String id = UUID.randomUUID().toString();
		postEvent(event(employee(id, "Rev", "Enue", unique("rev"), "active", "REVENUE", "junior", null), Instant.now()))
				.andExpect(jsonPath("$.data.status").value("created"));

		mockMvc.perform(delete("/api/v1/integrations/hrms").header("Authorization", bearer(ws)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.connected").value(false));
		String later = UUID.randomUUID().toString();
		postEvent(event(employee(later, "Too", "Late", unique("late"), "active", "SLS", "junior", null), Instant.now()))
				.andExpect(jsonPath("$.data.status").value("ignored"));
		assertNull(userByHrmsId(ws, later));
		assertTrue(userByHrmsId(ws, id) != null, "existing accounts survive a disconnect");
	}

	// ---------------------------------------------------------------------------------------------- fake HRMS

	private static void handleHrms(HttpExchange ex) throws IOException {
		try (ex) {
			String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			String path = ex.getRequestURI().getRawPath();
			boolean signed = RequestSigner.verify(SECRET, ex.getRequestHeaders().getFirst(RequestSigner.TIMESTAMP_HEADER),
					ex.getRequestHeaders().getFirst(RequestSigner.SIGNATURE_HEADER), ex.getRequestMethod(),
					ex.getRequestURI().toString(), body, Instant.now());
			if (!signed) {
				respond(ex, 401, "{\"message\":\"Invalid or expired request signature\"}");
			} else if (path.endsWith("/link/redeem")) {
				if (body.toUpperCase().contains(GOOD_CODE)) {
					respond(ex, 200, "{\"organizationId\":\"" + hrmsOrgId + "\",\"organizationName\":\"Acme HR\"}");
				} else {
					respond(ex, 400, "{\"message\":\"This code is invalid or has expired. Ask your HR admin for a new one.\"}");
				}
			} else if (path.equals(HrmsClient.BASE_PATH + "/organizations/" + hrmsOrgId + "/employees")) {
				lastCompanyHeader = ex.getRequestHeaders().getFirst(HrmsClient.CRM_COMPANY_HEADER);
				respond(ex, 200, "{\"organizationId\":\"" + hrmsOrgId + "\",\"generatedAt\":\"2026-10-05T10:00:00\","
						+ "\"employees\":" + rosterJson + "}");
			} else {
				respond(ex, 404, "{\"message\":\"Not found\"}");
			}
		}
	}

	private static void respond(HttpExchange ex, int status, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		ex.getResponseBody().write(bytes);
	}

	// ------------------------------------------------------------------------------------------------ helpers

	private Workspace register() throws Exception {
		String email = unique("admin");
		String body = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of("name", "Ada Admin", "email", email,
								"password", "password123", "companyName", "Acme " + UUID.randomUUID()))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		JsonNode data = jsonMapper.readTree(body).path("data");
		return new Workspace(data.path("tokens").path("accessToken").asString(),
				data.path("user").path("companyId").asString(), data.path("user").path("id").asString(), email);
	}

	private ResultActions connect(Workspace ws) throws Exception {
		return mockMvc.perform(post("/api/v1/integrations/hrms/connect").header("Authorization", bearer(ws))
						.contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + GOOD_CODE.toLowerCase() + "\"}"))
				.andExpect(status().isOk());
	}

	private String createUser(Workspace ws, String name, String email, String role) throws Exception {
		String body = mockMvc.perform(post("/api/v1/users").header("Authorization", bearer(ws))
						.contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of("name", name, "email", email,
								"password", "password123", "role", role))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(body).path("data").path("id").asString();
	}

	private String loginAs(Workspace ws, String name, String role) throws Exception {
		String email = unique(name.toLowerCase());
		createUser(ws, name, email, role);
		return login(email, "password123");
	}

	private String login(String email, String password) throws Exception {
		String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of("email", email, "password", password))))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return jsonMapper.readTree(body).path("data").path("tokens").path("accessToken").asString();
	}

	private ResultActions postEvent(String body) throws Exception {
		long ts = Instant.now().getEpochSecond();
		return mockMvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).content(body)
				.header(RequestSigner.TIMESTAMP_HEADER, Long.toString(ts))
				.header(RequestSigner.SIGNATURE_HEADER, RequestSigner.sign(SECRET, ts, "POST", EVENTS, body)));
	}

	private String event(Map<String, Object> employee, Instant occurredAt) {
		return event(hrmsOrgId, employee, occurredAt);
	}

	private String event(String orgId, Map<String, Object> employee, Instant occurredAt) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("eventId", UUID.randomUUID().toString());
		e.put("type", "employee.changed");
		e.put("occurredAt", occurredAt.toString());
		e.put("organizationId", orgId);
		e.put("employee", employee);
		return jsonMapper.writeValueAsString(e);
	}

	private static Map<String, Object> employee(String id, String first, String last, String email, String status,
			String dept, String level, String managerId) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("employeeId", id);
		e.put("organizationId", hrmsOrgId);
		e.put("firstName", first);
		e.put("lastName", last);
		e.put("email", email);
		e.put("employeeCode", "E-" + id.substring(0, 6));
		e.put("status", status);
		e.put("departmentCode", dept);
		e.put("departmentName", dept + " team");
		e.put("positionTitle", level + " role");
		e.put("positionLevel", level);
		e.put("managerEmployeeId", managerId);
		e.put("departmentManagerEmployeeId", null);
		e.put("hireDate", "2026-01-15");
		e.put("endDate", null);
		e.put("updatedAt", "2026-10-05T10:00:00");
		return e;
	}

	private Map<String, Object> userByHrmsId(Workspace ws, String employeeId) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select * from users where company_id = ? and hrms_employee_id = ?", ws.companyId(), employeeId);
		assertFalse(rows.size() > 1);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private String insertDeal(Workspace ws, String ownerId, String stage) {
		String id = "deal-" + UUID.randomUUID();
		jdbc.update("insert into deals (id, title, stage, company_id, owner_id, updated_at) "
				+ "values (?, 'Deal', cast(? as \"DealStage\"), ?, ?, now())", id, stage, ws.companyId(), ownerId);
		return id;
	}

	private String insertTicket(Workspace ws, String assigneeId, String status) {
		String id = "ticket-" + UUID.randomUUID();
		jdbc.update("insert into tickets (id, ticket_number, title, description, status, company_id, assignee_id, updated_at) "
				+ "values (?, ?, 'Help', 'Please help', cast(? as \"TicketStatus\"), ?, ?, now())",
				id, "T-" + UUID.randomUUID(), status, ws.companyId(), assigneeId);
		return id;
	}

	private String insertContact(Workspace ws, String ownerId) {
		String id = "contact-" + UUID.randomUUID();
		jdbc.update("insert into contacts (id, name, company_id, owner_id, updated_at) values (?, 'Client', ?, ?, now())",
				id, ws.companyId(), ownerId);
		return id;
	}

	private int count(String sql, Object... args) {
		Integer n = jdbc.queryForObject(sql, Integer.class, args);
		return n == null ? 0 : n;
	}

	private String value(String sql, Object... args) {
		return jdbc.queryForObject(sql, String.class, args);
	}

	private static String bearer(Workspace ws) {
		return "Bearer " + ws.token();
	}

	private static String unique(String name) {
		return name + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}
}
