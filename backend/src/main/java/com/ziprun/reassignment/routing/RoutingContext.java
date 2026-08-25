package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.domain.TriggerReason;

public record RoutingContext(
		TriggerReason triggerReason,
		String failedAgentId,
		int strandedOrderCount) {

	public static RoutingContext initial() {
		return new RoutingContext(TriggerReason.INITIAL, null, 0);
	}

	public static RoutingContext offline(String failedAgentId, int strandedOrderCount) {
		return new RoutingContext(TriggerReason.AGENT_OFFLINE, failedAgentId, strandedOrderCount);
	}
}
