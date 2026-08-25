package com.ziprun.reassignment.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Suggestion REST endpoints")
class SuggestionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("Positive: PATCH /suggestions/{id} ACCEPTED moves order to REASSIGNED and returns 200")
	void acceptSuggestionReassignsOrderAndUpdatesLoad() throws Exception {
		// given — create on-demand suggestion for ORD-007 (assigned to AGT-003)
		MvcResult createResult = mockMvc.perform(post("/api/orders/ORD-007/suggest"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andReturn();

		JsonNode suggestion = objectMapper.readTree(createResult.getResponse().getContentAsString());
		long suggestionId = suggestion.get("id").asLong();
		String recommendedAgentId = suggestion.get("recommendedAgentId").asText();

		MvcResult agentsBefore = mockMvc.perform(get("/api/agents")).andReturn();
		int loadBefore = findAgentLoad(agentsBefore.getResponse().getContentAsString(), recommendedAgentId);

		// when
		mockMvc.perform(patch("/api/suggestions/" + suggestionId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"ACCEPTED\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACCEPTED"));

		// then
		mockMvc.perform(get("/api/orders?status=REASSIGNED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == 'ORD-007')].assignedAgentId").value(org.hamcrest.Matchers.hasItem(recommendedAgentId)))
				.andExpect(jsonPath("$[?(@.id == 'ORD-007')].status").value(org.hamcrest.Matchers.hasItem("REASSIGNED")));

		MvcResult agentsAfter = mockMvc.perform(get("/api/agents")).andReturn();
		int loadAfter = findAgentLoad(agentsAfter.getResponse().getContentAsString(), recommendedAgentId);
		assertThat(loadAfter).isEqualTo(loadBefore + 1);
	}

	@Test
	@DisplayName("Negative: PATCH ACCEPTED on already-processed suggestion returns 400 Bad Request")
	void acceptAlreadyProcessedSuggestionReturnsBadRequest() throws Exception {
		// given — dedicated order so prior offline replans (e.g. ORD-008) cannot leave a PENDING card
		mockMvc.perform(post("/api/orders")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"id":"ORD-REJECT-TEST","description":"reject flow","assignedAgentId":"AGT-004"}
								"""))
				.andExpect(status().isCreated());

		MvcResult createResult = mockMvc.perform(post("/api/orders/ORD-REJECT-TEST/suggest"))
				.andExpect(status().isCreated())
				.andReturn();
		long suggestionId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

		mockMvc.perform(patch("/api/suggestions/" + suggestionId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"REJECTED\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"));

		// when / then — second decision on same suggestion
		mockMvc.perform(patch("/api/suggestions/" + suggestionId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"ACCEPTED\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("already decided")));
	}

	@Test
	@DisplayName("Negative: PATCH unknown suggestion id returns 400")
	void patchUnknownSuggestionReturnsBadRequest() throws Exception {
		mockMvc.perform(patch("/api/suggestions/999999")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"ACCEPTED\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Unknown suggestion")));
	}

	private int findAgentLoad(String agentsJson, String agentId) throws Exception {
		JsonNode array = objectMapper.readTree(agentsJson);
		for (JsonNode node : array) {
			if (agentId.equals(node.get("id").asText())) {
				return node.get("activeOrderCount").asInt();
			}
		}
		throw new AssertionError("Agent not found: " + agentId);
	}
}
