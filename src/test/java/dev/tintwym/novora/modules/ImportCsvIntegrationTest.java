package dev.tintwym.novora.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ImportCsvIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired(required = false)
	private JdbcTemplate jdbc;

	private boolean dbAvailable;
	private String token;
	private String companyId;

	@BeforeEach
	void setUp() throws Exception {
		dbAvailable = false;
		if (jdbc != null) {
			try {
				jdbc.queryForObject("select 1", Integer.class);
				dbAvailable = true;
			} catch (Exception ignored) {
				dbAvailable = false;
			}
		}
		Assumptions.assumeTrue(dbAvailable, "Skipping — PostgreSQL not available");
		Workspace ws = register();
		token = ws.token();
		companyId = ws.companyId();
	}

	@Test
	void importsCompaniesAndSkipsDuplicates() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String name = "Acme Import Co " + suffix;
		String body = jsonMapper.writeValueAsString(Map.of(
				"companies", List.of(
						Map.of("name", name, "industry", "Tech", "email", "hello@" + suffix + ".test"),
						Map.of("name", name, "industry", "Other"),
						Map.of("industry", "Missing name"))));

		mockMvc.perform(post("/api/v1/companies/import")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.imported").value(1))
				.andExpect(jsonPath("$.data.skipped.length()").value(1))
				.andExpect(jsonPath("$.data.errors.length()").value(1));

		assertEquals(1, jdbc.queryForObject(
				"select count(*) from accounts where company_id = ? and name = ?",
				Integer.class, companyId, name));
	}

	@Test
	void importsDealsResolvingCompanyAndStageLabels() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String dealTitle = "Big Sale " + suffix;
		String accountName = "Northwind Traders " + suffix;
		String body = jsonMapper.writeValueAsString(Map.of(
				"deals", List.of(
						Map.of("title", dealTitle, "stage", "Qualified", "value", "1200",
								"company", accountName, "source", "Website"),
						Map.of("title", "Bad Stage " + suffix, "stage", "NotAStage"),
						Map.of("title", dealTitle, "company", accountName))));

		mockMvc.perform(post("/api/v1/deals/import")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.imported").value(1))
				.andExpect(jsonPath("$.data.errors.length()").value(1))
				.andExpect(jsonPath("$.data.skipped.length()").value(1));

		Map<String, Object> deal = jdbc.queryForMap(
				"select title, stage::text as stage, value from deals where company_id = ? and title = ?",
				companyId, dealTitle);
		assertEquals("QUALIFIED", deal.get("stage"));
		assertEquals(1, jdbc.queryForObject(
				"select count(*) from accounts where company_id = ? and name = ?",
				Integer.class, companyId, accountName));
	}

	private record Workspace(String token, String companyId) {}

	private Workspace register() throws Exception {
		String email = "import-" + UUID.randomUUID() + "@example.com";
		MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of(
								"name", "Import Admin",
								"email", email,
								"password", "password123",
								"companyName", "Import Co " + UUID.randomUUID()))))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode data = jsonMapper.readTree(result.getResponse().getContentAsString()).path("data");
		return new Workspace(
				data.path("tokens").path("accessToken").asString(),
				data.path("user").path("companyId").asString());
	}
}
