package com.ziprun.reassignment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReassignmentFlowIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("On-demand suggest creates INITIAL pending suggestion via rule strategy")
	void suggestCreatesInitialSuggestion() throws Exception {
		mockMvc.perform(post("/api/orders/ORD-003/suggest"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.orderId").value("ORD-003"))
				.andExpect(jsonPath("$.triggerReason").value("INITIAL"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.recommendedAgentId").exists())
				.andExpect(jsonPath("$.reasoning").isNotEmpty());
	}

	@Test
	@DisplayName("PATCH agent OFFLINE returns immediately and async re-plan creates AGENT_OFFLINE suggestions")
	void offlineTriggersAsyncReplan() throws Exception {
		mockMvc.perform(patch("/api/agents/AGT-005/status")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"OFFLINE\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OFFLINE"));

		// Allow async listener to finish
		Thread.sleep(2500);

		mockMvc.perform(get("/api/suggestions?status=PENDING"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()", greaterThanOrEqualTo(1)))
				.andExpect(jsonPath("$[?(@.triggerReason == 'AGENT_OFFLINE')]").isNotEmpty());
	}
}
