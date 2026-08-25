package com.ziprun.reassignment.service;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.OrderStatus;
import com.ziprun.reassignment.event.AgentStatusChangedEvent;
import com.ziprun.reassignment.event.AgentWentOfflineEvent;
import com.ziprun.reassignment.event.StatusChangeCause;
import com.ziprun.reassignment.repository.AgentRepository;
import com.ziprun.reassignment.repository.OrderRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

/**
 * Agent status service — load-driven AVAILABLE/BUSY with manual OFFLINE only.
 * <ul>
 *   <li>{@code activeOrderCount == 0} → {@link AgentStatus#AVAILABLE}</li>
 *   <li>{@code activeOrderCount >= 1} → {@link AgentStatus#BUSY}</li>
 *   <li>Managers may only PATCH {@code OFFLINE} (while online) or {@code AVAILABLE} (while OFFLINE)</li>
 *   <li>OFFLINE blocks auto transitions until load drains to 0 (lock cleared) and user chooses Make AVAILABLE</li>
 * </ul>
 */
@Service
public class AgentService {

	private static final Logger log = LoggerFactory.getLogger(AgentService.class);

	private static final Set<OrderStatus> OWNED_LOAD_STATUSES = EnumSet.of(
			OrderStatus.ASSIGNED,
			OrderStatus.IN_TRANSIT,
			OrderStatus.REASSIGNMENT_PENDING);

	private final AgentRepository agentRepository;
	private final OrderRepository orderRepository;
	private final ApplicationEventPublisher eventPublisher;

	public AgentService(
			AgentRepository agentRepository,
			OrderRepository orderRepository,
			ApplicationEventPublisher eventPublisher) {
		this.agentRepository = agentRepository;
		this.orderRepository = orderRepository;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Manual PATCH — only {@link AgentStatus#AVAILABLE} (from OFFLINE) and {@link AgentStatus#OFFLINE}
	 * (from online) are accepted. AVAILABLE/BUSY while online are never user-selectable.
	 */
	@Transactional
	public Agent applyManualStatus(String agentId, AgentStatus requested) {
		if (requested == null) {
			throw new IllegalArgumentException("status is required");
		}
		if (requested == AgentStatus.BUSY) {
			throw new IllegalArgumentException(
					"BUSY is managed automatically from order load; set OFFLINE or Make AVAILABLE");
		}

		Agent agent = requireLocked(agentId);
		AgentStatus previous = agent.getStatus();

		switch (requested) {
			case AVAILABLE -> {
				if (previous != AgentStatus.OFFLINE) {
					throw new IllegalArgumentException(
							"Cannot manually set AVAILABLE while agent is online; AVAILABLE/BUSY follow order load");
				}
				agent.setManualOverride(false);
				// Leave OFFLINE before load reconcile so auto rules can apply.
				agent.setStatus(statusFromLoad(agent.getActiveOrderCount()));
				reconcileLoadStatus(agent);
			}
			case OFFLINE -> {
				if (previous == AgentStatus.OFFLINE) {
					return agent;
				}
				agent.setManualOverride(true);
				agent.setStatus(AgentStatus.OFFLINE);
			}
			default -> throw new IllegalArgumentException("Unsupported status: " + requested);
		}

		Agent saved = agentRepository.save(agent);
		publishTransitions(agentId, previous, saved.getStatus(), StatusChangeCause.MANUAL);
		return saved;
	}

	@Transactional
	public Agent onOrderAssigned(String agentId) {
		Agent agent = requireLocked(agentId);
		AgentStatus previous = agent.getStatus();

		agent.setActiveOrderCount(agent.getActiveOrderCount() + 1);

		if (isOfflineLocked(agent)) {
			log.debug("Agent {} OFFLINE (manual) — load {} unchanged status",
					agentId, agent.getActiveOrderCount());
		} else {
			reconcileLoadStatus(agent);
		}

		Agent saved = agentRepository.save(agent);
		publishTransitions(agentId, previous, saved.getStatus(), StatusChangeCause.AUTO_LOAD);
		return saved;
	}

	@Transactional
	public Agent onOrderReleased(String agentId) {
		Agent agent = requireLocked(agentId);
		AgentStatus previous = agent.getStatus();

		agent.setActiveOrderCount(Math.max(0, agent.getActiveOrderCount() - 1));
		return finishLoadChange(agent, previous);
	}

	/**
	 * After a stranded order is reassigned away, recompute load from remaining owned orders.
	 * When the offline agent's load hits 0, clears the manual lock so Make AVAILABLE restores AVAILABLE.
	 */
	@Transactional
	public Agent syncLoadFromOwnedOrders(String agentId) {
		Agent agent = requireLocked(agentId);
		AgentStatus previous = agent.getStatus();

		int remaining = (int) orderRepository.countByAssignedAgentIdAndStatusIn(agentId, OWNED_LOAD_STATUSES);
		agent.setActiveOrderCount(remaining);
		log.info("Agent {} load synced to {} from owned orders", agentId, remaining);

		return finishLoadChange(agent, previous);
	}

	private Agent finishLoadChange(Agent agent, AgentStatus previous) {
		if (isOfflineLocked(agent)) {
			if (agent.getActiveOrderCount() == 0) {
				agent.setManualOverride(false);
				log.info("Agent {} load drained to 0 while OFFLINE — cleared manual lock", agent.getId());
			} else {
				log.debug("Agent {} OFFLINE (manual) — load {} unchanged status",
						agent.getId(), agent.getActiveOrderCount());
			}
		} else {
			reconcileLoadStatus(agent);
		}

		Agent saved = agentRepository.save(agent);
		publishTransitions(agent.getId(), previous, saved.getStatus(), StatusChangeCause.AUTO_LOAD);
		return saved;
	}

	/**
	 * AVAILABLE only when load is 0; BUSY when load &gt;= 1.
	 * Never overrides a manual OFFLINE lock.
	 */
	private void reconcileLoadStatus(Agent agent) {
		if (agent.getStatus() == AgentStatus.OFFLINE) {
			return;
		}
		AgentStatus target = statusFromLoad(agent.getActiveOrderCount());
		if (agent.getStatus() != target) {
			log.info("Agent {} auto {} — load {}", agent.getId(), target, agent.getActiveOrderCount());
		}
		agent.setStatus(target);
		agent.setManualOverride(false);
	}

	/** Public rule used by DTO mapping / tests. */
	public static AgentStatus statusFromLoad(int activeOrderCount) {
		return activeOrderCount <= 0 ? AgentStatus.AVAILABLE : AgentStatus.BUSY;
	}

	private static boolean isOfflineLocked(Agent agent) {
		return agent.getStatus() == AgentStatus.OFFLINE;
	}

	private Agent requireLocked(String agentId) {
		return agentRepository.findByIdForUpdate(agentId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + agentId));
	}

	private void publishTransitions(
			String agentId,
			AgentStatus previous,
			AgentStatus current,
			StatusChangeCause cause) {
		if (previous == current) {
			return;
		}
		log.info("Agent {} status {} → {} ({})", agentId, previous, current, cause);
		eventPublisher.publishEvent(new AgentStatusChangedEvent(agentId, previous, current, cause));
		if (current == AgentStatus.OFFLINE) {
			log.info("Agent {} went OFFLINE — publishing async re-plan event", agentId);
			eventPublisher.publishEvent(new AgentWentOfflineEvent(agentId));
		}
	}
}
