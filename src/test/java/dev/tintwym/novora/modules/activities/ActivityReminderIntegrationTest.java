package dev.tintwym.novora.modules.activities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "novora.activities.reminder-cron=-")
class ActivityReminderIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private ActivityReminderService reminders;

	@Autowired(required = false)
	private JdbcTemplate jdbc;

	private boolean dbAvailable;

	@BeforeEach
	void setUp() {
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
	}

	@Test
	void remindersArePersonalAndEmailRunsOnce() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		Workspace a = register("Ada-" + suffix, "ada-" + suffix + "@example.com");
		Workspace b = register("Bea-" + suffix, "bea-" + suffix + "@example.com");

		String contactId = createContact(a, "Client " + suffix);
		String dueSoon = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
		String aTask = createTask(a, contactId, "Ada follow-up", dueSoon);
		String bTask = createTask(b, createContact(b, "Other " + suffix), "Bea follow-up", dueSoon);

		mockMvc.perform(get("/api/v1/activities/reminders").header("Authorization", "Bearer " + a.token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].id").value(aTask));

		mockMvc.perform(get("/api/v1/activities?completed=false&mine=true")
						.header("Authorization", "Bearer " + a.token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].id").value(aTask));

		assertEquals(2, reminders.sendDueReminders());
		assertNotNull(jdbc.queryForObject(
				"select reminder_email_sent_at from activities where id = ?", Instant.class, aTask));
		assertNotNull(jdbc.queryForObject(
				"select reminder_email_sent_at from activities where id = ?", Instant.class, bTask));
		assertEquals(0, reminders.sendDueReminders());

		mockMvc.perform(patch("/api/v1/activities/" + aTask)
						.header("Authorization", "Bearer " + a.token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"completed\":true}"))
				.andExpect(status().isOk());
		mockMvc.perform(patch("/api/v1/activities/" + aTask)
						.header("Authorization", "Bearer " + a.token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"completed\":false}"))
				.andExpect(status().isOk());
		assertNull(jdbc.queryForObject(
				"select reminder_email_sent_at from activities where id = ?", Instant.class, aTask));
		assertTrue(reminders.sendDueReminders() >= 1);
	}

	private record Workspace(String token, String companyId) {}

	private Workspace register(String name, String email) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of(
								"name", name,
								"email", email,
								"password", "password123",
								"companyName", "Co " + email))))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode data = jsonMapper.readTree(result.getResponse().getContentAsString()).path("data");
		return new Workspace(data.path("tokens").path("accessToken").asString(),
				data.path("user").path("companyId").asString());
	}

	private String createContact(Workspace ws, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/contacts")
						.header("Authorization", "Bearer " + ws.token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of("name", name))))
				.andExpect(status().isCreated())
				.andReturn();
		return jsonMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asString();
	}

	private String createTask(Workspace ws, String contactId, String subject, String dueAt) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/activities")
						.header("Authorization", "Bearer " + ws.token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of(
								"type", "TASK",
								"subject", subject,
								"contactId", contactId,
								"dueAt", dueAt))))
				.andExpect(status().isCreated())
				.andReturn();
		return jsonMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asString();
	}
}
