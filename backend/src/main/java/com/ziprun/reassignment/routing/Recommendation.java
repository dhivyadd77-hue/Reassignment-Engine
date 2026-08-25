package com.ziprun.reassignment.routing;

public record Recommendation(String agentId, double confidence, String reasoning, String strategyUsed) {

	public Recommendation(String agentId, double confidence, String reasoning) {
		this(agentId, confidence, reasoning, "rule");
	}
}
