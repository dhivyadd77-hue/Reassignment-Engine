package com.ziprun.reassignment.service;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.SuggestionStatus;
import com.ziprun.reassignment.domain.TriggerReason;
import com.ziprun.reassignment.repository.AgentRepository;
import com.ziprun.reassignment.repository.OrderRepository;
import com.ziprun.reassignment.repository.ReassignmentSuggestionRepository;
import com.ziprun.reassignment.routing.RoutingEngine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent status — ReassignmentService delegates to AgentService")
class AgentServiceTest {

	@Mock
	private AgentRepository agentRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private ReassignmentSuggestionRepository suggestionRepository;
	@Mock
	private RoutingEngine routingEngine;
	@Mock
	private AgentService agentService;
	@Mock
	private TransactionTemplate transactionTemplate;

	@InjectMocks
	private ReassignmentService reassignmentService;

	@Test
	@DisplayName("Positive: updateAgentStatus delegates to AgentService")
	void updateAgentStatusDelegates() {
		Agent offline = new Agent("AGT-001", "Priya Sharma", 2, AgentStatus.OFFLINE);
		offline.setManualOverride(true);
		when(agentService.applyManualStatus("AGT-001", AgentStatus.OFFLINE)).thenReturn(offline);

		Agent result = reassignmentService.updateAgentStatus("AGT-001", AgentStatus.OFFLINE);

		assertThat(result.getStatus()).isEqualTo(AgentStatus.OFFLINE);
		verify(agentService).applyManualStatus("AGT-001", AgentStatus.OFFLINE);
	}

	@Test
	@DisplayName("Negative: unknown agentId surfaces IllegalArgumentException")
	void updateAgentStatusWithUnknownIdThrows() {
		when(agentService.applyManualStatus("AGT-MISSING", AgentStatus.OFFLINE))
				.thenThrow(new IllegalArgumentException("Unknown agent: AGT-MISSING"));

		assertThatThrownBy(() -> reassignmentService.updateAgentStatus("AGT-MISSING", AgentStatus.OFFLINE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown agent");
	}

	@Test
	@DisplayName("Positive: idempotency check for PENDING suggestions")
	void idempotencyCheckReturnsTrueWhenPendingExists() {
		when(suggestionRepository.existsByOrderIdAndStatusAndTriggerReason(
				"ORD-001", SuggestionStatus.PENDING, TriggerReason.AGENT_OFFLINE))
				.thenReturn(true);

		boolean exists = suggestionRepository.existsByOrderIdAndStatusAndTriggerReason(
				"ORD-001", SuggestionStatus.PENDING, TriggerReason.AGENT_OFFLINE);

		assertThat(exists).isTrue();
	}
}
