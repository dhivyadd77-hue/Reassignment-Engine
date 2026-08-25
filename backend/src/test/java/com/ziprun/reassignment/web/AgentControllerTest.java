package com.ziprun.reassignment.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Agent REST endpoints")
class AgentControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("Positive: PATCH /agents/{id}/status returns 200 quickly without blocking on re-plan")
	void patchAgentOfflineReturnsFastOk() throws Exception {
		// given / when
		long started = System.nanoTime();
		MvcResult result = mockMvc.perform(patch("/api/agents/AGT-001/status")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"OFFLINE\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value("AGT-001"))
				.andExpect(jsonPath("$.status").value("OFFLINE"))
				.andReturn();
		long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

		// then — HTTP path must not wait on LLM / full re-plan
		assertThat(elapsedMs)
				.as("PATCH OFFLINE must not block on async re-plan/LLM (elapsed=%d ms)", elapsedMs)
				.isLessThan(250L);
		assertThat(result.getResponse().getStatus()).isEqualTo(200);

		// async re-plan may still be running; give it a moment then confirm suggestions appear
		Thread.sleep(2000);
		mockMvc.perform(get("/api/suggestions?status=PENDING"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.triggerReason == 'AGENT_OFFLINE')]").isNotEmpty());
	}

	@Test
	@DisplayName("Negative: PATCH unknown agent returns 400 Bad Request")
	void patchUnknownAgentReturnsBadRequest() throws Exception {
		mockMvc.perform(patch("/api/agents/AGT-DOES-NOT-EXIST/status")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"OFFLINE\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Unknown agent")));
	}

	@Test
	@DisplayName("Positive: GET /agents returns seeded roster")
	void listAgentsReturnsSeedData() throws Exception {
		mockMvc.perform(get("/api/agents"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)));
	}
}
