package com.ziprun.reassignment.dto;

import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.OrderStatus;
import com.ziprun.reassignment.domain.SuggestionStatus;

public final class ApiDtos {

	private ApiDtos() {
	}

	public record CreateOrderRequest(
			String id,
			String description,
			String assignedAgentId,
			String weightClass,
			String pickupZone,
			String dropoffZone) {
	}

	public record AgentStatusRequest(AgentStatus status) {
	}

	public record SuggestionDecisionRequest(SuggestionStatus status) {
	}

	/**
	 * Agent card payload. {@code manualAction} is the only UI control:
	 * {@code OFFLINE} → show "Go OFFLINE"; {@code AVAILABLE} → show "Make AVAILABLE".
	 */
	public record AgentResponse(
			String id,
			String name,
			int activeOrderCount,
			AgentStatus status,
			Integer maxCapacity,
			boolean manualOverride,
			AgentStatus manualAction) {
	}

	public record OrderResponse(
			String id,
			String description,
			String assignedAgentId,
			OrderStatus status,
			String weightClass,
			String pickupZone,
			String dropoffZone) {
	}
}
