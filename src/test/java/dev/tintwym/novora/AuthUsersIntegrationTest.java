package dev.tintwym.novora;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

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
class AuthUsersIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired(required = false)
	private JdbcTemplate jdbcTemplate;

	private boolean dbAvailable;

	@BeforeEach
	void setUp() {
		dbAvailable = false;
		if (jdbcTemplate != null) {
			try {
				jdbcTemplate.queryForObject("select 1", Integer.class);
				dbAvailable = true;
			} catch (Exception ignored) {
				dbAvailable = false;
			}
		}
	}

	@Test
	void registerLoginAndListUsers() throws Exception {
		Assumptions.assumeTrue(dbAvailable, "Skipping — PostgreSQL not available");

		long suffix = System.currentTimeMillis();
		String email = "admin-" + suffix + "@example.com";
		String password = "password123";

		MvcResult register = mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of(
								"name", "Test Admin",
								"email", email,
								"password", password,
								"companyName", "Acme " + suffix))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andReturn();

		JsonNode registerBody = jsonMapper.readTree(register.getResponse().getContentAsString());
		String accessToken = registerBody.path("data").path("tokens").path("accessToken").asString();

		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of(
								"email", email,
								"password", "wrong-password"))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false));

		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of(
								"email", email,
								"password", password))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.tokens.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.data.user.email").value(email));

		mockMvc.perform(get("/api/v1/users")
						.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").isArray());

		mockMvc.perform(post("/api/v1/users")
						.header("Authorization", "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of(
								"name", "Sales Rep",
								"email", "rep-" + suffix + "@example.com",
								"password", "password123",
								"role", "SALES_REP"))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.role").value("SALES_REP"));

		mockMvc.perform(get("/api/v1/users")
						.header("Authorization", "Bearer not-a-real-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").exists());
	}
}
