package com.ziprun.reassignment.event;

import com.ziprun.reassignment.domain.AgentStatus;

/**
 * Fired whenever an agent's status changes (manual PATCH or automatic load rules).
 * Listeners must not assume OFFLINE — use {@link AgentWentOfflineEvent} for re-plan.
 */
public record AgentStatusChangedEvent(
		String agentId,
		AgentStatus previousStatus,
		AgentStatus currentStatus,
		StatusChangeCause cause) {
}
