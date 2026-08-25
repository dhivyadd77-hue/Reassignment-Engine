package com.ziprun.reassignment.dto;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.dto.ApiDtos.AgentResponse;
import com.ziprun.reassignment.service.AgentService;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link Agent} entities to API responses with the single allowed manual action
 * for the Agent Card UI.
 */
@Component
public class AgentMapper {

	public AgentResponse toResponse(Agent agent) {
		if (agent == null) {
			return null;
		}
		return new AgentResponse(
				agent.getId(),
				agent.getName(),
				agent.getActiveOrderCount(),
				agent.getStatus(),
				agent.getMaxCapacity(),
				agent.isManualOverride(),
				manualActionFor(agent.getStatus()));
	}

	public List<AgentResponse> toResponseList(List<Agent> agents) {
		return agents.stream().map(this::toResponse).toList();
	}

	/**
	 * Case A — AVAILABLE/BUSY → only Go OFFLINE.
	 * Case B — OFFLINE → only Make AVAILABLE.
	 */
	public static AgentStatus manualActionFor(AgentStatus status) {
		if (status == AgentStatus.OFFLINE) {
			return AgentStatus.AVAILABLE;
		}
		return AgentStatus.OFFLINE;
	}

	/** Expected auto status for a given load (ignores OFFLINE lock). */
	public static AgentStatus expectedLoadStatus(int activeOrderCount) {
		return AgentService.statusFromLoad(activeOrderCount);
	}
}
