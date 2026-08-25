package com.ziprun.reassignment.ai;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.Order;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class PromptFactory {

	public String buildInitialPrompt(Order order, List<Agent> availableAgents) {
		return """
				You are a ZipRun dispatch advisor helping ops choose an agent for an on-demand reassignment suggestion.

				This is an INITIAL suggestion (not a mid-shift failure recovery). Prefer the available agent with the lowest current load unless the order description clearly implies a better fit.

				ORDER:
				- id: %s
				- description: %s
				- currently assigned to: %s

				AVAILABLE AGENTS:
				%s

				Respond with ONLY valid JSON (no markdown):
				{"agentId":"<one of the available agent ids>","confidence":0.0-1.0,"reasoning":"<one plain-English sentence for ops>"}
				""".formatted(
				order.getId(),
				order.getDescription(),
				order.getAssignedAgentId(),
				formatAgents(availableAgents));
	}

	public String buildOfflineRecoveryPrompt(
			Order order,
			List<Agent> availableAgents,
			String failedAgentId,
			int strandedOrderCount) {
		return """
				You are a ZipRun recovery advisor. An agent just went OFFLINE mid-shift and stranded deliveries need reassignment.

				This is FAILURE RECOVERY, not a first assignment.
				- Failed agent id: %s
				- Total stranded orders in this incident: %d
				- Do NOT recommend the failed agent.
				- Prefer agents with spare capacity (low activeOrderCount) who can absorb recovery load quickly.
				- Explain the recovery rationale clearly for ops.

				STRANDED ORDER:
				- id: %s
				- description: %s
				- previously assigned to failed agent: %s

				REMAINING AVAILABLE AGENTS:
				%s

				Respond with ONLY valid JSON (no markdown):
				{"agentId":"<one of the available agent ids>","confidence":0.0-1.0,"reasoning":"<one plain-English recovery sentence for ops>"}
				""".formatted(
				failedAgentId,
				strandedOrderCount,
				order.getId(),
				order.getDescription(),
				failedAgentId,
				formatAgents(availableAgents));
	}

	private String formatAgents(List<Agent> agents) {
		return agents.stream()
				.map(a -> "- %s | %s | load=%d | status=%s".formatted(
						a.getId(), a.getName(), a.getActiveOrderCount(), a.getStatus()))
				.collect(Collectors.joining("\n"));
	}
}
