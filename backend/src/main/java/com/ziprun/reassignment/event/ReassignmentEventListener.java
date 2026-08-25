package com.ziprun.reassignment.event;

import com.ziprun.reassignment.service.ReassignmentService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Non-blocking agentic re-plan loop.
 * After an agent is marked OFFLINE (and the status TX commits), loads only that agent's
 * ASSIGNED / IN_TRANSIT orders and queues suggestions.
 */
@Component
public class ReassignmentEventListener {

	private static final Logger log = LoggerFactory.getLogger(ReassignmentEventListener.class);

	private final ReassignmentService reassignmentService;

	public ReassignmentEventListener(ReassignmentService reassignmentService) {
		this.reassignmentService = reassignmentService;
	}

	@Async("taskExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onAgentWentOffline(AgentWentOfflineEvent event) {
		log.info("Async re-plan started for offline agent {}", event.agentId());
		try {
			reassignmentService.replanForOfflineAgent(event.agentId());
			log.info("Async re-plan finished for offline agent {}", event.agentId());
		} catch (Exception ex) {
			log.error("Async re-plan failed for agent {}", event.agentId(), ex);
		}
	}
}
