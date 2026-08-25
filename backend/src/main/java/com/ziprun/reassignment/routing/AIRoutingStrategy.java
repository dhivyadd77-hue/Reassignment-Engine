package com.ziprun.reassignment.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ziprun.reassignment.ai.LLMGateway;
import com.ziprun.reassignment.ai.PromptFactory;
import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.Order;
import com.ziprun.reassignment.domain.TriggerReason;
import com.ziprun.reassignment.repository.AgentRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component("ai")
public class AIRoutingStrategy implements RoutingStrategy {

	private static final Logger log = LoggerFactory.getLogger(AIRoutingStrategy.class);

	private final LLMGateway llmGateway;
	private final PromptFactory promptFactory;
	private final RuleBasedRoutingStrategy ruleBasedRoutingStrategy;
	private final ObjectMapper objectMapper;
	private final AgentRepository agentRepository;

	public AIRoutingStrategy(
			LLMGateway llmGateway,
			PromptFactory promptFactory,
			RuleBasedRoutingStrategy ruleBasedRoutingStrategy,
			ObjectMapper objectMapper,
			AgentRepository agentRepository) {
		this.llmGateway = llmGateway;
		this.promptFactory = promptFactory;
		this.ruleBasedRoutingStrategy = ruleBasedRoutingStrategy;
		this.objectMapper = objectMapper;
		this.agentRepository = agentRepository;
	}

	@Override
	public List<Recommendation> recommend(Order order, List<Agent> availableAgents) {
		RoutingContext ctx = RoutingContextHolder.get();
		try {
			String prompt = buildPrompt(order, availableAgents, ctx);
			String raw = llmGateway.callLLM(prompt);
			Recommendation parsed = parseAndValidate(raw, availableAgents, ctx.failedAgentId());
			return List.of(new Recommendation(
					parsed.agentId(),
					parsed.confidence(),
					parsed.reasoning(),
					"ai"));
		} catch (Exception ex) {
			log.warn("AI routing failed for order={} — falling back to rule strategy: {}",
					order.getId(), ex.getMessage());
			return fallbackToRule(order, availableAgents, ex);
		}
	}

	private List<Recommendation> fallbackToRule(Order order, List<Agent> availableAgents, Exception aiFailure) {
		try {
			Recommendation fallback = ruleBasedRoutingStrategy.recommend(order, availableAgents).get(0);
			return List.of(new Recommendation(
					fallback.agentId(),
					fallback.confidence(),
					"[AI fallback] " + fallback.reasoning(),
					"ai-fallback-rule"));
		} catch (Exception ruleFailure) {
			log.error("Rule fallback also failed for order={} after AI error: {}",
					order.getId(), aiFailure.getMessage());
			throw new IllegalStateException(
					"AI and rule routing both failed for order " + order.getId(),
					ruleFailure);
		}
	}

	private String buildPrompt(Order order, List<Agent> availableAgents, RoutingContext ctx) {
		if (ctx.triggerReason() == TriggerReason.AGENT_OFFLINE) {
			return promptFactory.buildOfflineRecoveryPrompt(
					order,
					availableAgents,
					ctx.failedAgentId(),
					ctx.strandedOrderCount());
		}
		return promptFactory.buildInitialPrompt(order, availableAgents);
	}

	/**
	 * Validates LLM output against the candidate roster and a fresh DB read
	 * so hallucinated or newly-OFFLINE agents never pass through.
	 */
	private Recommendation parseAndValidate(String raw, List<Agent> availableAgents, String failedAgentId)
			throws Exception {
		String json = extractJson(raw);
		JsonNode node = objectMapper.readTree(json);
		String agentId = node.path("agentId").asText(null);
		double confidence = node.path("confidence").asDouble(0.5);
		String reasoning = node.path("reasoning").asText(null);

		if (agentId == null || agentId.isBlank()) {
			throw new IllegalArgumentException("Missing agentId in LLM response");
		}
		if (reasoning == null || reasoning.isBlank()) {
			throw new IllegalArgumentException("Missing reasoning in LLM response");
		}

		Set<String> rosterIds = availableAgents.stream().map(Agent::getId).collect(Collectors.toSet());
		if (!rosterIds.contains(agentId)) {
			throw new IllegalArgumentException("Hallucinated agentId not in roster: " + agentId);
		}
		if (failedAgentId != null && failedAgentId.equals(agentId)) {
			throw new IllegalArgumentException("AI recommended failed agent: " + agentId);
		}

		Agent dbAgent = agentRepository.findById(agentId)
				.orElseThrow(() -> new IllegalArgumentException("Agent not found in DB: " + agentId));
		if (dbAgent.getStatus() == AgentStatus.OFFLINE) {
			throw new IllegalArgumentException("AI recommended OFFLINE agent (DB): " + agentId);
		}

		confidence = Math.max(0.0, Math.min(1.0, confidence));
		return new Recommendation(agentId, confidence, reasoning, "ai");
	}

	private String extractJson(String raw) {
		if (raw == null) {
			throw new IllegalArgumentException("Empty LLM response");
		}
		String trimmed = raw.trim();
		int start = trimmed.indexOf('{');
		int end = trimmed.lastIndexOf('}');
		if (start < 0 || end < start) {
			throw new IllegalArgumentException("No JSON object in LLM response");
		}
		return trimmed.substring(start, end + 1);
	}
}
