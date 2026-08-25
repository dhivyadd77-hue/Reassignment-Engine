package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.Order;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component("rule")
public class RuleBasedRoutingStrategy implements RoutingStrategy {

	@Override
	public List<Recommendation> recommend(Order order, List<Agent> availableAgents) {
		RoutingContext ctx = RoutingContextHolder.get();
		String failedAgentId = ctx.failedAgentId();

		Agent best = availableAgents.stream()
				.filter(a -> a.getStatus() != AgentStatus.OFFLINE)
				.filter(a -> failedAgentId == null || !failedAgentId.equals(a.getId()))
				.min(Comparator.comparingInt(Agent::getActiveOrderCount).thenComparing(Agent::getId))
				.orElseThrow(() -> new IllegalStateException(
						"No eligible (non-OFFLINE) agents to recommend for order " + order.getId()));

		String reasoning = String.format(
				"Least-loaded eligible agent: %s (%s) currently carries %d active order(s) [status=%s].",
				best.getName(),
				best.getId(),
				best.getActiveOrderCount(),
				best.getStatus());

		return List.of(new Recommendation(best.getId(), 0.70, reasoning, "rule"));
	}
}
